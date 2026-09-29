package com.miniseuleros.app.agent.orchestrator

import android.content.Context
import com.miniseuleros.app.data.model.AgentToolDefinition
import com.miniseuleros.app.data.model.AgentToolParam
import com.miniseuleros.app.data.model.LLMMessage
import com.miniseuleros.app.data.model.LLMStreamChunk
import com.miniseuleros.app.provider.LLMProvider
import com.miniseuleros.app.data.model.AgentContentPart
import com.miniseuleros.app.data.model.ThinkingLevel
import com.miniseuleros.app.provider.ModelsDevApi
import com.miniseuleros.app.sandbox.ExecutionCoordinator
import com.miniseuleros.app.sandbox.PRootKernel
import com.miniseuleros.app.tools.FileEditTool
import com.miniseuleros.app.tools.FileReadTool
import com.miniseuleros.app.tools.FileWriteTool
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-android-subagent-orchestration] Isolated mini agent loop for one
 * dispatched subtask. Mirrors the main runAgentLoop message protocol
 * (assistant ToolUse parts ⇄ user ToolResult parts) but is completely
 * detached from chat UI: progress flows out through [onEvent], and only the
 * final facts pack survives. Tool access is fenced by [SubAgentPathGuard]
 * and the spec's tool allowlist — out-of-scope requests are rejected with a
 * harness error before any executor runs (deny-by-default).
 */
