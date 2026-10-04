package com.miniseuleros.app.agent.orchestrator

import android.content.Context
import com.miniseuleros.app.provider.LLMProvider
import com.miniseuleros.app.sandbox.PRootKernel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-android-subagent-orchestration] The dispatcher. Takes the planner's
 * subtask list, resolves a model per subtask (per-task group_id override →
 * the configured default sub-agent group), schedules execution, and aggregates
 * one budget-bounded facts pack for the main model.
 *
 * Scheduling modes:
 *  - waves (default, WanXiang file-write lease): subtasks whose DECLARED write
 *    scopes collide (literal pattern intersection) run in separate sequential
 *    waves; independent ones run concurrently inside a wave (bounded by
 *    [SubAgentLimits.DEFAULT_CONCURRENCY]).
 *  - sequential: strict one-by-one.
 *  - parallel: everything at once (planner's responsibility if scopes collide).
 *
 * A JSONL journal of outcomes is appended per run (MAGE-inspired: decisions
 * and evidence stay inspectable after the pack is consumed).
 */
object SubAgentOrchestrator {

    /** Resolves (provider, display label) for a group id override; null when
     *  the group is unknown/empty — the caller turns that into a REJECTED. */
    /** [T-android-subagent-pool] Resolves the provider for one subtask.
     *  [poolIndex] is the subtask's ordinal in the dispatch run: a bound
     *  group is a POOL, so spec i lands on available member (i % size) —
     *  subtasks spread across the group's models instead of all queuing on
     *  the first member. Extra members stay as fallback (unavailable
     *  providers are skipped by the repository's member filter). */
    fun interface ModelResolver {
        suspend fun resolve(groupIdOverride: String?, poolIndex: Int): Pair<LLMProvider, String>?
    }

    suspend fun run(
        planSummary: String,
        specs: List<SubTaskSpec>,
        mode: String,
        sessionId: String,
        context: Context,
        modelResolver: ModelResolver,
        onProgress: (String) -> Unit,
    ): OrchestrationReport {
        val startedAt = System.currentTimeMillis()
        onProgress("📋 多模型协同 · 计划: ${planSummary.take(120)}")
        onProgress("共有 ${specs.size} 个子任务 · 调度=$mode")

        val waves: List<List<SubTaskSpec>> = when (mode) {
            "sequential" -> specs.map { listOf(it) }
            "parallel" -> listOf(specs)
            else -> waveSchedule(specs)
        }
        if (waves.size > 1) {
            onProgress("文件写租约: 划分为 ${waves.size} 波（写路径重叠的子任务串行，独立子任务并行）")
        }

        val outcomes = ArrayList<SubTaskOutcome>(specs.size)
        val index = AtomicInteger(0)

        // [T-android-subagent-pool] Stable per-spec ordinal for pool
        // rotation (see ModelResolver doc): spec i → available member
        // (i % poolSize) of its resolved group.
        val poolIndexOf = HashMap<String, Int>(specs.size)
        specs.forEachIndexed { idx, spec -> poolIndexOf[spec.id] = idx }

        for ((waveIdx, wave) in waves.withIndex()) {
            if (waves.size > 1) onProgress("— 第 ${waveIdx + 1}/${waves.size} 波: ${wave.joinToString { it.id }}")
            val resolved = HashMap<String, Pair<LLMProvider, String>>()
            val rejected = HashMap<String, String>()
            for (spec in wave) {
                val pair = modelResolver.resolve(spec.groupId, poolIndexOf[spec.id] ?: 0)
                if (pair == null) {
                    rejected[spec.id] = if (spec.groupId != null)
                        "模型组 ${spec.groupId} 不存在或无可用成员"
                    else "未配置子代理模型组（设置 → 模型组 → 子代理）"
                } else {
                    resolved[spec.id] = pair
                }
            }
            rejected.forEach { (id, why) ->
                outcomes.add(SubTaskOutcome(
                    taskId = id, title = specs.firstOrNull { it.id == id }?.title ?: id,
                    status = SubTaskStatus.REJECTED, error = why,
                ))
                onProgress("✗ [$id] 拒绝派发 · $why")
            }
            val runnable = wave.filter { it.id in resolved }
            if (runnable.isEmpty()) continue

            val semaphore = Semaphore(SubAgentLimits.DEFAULT_CONCURRENCY)
            supervisorScope {
                for (spec in runnable) {
                    launch {
                        semaphore.withPermit {
                            val (provider, label) = resolved[spec.id]!!
                            val runner = SubAgentRunner(
                                provider = provider,
                                modelLabel = label,
                                sessionId = sessionId,
                                context = context,
                                onEvent = onProgress,
                            )
                            // [T-dispatch-ledger] 任何异常都必须记成 outcome，否则账本
                            // 会丢掉整条记录（见 2026-09-28 22:08 dispatch 事故：子代理
                            // 上游 stall 抛 CancellationException，原 logic 在 runner.run
                            // 之后才 add，异常冒泡直接带崩整个 coroutineScope，outcomes
                            // 为空 → ledger 显示 0/0、明细空白、NEXT-STEPS 谎称"全部完成"）。
                            // supervisorScope 让单个子代理失败不再取消兄弟任务，且这里
                            // 用 NonCancellable 包住 outcome 构造，确保即使本协程正被取消
                            // 也能把失败如实入账（只有外部主动取消整段 dispatch 时才不记）。
                            val outcome = @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                            withContext(NonCancellable) {
                                try {
                                    runner.run(spec)
                                } catch (e: CancellationException) {
                                    // 仅当整段 dispatch 被外部主动取消（用户中止/会话关闭）
                                    // 时才让取消继续传播；子代理自身因上游 stall/超时而被取消
                                    // 时，父 scope 仍活跃，应记成 FAILED outcome 而非带崩全盘。
                                    val parent = (coroutineContext[Job] as? kotlinx.coroutines.Job)
                                        ?.parent
                                    if (parent?.isCancelled == true) {
                                        // 父 scope 也被取消了：让取消继续传播。
                                        throw e
                                    }
                                    SubTaskOutcome(
                                        taskId = spec.id,
                                        title = spec.title,
                                        status = SubTaskStatus.FAILED,
                                        model = label,
                                        error = "子任务被取消：${e.message ?: "上游中断或超时"}",
                                        durationMs = System.currentTimeMillis() - startedAt,
                                    )
                                } catch (e: Exception) {
                                    SubTaskOutcome(
                                        taskId = spec.id,
                                        title = spec.title,
                                        status = SubTaskStatus.FAILED,
                                        model = label,
                                        error = e.message ?: e.javaClass.simpleName,
                                        durationMs = System.currentTimeMillis() - startedAt,
                                    )
                                }
                            }
                            synchronized(outcomes) { outcomes.add(outcome) }
                        }
                    }
                }
            }
        }

        outcomes.sortBy { o -> specs.indexOfFirst { it.id == o.taskId } }
        val pack = buildPack(planSummary, mode, outcomes, startedAt)
        val reportFile = appendJournal(sessionId, context, planSummary, mode, outcomes)
        val handoffFile = writeHandoff(sessionId, context, planSummary, mode, outcomes, startedAt)

        onProgress("■ 编排完成: ${outcomes.count { it.success }}/${outcomes.size} 成功 · ${(System.currentTimeMillis() - startedAt) / 1000.0}s")
        if (handoffFile != null) onProgress("📋 协作交接已落盘: $handoffFile")
        return OrchestrationReport(
            planSummary = planSummary,
            mode = mode,
            outcomes = outcomes,
            packForMainModel = pack,
            reportFile = reportFile,
            handoffFile = handoffFile,
        )
    }

    /**
     * [T-android-subagent-handoff] Fixed-spec handoff ledger, written by the
     * SYSTEM after every dispatch — never by model goodwill. Field principle
     * (2026-09-26): "不能让模型白白付出但什么都没做出来" — compaction may
     * throw away the conversation, but the handoff file survives it, so the
     * next turn / next session / next compaction cycle can re-enter the work
     * at exactly where it stopped instead of re-running everything.
     *
     * Spec (deliberately rigid, grep-friendly):
     *   /var/minis-euleros/workspace/.collab/HANDOFF.md
     *   - one APPEND-ONLY ## [run <ts>] section per dispatch (history kept)
     *   - front matter = WHERE-WE-ARE pointer so a fresh context can orient
     *     in one read; per-subtask STATUS/summary/facts/files/issues;
     *     a NEXT-STEPS tail derived from actual BLOCKED/FAILED outcomes.
     */
    private fun writeHandoff(
        sessionId: String,
        context: Context,
        planSummary: String,
        mode: String,
        outcomes: List<SubTaskOutcome>,
        startedAt: Long,
    ): String? {
        val handoffLinuxPath = "/var/minis-euleros/workspace/.collab/HANDOFF.md"
        return try {
            val host = PRootKernel.resolveSessionHostPath(sessionId, handoffLinuxPath, context)
                ?: return null
            host.parentFile?.mkdirs()
            if (!host.exists()) {
                host.writeText(
                    "# 协作交接账本 · HANDOFF LEDGER\n\n" +
                        "> 本文件由系统在每次多模型协同派发后自动追加（append-only），" +
                        "格式固定；会话压缩/新会话请先读本文件恢复协作上下文。\n\n" +
                        "## WHERE-WE-ARE\n（暂无派发记录）\n\n"
                )
            }
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(startedAt))
            val durS = (System.currentTimeMillis() - startedAt) / 1000.0
            val done = outcomes.count { it.success }
            val blocked = outcomes.filter { !it.success }
            val sb = StringBuilder()
            sb.append("\n## [run ").append(ts).append("] · mode=").append(mode)
                .append(" · ").append(done).append("/").append(outcomes.size).append(" 成功 · ")
                .append(durS).append("s\n\n")
            sb.append("### 计划\n").append(planSummary.take(600)).append("\n\n")
            sb.append("### 子任务明细\n")
            for (o in outcomes) {
                sb.append("- **").append(o.taskId).append("** · ").append(o.title).append('\n')
                sb.append("  - STATUS: ").append(if (o.success) "DONE" else o.status.name)
                    .append(" · MODEL: ").append(o.model ?: "?")
                    .append(" · TURNS: ").append(o.turnsUsed)
                    .append(" · ").append(o.durationMs / 1000.0).append("s\n")
                if (o.summary.isNotBlank()) sb.append("  - SUMMARY: ").append(o.summary).append('\n')
                o.facts.take(10).forEach { f -> sb.append("  - FACT: ").append(f.take(200)).append('\n') }
                if (o.files.isNotEmpty()) sb.append("  - FILES: ").append(o.files.joinToString(", ")).append('\n')
                // [T-subagent-verify-files] Ledger carries the same disk
                // verification the pack shows, so a cold session reading only
                // HANDOFF.md knows which artifacts were machine-checked.
                if (!o.filesVerification.isNullOrBlank()) sb.append("  - FILES-CHECK: ").append(o.filesVerification).append('\n')
                if (!o.issues.isNullOrBlank()) sb.append("  - ISSUES: ").append(o.issues.take(400)).append('\n')
                if (!o.error.isNullOrBlank()) sb.append("  - ERROR: ").append(o.error.take(300)).append('\n')
                if (!o.resultFile.isNullOrBlank()) sb.append("  - RAW-RESULT: ").append(o.resultFile).append('\n')
                // [T-subagent-reasoning] The decision trail goes in the ledger
                // too, not just the pack. The ledger is what a FRESH session
                // reads to recover context, and "which approach was already
                // tried and rejected" is the first thing it needs — without
                // it the natural move is to re-attempt the dead end.
                if (o.reasoning.isNotBlank()) {
                    sb.append("  - REASONING: ").append(o.reasoning.replace('\n', ' ').take(600)).append('\n')
                }
                if (!o.reasoningFile.isNullOrBlank()) sb.append("  - REASONING-FILE: ").append(o.reasoningFile).append('\n')
            }
            sb.append("\n### NEXT-STEPS\n")
            if (outcomes.isEmpty() && blocked.isEmpty()) {
                // [T-dispatch-ledger] 子任务在派发阶段即异常终止（上游 stall/超时/被取消），
                // outcomes 为空。这时不能谎称"全部完成"，否则主模型会误以为成功。
                sb.append("- 本轮无子任务产出：子任务在派发阶段即异常终止（上游模型不可用/超时/被取消）。请检查上游模型组是否在线后重试，不要按成功处理。\n")
            } else if (blocked.isEmpty()) {
                sb.append("- 本轮全部完成。若继续相关目标，请以本轮产物与 FACT 为基础增量推进，勿重复执行。\n")
            } else {
                blocked.forEach { o ->
                    sb.append("- ").append(o.taskId).append("（").append(o.title).append("）")
                        .append(" 未完成：").append((o.issues ?: o.error ?: o.summary).take(200))
                        .append(" — 复派前先确认其已声明的文件范围未被他任务占用。\n")
                }
            }
            host.appendText(sb.toString())
            // Refresh the WHERE-WE-ARE pointer so a cold context reads the
            // latest state first without scrolling the whole ledger.
            refreshWhereWeAre(host)
            handoffLinuxPath
        } catch (_: Exception) {
            null
        }
    }

    /** Rewrites only the WHERE-WE-ARE section at the top of the ledger. */
    private fun refreshWhereWeAre(host: java.io.File) {
        try {
            val text = host.readText()
            val latest = text.substringAfterLast("\n## [run", "").ifBlank { null }
            val pointer = latest?.let {
                val head = it.lineSequence().firstOrNull()?.trim().orEmpty()
                "最新一轮: $head。完整历史见下方 [run] 小节；子任务产出文件路径在 FILES 行。\n"
            } ?: return
            val updated = text.replaceFirst(
                Regex("(?s)## WHERE-WE-ARE\\n.*?(?=\\n## \\[run)"),
                "## WHERE-WE-ARE\n$pointer\n",
            )
            if (updated != text) host.writeText(updated)
        } catch (_: Exception) {
        }
    }

    /**
     * File-write lease (WanXiang): two subtasks that declare the SAME write
     * pattern (or both declare non-empty scopes matching each other's exact
     * prefixes) must not run concurrently. Conservative, cheap proxy: union-
     * find over literal pattern equality; everything else may run in parallel.
     */
    private fun waveSchedule(specs: List<SubTaskSpec>): List<List<SubTaskSpec>> {
        val parent = IntArray(specs.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        for (i in specs.indices) {
            for (j in (i + 1) until specs.size) {
                if (SubAgentPathGuard.writeScopesOverlap(specs[i], specs[j])) union(i, j)
            }
        }
        val groups = LinkedHashMap<Int, MutableList<SubTaskSpec>>()
        specs.forEachIndexed { idx, spec -> groups.getOrPut(find(idx)) { ArrayList() }.add(spec) }
        return groups.values.toList()
    }

    // -------------------------------------------------------------- packing

    private fun buildPack(
        planSummary: String,
        mode: String,
        outcomes: List<SubTaskOutcome>,
        startedAt: Long,
    ): String = buildString {
        append("[ORCHESTRATION REPORT] subtasks=${outcomes.size} mode=$mode ")
        append("done=${outcomes.count { it.status == SubTaskStatus.DONE }} ")
        append("blocked=${outcomes.count { it.status == SubTaskStatus.BLOCKED }} ")
        append("failed=${outcomes.count { it.status in setOf(SubTaskStatus.FAILED, SubTaskStatus.TIMEOUT, SubTaskStatus.REJECTED) }}")
        if (planSummary.isNotBlank()) append("\nPLAN: ${planSummary.take(200)}")
        var total = length
        for (o in outcomes) {
            val sb = StringBuilder()
            sb.append("\n== [").append(o.taskId).append("] \"").append(o.title).append("\" → ")
                .append(o.status.name)
            o.model?.let { sb.append(" (model=").append(it).append(')') }
            sb.append(" turns=").append(o.turnsUsed)
                .append(" tok=").append(o.inputTokens).append('+').append(o.outputTokens)
            if (o.error != null) sb.append(" error=").append(o.error.take(160))
            if (o.summary.isNotBlank()) sb.append("\nSUMMARY: ").append(o.summary.take(400))
            if (o.facts.isNotEmpty()) {
                sb.append("\nFACTS:")
                o.facts.forEach { sb.append("\n- ").append(it) }
            }
            // [T-subagent-reasoning] Placed AFTER facts on purpose: the
            // section-budget cut is a blind tail `take()`, so whatever follows
            // facts is what gets sacrificed first. Facts are the verified
            // conclusions; the reasoning is the supporting narrative, so
            // narrative is the right thing to lose under pressure.
            if (o.reasoning.isNotBlank()) sb.append("\nREASONING: ").append(o.reasoning.replace('\n', ' '))
            if (o.files.isNotEmpty()) sb.append("\nFILES: ").append(o.files.joinToString(", "))
            // [T-subagent-verify-files] Rendered directly under FILES so the
            // orchestrator reads claim and verification as one unit.
            if (!o.filesVerification.isNullOrBlank()) sb.append("\nFILES-CHECK: ").append(o.filesVerification)
            if (!o.issues.isNullOrBlank()) sb.append("\nISSUES: ").append(o.issues)
            if (o.resultFile != null) sb.append("\nRAW: ").append(o.resultFile)
            if (o.reasoningFile != null) sb.append("\nREASONING-RAW: ").append(o.reasoningFile)
            var section = sb.toString()
            if (section.length > SubAgentLimits.PACK_SECTION_BUDGET) {
                section = section.take(SubAgentLimits.PACK_SECTION_BUDGET) + "\n…[section truncated]"
            }
            total += section.length
            if (total > SubAgentLimits.PACK_TOTAL_BUDGET) {
                append("\n…[剩余子任务结果超预算，完整报告见 journal 文件]")
                break
            }
            append(section)
        }
    }

    /** Append-only JSONL journal (MAGE: decisions + evidence stay traceable). */
    private fun appendJournal(
        sessionId: String,
        context: Context,
        planSummary: String,
        mode: String,
        outcomes: List<SubTaskOutcome>,
    ): String? {
        // [T-dispatch-ledger] 不能用 /var/minis-euleros/offloads/：offloads 在
        // perSessionSubdirs 里，resolveSessionHostPath 会把它导向 app 私有区
        // (context.filesDir/minis-sessions/<sid>/offloads/)，而非共享 offloads，
        // 导致 journal 写进去后排查时找不到。workspace 走全局 bind-mount，与
        // HANDOFF.md 同一套解析机制，且跨会话可追溯。
        val journalLinuxPath = "/var/minis-euleros/workspace/.collab/subagent-journal.jsonl"
        return try {
            val host = PRootKernel.resolveSessionHostPath(sessionId, journalLinuxPath, context)
                ?: return null
            host.parentFile?.mkdirs()
            val runRecord = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("session", sessionId)
                put("plan", planSummary.take(500))
                put("mode", mode)
                put("outcomes", SubTaskOutcomeJson.toArray(outcomes))
            }
            host.appendText(runRecord.toString() + "\n")
            journalLinuxPath
        } catch (_: Exception) {
            null
        }
    }
}