class SubAgentRunner(
    private val provider: LLMProvider,
    private val modelLabel: String,
    private val sessionId: String,
    private val context: Context,
    private val onEvent: (String) -> Unit,
) {
    /** One guarded tool execution, result + timing included.
     *  [denied] = harness 拒绝（超出授权范围，子代理本就不该调），区别于
     *  [isError]=true 的「命令执行了但失败」。两者 UI 文案必须不同，否则子代理
     *  会把"grep 没匹配(exit 1)"误判成"被权限拒绝"而空转（见 2026-09-29 p2 事故）。 */
    private data class ToolRun(
        val resultJson: JSONObject,
        val isError: Boolean,
        val denied: Boolean,
        val fileName: String,
    )

    suspend fun run(spec: SubTaskSpec): SubTaskOutcome {
        val startedAt = System.currentTimeMillis()
        val guard = SubAgentPathGuard(spec.readPaths, spec.writePaths)
        val toolNames = spec.effectiveTools.filter { it in SubTaskSpec.KNOWN_TOOLS }
        var turnsUsed = 0
        var inputTokens = 0
        var outputTokens = 0
        // [T-subagent-reasoning] Declared OUTSIDE the try so the catch paths
        // can page it too — a timeout is exactly the case where "what was it
        // doing" is the only diagnostic available.
        val reasoningTrace = StringBuilder()

        onEvent("▶ [${spec.id}] 启动 · $modelLabel · 工具=${toolNames.ifEmpty { listOf("无") }.joinToString("/")}")

        try {
            return withTimeout(spec.timeoutSec * 1000L) {
                val messages = ArrayList<LLMMessage>()
                messages.add(LLMMessage(role = LLMMessage.Role.USER, content = buildTaskBrief(spec)))

                var finalText: String? = null
                // [T-subagent-reasoning] Reasoning accumulated across every
                // turn of THIS subtask, not just the last one. A child's most
                // useful thinking usually happens before it starts calling
                // tools — the plan, the file it decides to read, the approach
                // it rejects — so keeping only the final turn would keep the
                // least informative slice. Never sent back to the model: this
                // is handed to the ORCHESTRATOR, not replayed into the child.
                while (turnsUsed < spec.maxTurns) {
                    turnsUsed++
                    var turnText = StringBuilder()
                    val toolCalls = ArrayList<Triple<String, String, JSONObject>>()
                    val usage = arrayOfNulls<com.miniseuleros.app.data.model.LLMUsage>(1)

                    val flow = provider.streamMessage(
                        messages = messages,
                        systemPrompt = buildSystemPrompt(spec, guard, toolNames),
                        maxTokens = SUB_MAX_TOKENS,
                        tools = toolDefinitions(toolNames),
                        // [T-subagent-reasoning] Was OFF, which meant the child's
                        // decision trail was unrecoverable: the collect loop's
                        // `else -> {}` discarded reasoning deltas, and providers
                        // that gate them behind this flag emitted none. HIGH
                        // rather than OFF because a subtask is exactly the case
                        // where the reasoning IS the product — it is doing
                        // multi-step analysis whose approach choices the next
                        // agent must inherit. clampThinkingLevel inside the
                        // provider caps this at the model's own ceiling, so
                        // non-reasoning models silently fall back to OFF and
                        // lose nothing.
                        thinkingLevel = ThinkingLevel.HIGH,
                    )
                    flow.collect { chunk ->
                        when (chunk) {
                            is LLMStreamChunk.Text -> turnText.append(chunk.text)
                            is LLMStreamChunk.ToolCallComplete ->
                                toolCalls.add(Triple(chunk.id, chunk.name, chunk.args))
                            is LLMStreamChunk.Usage -> usage[0] = chunk.usage
                            is LLMStreamChunk.ThinkingDelta -> {
                                // Skip the opening filler ("Let me think…")
                                // which every reasoning model emits first;
                                // it is pure overhead in the condensed trace.
                                if (chunk.text.isNotBlank() && !reasoningTrace.contains("Let me think")) {
                                    reasoningTrace.append(chunk.text)
                                }
                            }
                            is LLMStreamChunk.ReasoningContent -> {
                                // DeepSeek/Kimi/QwQ echo the WHOLE accumulated
                                // blob on every turn rather than increments, so
                                // it must not be appended per-delta (that would
                                // duplicate the trace quadratically). Keep only
                                // the newest echo, and only if the incremental
                                // path produced nothing — some routes emit
                                // only this one.
                                if (reasoningTrace.isEmpty() && chunk.content.isNotBlank()) {
                                    reasoningTrace.append(chunk.content)
                                }
                            }
                            else -> {}
                        }
                    }
                    usage[0]?.let {
                        inputTokens += it.inputTokens
                        outputTokens += it.outputTokens
                    }

                    if (toolCalls.isEmpty()) {
                        finalText = turnText.toString().trim()
                        break
                    }

                    // Record the assistant turn, execute each call under the
                    // guard, append the tool-result turn — the provider round trip.
                    val assistantParts = toolCalls.map { (id, name, args) ->
                        AgentContentPart.ToolUse(id, name, args)
                    }
                    messages.add(LLMMessage(
                        role = LLMMessage.Role.ASSISTANT,
                        content = turnText.toString(),
                        contentParts = assistantParts,
                    ))

                    val resultParts = ArrayList<AgentContentPart>()
                    for ((id, name, args) in toolCalls) {
                        val toolRun = executeGuarded(name, args, guard, toolNames, spec)
                        resultParts.add(AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = toolRun.resultJson.toString(2),
                            isError = toolRun.isError,
                        ))
                        if (toolRun.denied) {
                            onEvent("⛔ [${spec.id}] ${toolRun.fileName} 被拒绝（超出授权范围）")
                        } else if (toolRun.isError) {
                            onEvent("✗ [${spec.id}] ${toolRun.fileName} 执行失败")
                        } else if (name != SubTaskSpec.TOOL_SHELL || VERBOSE_SHELL_EVENTS) {
                            onEvent("· [${spec.id}] ${toolRun.fileName} 完成")
                        }
                    }
                    messages.add(LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = resultParts,
                    ))
                }

                val durationMs = System.currentTimeMillis() - startedAt
                val rawFinal = finalText
                    ?: "STATUS: FAILED\nSUMMARY: 达到最大轮数上限 ${spec.maxTurns} 仍未给出最终结论。"
                finish(
                    spec, rawFinal, turnsUsed, durationMs, inputTokens, outputTokens,
                    maxedOut = finalText == null,
                    // [T-subagent-reasoning] Hand the trail to the orchestrator.
                    // Not appended to `messages` — that would replay the child's
                    // own thinking back at it, which is both wasteful and
                    // actively harmful (it anchors the retry to the approach
                    // that just failed).
                    reasoningRaw = reasoningTrace.toString(),
                )
            }
        } catch (e: TimeoutCancellationException) {
            val durationMs = System.currentTimeMillis() - startedAt
            // [T-subagent-reasoning] Paged ONCE — pageReasoning writes a file,
            // so calling it twice for the two fields would race two writes to
            // different timestamps and leak the first.
            val trace = pageReasoning(spec, reasoningTrace.toString())
            return SubTaskOutcome(
                taskId = spec.id, title = spec.title, status = SubTaskStatus.TIMEOUT,
                model = modelLabel,
                summary = "子任务超时（${spec.timeoutSec}s）",
                error = "timeout after ${spec.timeoutSec}s",
                turnsUsed = turnsUsed, durationMs = durationMs,
                inputTokens = inputTokens, outputTokens = outputTokens,
                // Highest-value case for the trail: a timeout is the one
                // failure the next agent cannot diagnose from the outcome
                // alone. "What was it doing when the clock ran out" lives
                // only here, and it is exactly what tells a re-dispatch
                // whether to shrink the scope or raise the limit.
                reasoning = trace.displayText,
                reasoningFile = trace.overflowPath,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startedAt
            return SubTaskOutcome(
                taskId = spec.id, title = spec.title, status = SubTaskStatus.FAILED,
                model = modelLabel,
                summary = "子任务执行异常",
                error = e.message ?: e.javaClass.simpleName,
                turnsUsed = turnsUsed, durationMs = durationMs,
                inputTokens = inputTokens, outputTokens = outputTokens,
            )
        }
    }

    // ---------------------------------------------------------------- tools

    private suspend fun executeGuarded(
        name: String,
        args: JSONObject,
        guard: SubAgentPathGuard,
        allowedTools: List<String>,
        spec: SubTaskSpec,
    ): ToolRun {
        val label = args.optString("tool_title", name)
        if (name !in allowedTools) {
            // denied：子代理调了它没被授权的工具——这是范围违反，不是执行失败。
            return ToolRun(denyJson("$name 不在本子任务的工具授权列表内（授权：${allowedTools.joinToString("/")}）。超过权限范围的请求一律不允许。"), true, true, name)
        }
        if (name == SubTaskSpec.TOOL_SHELL) {
            val command = args.optString("command", "")
            if (command.isBlank()) return ToolRun(denyJson("command 为空"), true, true, name)
            val timeout = args.optInt("timeout", 300).coerceIn(1, 600) * 1000L
            return try {
                val r = ExecutionCoordinator.execute(sessionId = sessionId, command = command, timeout = timeout)
                val out = buildString {
                    append(r.output.take(SHELL_OUTPUT_BUDGET))
                    if (r.output.length > SHELL_OUTPUT_BUDGET) append("\n…[输出截断，完整 ${r.output.length} 字符]")
                    append("\n[exit code: ${r.exitCode}]")
                }
                // 命令执行了（exit code 可能非 0）——这是「执行失败」，不是「被拒绝」。
                ToolRun(okJson(out, JSONObject().put("exit_code", r.exitCode)), r.exitCode != 0, false, name)
            } catch (e: Exception) {
                // 执行路径本身抛异常（PRoot 未启动等）——也算执行失败，非范围拒绝。
                ToolRun(denyJson("shell 执行失败: ${e.message}"), true, false, name)
            }
        }
        // File tools — guard the path BEFORE the executor sees the call.
        val path = args.optString("path", "")
        val verdict = when (name) {
            SubTaskSpec.TOOL_FILE_READ -> guard.checkRead(path)
            SubTaskSpec.TOOL_FILE_WRITE, SubTaskSpec.TOOL_FILE_EDIT -> guard.checkWrite(path)
            else -> SubAgentPathGuard.Verdict.Deny("未知工具 $name")
        }
        if (verdict is SubAgentPathGuard.Verdict.Deny) {
            // denied：路径越权——范围违反。
            return ToolRun(denyJson(verdict.reason), true, true, "$name:$path")
        }
        val argsJson = args.toString()
        val result = when (name) {
            SubTaskSpec.TOOL_FILE_READ -> FileReadTool.execute(argsJson, sessionId, context)
            SubTaskSpec.TOOL_FILE_WRITE -> FileWriteTool.execute(argsJson, sessionId, context)
            SubTaskSpec.TOOL_FILE_EDIT -> FileEditTool.execute(argsJson, sessionId, context)
            else -> com.miniseuleros.app.tools.ToolExecutionResult("Error: unknown tool $name", false)
        }
        return ToolRun(
            okJson(result.output, JSONObject().put("success", result.success)),
            !result.success,
            false,
            "$name:$path",
        )
    }

    private fun okJson(content: String, extra: JSONObject): JSONObject = JSONObject().apply {
        put("ok", true)
        put("content", content)
        extra.keys().forEachRemaining { put(it, extra.get(it)) }
    }

    private fun denyJson(reason: String, denied: Boolean = true): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", reason)
        if (denied) {
            put("hint", "该请求超出本子任务授权范围，已被 harness 拒绝。请改用范围内的路径/工具，或回报 BLOCKED 说明所需范围。")
        } else {
            // 执行失败（命令跑了但失败）：不要伪装成权限拒绝，否则子代理会误判范围而空转。
            put("hint", "命令已执行但失败（见 exit code）。先查看输出定位问题，必要时换命令重试；这不是权限问题。")
        }
    }

    private fun toolDefinitions(names: List<String>): List<AgentToolDefinition> = buildList {
        if (SubTaskSpec.TOOL_FILE_READ in names) add(FileReadTool.definition())
        if (SubTaskSpec.TOOL_FILE_WRITE in names) add(FileWriteTool.definition())
        if (SubTaskSpec.TOOL_FILE_EDIT in names) add(FileEditTool.definition())
        if (SubTaskSpec.TOOL_SHELL in names) add(shellDefinition())
    }

    private fun shellDefinition() = AgentToolDefinition(
        name = SubTaskSpec.TOOL_SHELL,
        description = "Execute a shell command in the app's Linux sandbox (persistent session, " +
            "stdout/stderr merged). ONLY for work inside this subtask's authorized scope.",
        parameters = mapOf(
            "command" to AgentToolParam("string", "The shell command to execute. Keep under 1000 chars; write scripts to authorized paths with file_write first for anything longer."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default 300, max 600)."),
        ),
        required = listOf("command"),
        propertyOrdering = listOf("command", "timeout"),
    )

    // ------------------------------------------------------------- prompting

    private fun buildTaskBrief(spec: SubTaskSpec): String = buildString {
        append("## 任务（").append(spec.title).append("）\n\n")
        append(spec.instructions)
        append("\n\n严格按系统提示中的 OUTPUT CONTRACT 输出最终结果。")
    }

    private fun buildSystemPrompt(spec: SubTaskSpec, guard: SubAgentPathGuard, tools: List<String>): String = buildString {
        append("You are a sub-agent executor inside Minis on Android, dispatched by an orchestrator ")
        append("model for exactly ONE task. You cannot see the orchestrator's conversation; the task ")
        append("brief in the user message is self-contained. Do only the assigned task — no extras, ")
        append("no follow-up questions.\n\n")
        append("## PERMISSION SCOPE (hard-enforced by the harness; out-of-scope requests are REJECTED)\n")
        append("- Readable file paths: ").append(guard.readScopeDescription).append('\n')
        append("- Writable file paths: ").append(guard.writeScopeDescription).append('\n')
        append("- Allowed tools: ").append(if (tools.isEmpty()) "无（纯推理任务）" else tools.joinToString(", ")).append('\n')
        append("Stay strictly inside this scope. If the task cannot be completed within it, stop and ")
        append("report STATUS: BLOCKED with the exact extra scope you would need.\n\n")
        append("## OUTPUT CONTRACT — your FINAL message (a turn with NO tool calls) MUST be:\n")
        append("STATUS: DONE | BLOCKED | FAILED\n")
        append("SUMMARY: <1-3 sentences>\n")
        append("FACTS:\n- <evidence-backed fact, max 10 lines, each ≤200 chars>\n")
        append("FILES: <comma-separated linux paths you created/modified, or none>\n")
        append("ISSUES: <blockers / unknowns / what you could not verify, or none>\n")
        append("Write SUMMARY/FACTS/ISSUES content in the same language as the task brief. ")
        append("The orchestrator only sees this facts pack, never your transcript — pack every ")
        append("decision and result the orchestrator needs into it.\n\n")
        append("## PERSISTENCE CONTRACT [T-android-subagent-handoff] — 工作必须留下来\n")
        append("Your facts pack is the ONLY thing that survives this run. Anything that exists ")
        append("only in this conversation is LOST to every later turn and session. Therefore:\n")
        append("- Any non-trivial result (computed values, drafted text/code, findings, decisions, ")
        append("data, scripts) MUST be written to a file inside your authorized write scope (use ")
        append("file_write / file_edit), then referenced in your FILES line. Results that are too ")
        append("large for the pack are likewise paginated to a file by the harness.\n")
        append("- One file per work product beats one giant dump: name them so the next worker can ")
        append("tell what is inside without opening them (e.g. <topic>.<kind>.md).\n")
        append("- FACTS lines should cite these paths as evidence instead of restating long content.\n")
        append("- Pure-reasoning tasks with no file scope: keep the pack self-contained instead, ")
        append("and say in SUMMARY that no artifact was produced.\n\n")
        append("## HOW YOUR THINKING IS USED [T-subagent-reasoning]\n")
        append("Your reasoning is captured and handed to the orchestrator, condensed to about ")
        append("${SubAgentLimits.REASONING_PACK_BUDGET} characters. This is the only channel through which ")
        append("the next agent learns HOW you worked, not just WHAT you concluded. So think out loud ")
        append("about the things a later reader cannot reconstruct from your facts pack:\n")
        append("- which approaches you considered and rejected, and the reason for rejecting them\n")
        append("- which files/context you already read, so they are not re-read\n")
        append("- what you verified vs assumed, and what would falsify each assumption\n")
        append("Do not narrate routine mechanics (which tool you are about to call, or that you are ")
        append("reading a file) — that is the part that gets elided. Lead with the decision, not the ")
        append("process. Note: your trace is NOT replayed to you on a retry, so a fresh sub-agent ")
        append("starting the same task will not know what you did — leave that trail here.\n")
    }

    // ----------------------------------------------------- facts pack finish

    private fun finish(
        spec: SubTaskSpec,
        rawFinal: String,
        turns: Int,
        durationMs: Long,
        inTok: Int,
        outTok: Int,
        maxedOut: Boolean,
        // [T-subagent-reasoning] The child's accumulated reasoning trace.
        // Paged and condensed here — same overflow treatment the final answer
        // gets, so a chatty child can never blow the pack budget through its
        // thinking alone.
        reasoningRaw: String = "",
    ): SubTaskOutcome {
        val paged = pageIfNeeded(spec, rawFinal)
        val parsed = parseFactsPack(paged.displayText, spec)
        val status = when {
            maxedOut -> SubTaskStatus.FAILED
            parsed.status.equals("BLOCKED", true) -> SubTaskStatus.BLOCKED
            parsed.status.equals("FAILED", true) -> SubTaskStatus.FAILED
            else -> SubTaskStatus.DONE
        }
        val reasoningPaged = pageReasoning(spec, reasoningRaw)
        onEvent(
            when (status) {
                SubTaskStatus.DONE -> "✓ [${spec.id}] 完成 · ${parsed.facts.size} facts · ${parsed.files.size} files · ${durationMs / 1000.0}s"
                SubTaskStatus.BLOCKED -> "⏸ [${spec.id}] BLOCKED · ${parsed.issues ?: ""}"
                else -> "✗ [${spec.id}] FAILED · ${(parsed.issues ?: parsed.summary).take(80)}"
            }
        )
        return SubTaskOutcome(
            taskId = spec.id,
            title = spec.title,
            status = status,
            model = modelLabel,
            summary = parsed.summary,
            facts = parsed.facts,
            files = parsed.files,
            issues = parsed.issues,
            error = if (maxedOut) "max turns (${spec.maxTurns}) reached without a final answer" else null,
            turnsUsed = turns,
            durationMs = durationMs,
            inputTokens = inTok,
            outputTokens = outTok,
            resultFile = paged.overflowPath,
            reasoning = reasoningPaged.displayText,
            reasoningFile = reasoningPaged.overflowPath,
        )
    }

    private data class Paged(val displayText: String, val overflowPath: String?)

    /**
     * [T-subagent-reasoning] Condense a child's reasoning trace to
     * [SubAgentLimits.REASONING_PACK_BUDGET] and offload the raw text.
     *
     * Trims from BOTH ends, never just the tail. A child that ran out of
     * turns has its approach at the start and its unfinished state at the
     * end, and the next agent needs both: the former to know what was already
     * attempted, the latter to know where to resume. The middle is where a
     * reasoning model repeats itself, so that is what gets elided.
     */
    private fun pageReasoning(spec: SubTaskSpec, reasoningRaw: String): Paged {
        val text = reasoningRaw.trim()
        if (text.isEmpty()) return Paged("", null)
        if (text.length <= SubAgentLimits.REASONING_PACK_BUDGET) return Paged(text, null)
        val linuxPath = "/var/minis-euleros/offloads/subagent/${spec.id}_reasoning_${System.currentTimeMillis()}.md"
        val host = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
        val condensed = condenseReasoning(text)
        if (host == null) return Paged(condensed, null)
        return try {
            host.parentFile?.mkdirs()
            host.writeText(text)
            Paged(condensed, linuxPath)
        } catch (_: Exception) {
            Paged(condensed, null)
        }
    }

    /** Results beyond the budget go to disk; the pack keeps a readable head
     *  plus the reference path (WanXiang-style overflow paging). */
    private fun pageIfNeeded(spec: SubTaskSpec, text: String): Paged {
        if (text.length <= SubAgentLimits.FINAL_ANSWER_BUDGET) return Paged(text, null)
        val linuxPath = "/var/minis-euleros/offloads/subagent/${spec.id}_${System.currentTimeMillis()}.txt"
        val host = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
        if (host == null) return Paged(text.take(SubAgentLimits.FINAL_ANSWER_BUDGET), null)
        try {
            host.parentFile?.mkdirs()
            host.writeText(text)
            val head = text.take(SubAgentLimits.FINAL_ANSWER_BUDGET)
            return Paged(
                head + "\n\n[全文 ${text.length} 字符已落盘: $linuxPath — 主模型可用 file_read 按需查阅]",
                linuxPath,
            )
        } catch (_: Exception) {
            return Paged(text.take(SubAgentLimits.FINAL_ANSWER_BUDGET), null)
        }
    }

    private data class ParsedPack(
        val status: String,
        val summary: String,
        val facts: List<String>,
        val files: List<String>,
        val issues: String?,
    )

    private fun parseFactsPack(text: String, spec: SubTaskSpec): ParsedPack {
        var status = ""
        var summary = ""
        val facts = ArrayList<String>()
        var files = ArrayList<String>()
        var issues: String? = null
        var inFacts = false
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("STATUS:", true) -> { status = line.substringAfter(':').trim(); inFacts = false }
                line.startsWith("SUMMARY:", true) -> { summary = line.substringAfter(':').trim(); inFacts = false }
                line.startsWith("FACTS:", true) -> inFacts = true
                line.startsWith("FILES:", true) -> {
                    inFacts = false
                    files = line.substringAfter(':').trim()
                        .split(',', '，').map { it.trim() }
                        .filter { it.isNotEmpty() && !it.equals("none", true) }
                        .toCollection(ArrayList())
                }
                line.startsWith("ISSUES:", true) -> {
                    inFacts = false
                    issues = line.substringAfter(':').trim().ifBlank { null }
                }
                inFacts && line.startsWith("- ") && facts.size < 10 ->
                    facts.add(line.substring(2).trim().take(200))
                else -> {}
            }
        }
        if (status.isBlank() && summary.isBlank() && facts.isEmpty()) {
            // Model ignored the contract — degrade gracefully: whole text is the summary.
            summary = text.take(600)
            if (text.length > 600) facts.add("[…] 全文 ${text.length} 字符${text.lines().size} 行，见 result_file")
        }
        return ParsedPack(
            status = status.ifBlank { if (issues.isNullOrBlank()) "DONE" else "BLOCKED" },
            summary = summary,
            facts = facts,
            files = files,
            issues = issues?.take(300),
        )
    }

    companion object {
        private const val SUB_MAX_TOKENS = 8192
        private const val SHELL_OUTPUT_BUDGET = 12_000
        private const val VERBOSE_SHELL_EVENTS = false

        /** Cheap display name for progress lines (models.dev enriched). */
        fun describeModel(modelId: String): String =
            try {
                ModelsDevApi.enrichModel(
                    com.miniseuleros.app.data.model.LLMModel(id = modelId, displayName = modelId, provider = "")
                ).displayName
            } catch (_: Exception) { null }
                .takeUnless { it.isNullOrBlank() } ?: modelId
    }
}

/**
 * [T-subagent-reasoning] Condense a reasoning trace to
 * [SubAgentLimits.REASONING_PACK_BUDGET] chars.
 *
 * Trims from BOTH ends, never just the tail. A child that ran out of turns
 * has its approach at the start and its unfinished state at the end, and the
 * next agent needs both: the former to know what was already attempted, the
 * latter to know where to resume. The middle is where a reasoning model
 * repeats itself, so that is what gets elided.
 *
 * Extracted from [SubAgentRunner.pageReasoning] as a pure top-level function
 * so the trimming policy is unit-testable without a provider or a kernel.
 */
internal fun condenseReasoning(text: String): String {
    val t = text.trim()
    if (t.length <= SubAgentLimits.REASONING_PACK_BUDGET) return t
    // Head gets 60% / tail 40%: the approach matters slightly more than the
    // tail, which is often a mid-sentence fragment.
    val head = SubAgentLimits.REASONING_PACK_BUDGET * 3 / 5
    val tail = SubAgentLimits.REASONING_PACK_BUDGET - head
    return if (t.length <= SubAgentLimits.REASONING_ELIDE_THRESHOLD) {
        t.take(SubAgentLimits.REASONING_PACK_BUDGET)
    } else {
        "${t.take(head)}\n\n[... ${t.length - head - tail} middle chars elided ...]\n\n${t.takeLast(tail)}"
    }
}
