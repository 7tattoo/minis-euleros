package com.miniseuleros.app.agent.orchestrator

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-subagent-orchestration] Data model for the multi-model
 * orchestration feature (dispatch_subtasks tool).
 *
 * Architecture (informed by WanXiang's sub-agent scheduling, NVIDIA SoL-Pi's
 * result-card harness mechanisms, and MAGE's budget-bounded memory packing):
 *  - The MAIN model plans and dispatches self-contained subtask briefs.
 *  - Each brief carries an explicit permission scope (read/write path globs +
 *    allowed tool set) that the harness enforces — out-of-scope requests are
 *    rejected before they reach a tool executor.
 *  - Sub-agents run in isolated mini loops; ONLY a structured facts pack
 *    (STATUS/SUMMARY/FACTS/FILES/ISSUES) re-enters the main context, never
 *    the sub-agent's transcript (token efficiency, stable prefix).
 *  - Subtasks whose declared write scopes overlap are scheduled in waves
 *    (file-write lease), never concurrently.
 *  - Results beyond the context budget are paged to disk under
 *    /var/minis-euleros/offloads/subagent/ and referenced by path.
 */

/** Hard caps — keep the main context lean no matter what the planner emits. */
object SubAgentLimits {
    const val MAX_SUBTASKS = 8
    const val MAX_INSTRUCTIONS_CHARS = 8000
    const val MAX_PATH_PATTERNS = 24
    const val DEFAULT_TIMEOUT_SEC = 300
    const val MAX_TIMEOUT_SEC = 900
    // [T-android-collab-long-tasks] (1.16.0) Raised for long-task stability:
    // bulk retrieval/build subtasks routinely needed >16 tool turns and died
    // as FAILED "max turns reached" with the work half-done (and, pre-persistence
    // contract, lost). 24 keeps a hard runaway bound while letting a child
    // finish a real scan→edit→build→verify arc; wall-clock is still bounded by
    // timeoutSec (≤900s), so the turn ceiling only buys useful work, not hangs.
    const val DEFAULT_MAX_TURNS = 10
    const val MAX_MAX_TURNS = 24
    const val DEFAULT_CONCURRENCY = 2

    /** Per-subtask section budget in the aggregated facts pack (chars). */
    const val PACK_SECTION_BUDGET = 1400

    /** Global aggregated-pack budget before paging kicks in (chars). */
    const val PACK_TOTAL_BUDGET = 12_000

    /** Final-answer budget before the raw text is paged to disk (chars). */
    const val FINAL_ANSWER_BUDGET = 8000

    /**
     * [T-subagent-reasoning] Budget for the condensed reasoning trace carried
     * in a subtask's pack section (chars).
     *
     * Sized so the decision trail survives while the facts still dominate:
     * a section is [PACK_SECTION_BUDGET] total, and reasoning may claim at most
     * this much of it. Deliberately sub-linear in the child's actual output —
     * a child that reasons for 4k chars gets ~900 here, because the marginal
     * value of the 4000th character of self-talk is near zero once the
     * approach and the rejected alternatives are already recorded.
     */
    const val REASONING_PACK_BUDGET = 900

    /**
     * [T-subagent-reasoning] Below this the trace is passed through verbatim;
     * above it the child keeps its plan from the front and its final state
     * from the back, with the middle elided. A child that hit its turn limit
     * plans early and stalls late, so both ends carry signal and the middle
     * is the most repetitive part.
     */
    const val REASONING_ELIDE_THRESHOLD = 2400
}

enum class SubTaskStatus { PENDING, RUNNING, DONE, BLOCKED, FAILED, TIMEOUT, REJECTED, SKIPPED }

/**
 * One dispatchable subtask. [readPaths]/[writePaths] are glob patterns over
 * Linux paths (`**` recursive, `*` segment wildcard; a bare directory prefix
 * covers everything under it). Empty writePaths ⇒ file modification tools
 * are hard-blocked, empty readPaths ⇒ file reads are hard-blocked.
 */
data class SubTaskSpec(
    val id: String,
    val title: String,
    val instructions: String,
    val readPaths: List<String> = emptyList(),
    val writePaths: List<String> = emptyList(),
    val allowedTools: List<String> = emptyList(),
    val groupId: String? = null,
    val timeoutSec: Int = SubAgentLimits.DEFAULT_TIMEOUT_SEC,
    val maxTurns: Int = SubAgentLimits.DEFAULT_MAX_TURNS,
) {
    val effectiveTools: List<String>
        get() = if (allowedTools.isEmpty()) {
            // [T-subagent-default-shell] 默认工具集必须含 shell_execute：子代理几乎总需要
            // gradle/git/grep/sed 等命令（与文件权限同源——PRoot 已把 shell 限制在
            // /var/minis-euleros 内）。2026-09-29 第 4 轮派发（p2）因 tools 留空、默认集
            // 只给 file_read 而不给 shell，导致每次 shell_execute 都被白名单拒绝，14 轮空转
            // 在"以为自己权限不足"。仅纯推理任务（无任何路径授权）才不放 shell。
            buildList {
                if (readPaths.isNotEmpty() || writePaths.isNotEmpty()) {
                    add(TOOL_FILE_READ)
                    if (writePaths.isNotEmpty()) { add(TOOL_FILE_WRITE); add(TOOL_FILE_EDIT) }
                    add(TOOL_SHELL)
                }
            }
        } else allowedTools

    companion object {
        const val TOOL_FILE_READ = "file_read"
        const val TOOL_FILE_WRITE = "file_write"
        const val TOOL_FILE_EDIT = "file_edit"
        const val TOOL_SHELL = "shell_execute"
        val KNOWN_TOOLS = setOf(TOOL_FILE_READ, TOOL_FILE_WRITE, TOOL_FILE_EDIT, TOOL_SHELL)

        /**
         * Parse the `subtasks` array of the dispatch_subtasks tool call.
         * Defensive: bad entries are skipped (caller receives fewer specs and
         * reports them), never thrown — a malformed dispatch must not crash
         * the main loop.
         */
        fun parseList(arr: JSONArray): Pair<List<SubTaskSpec>, List<String>> {
            val specs = ArrayList<SubTaskSpec>()
            val problems = ArrayList<String>()
            val n = minOf(arr.length(), SubAgentLimits.MAX_SUBTASKS)
            if (arr.length() > SubAgentLimits.MAX_SUBTASKS) {
                problems.add("subtasks 超过上限 ${SubAgentLimits.MAX_SUBTASKS}，多余条目已丢弃")
            }
            // [T-android-dispatch-stringified-subtasks] Some models emit list
            // fields (read_paths/write_paths/tools) — or the whole subtask
            // object — as JSON-encoded strings. Decode both instead of silently
            // dropping scopes/items.
            fun asJsonArray(v: Any?): JSONArray? = when (v) {
                is JSONArray -> v
                is String -> if (v.trim().startsWith("[")) {
                    try { JSONArray(v.trim()) } catch (_: Exception) { null }
                } else null
                else -> null
            }
            for (i in 0 until n) {
                try {
                    var o = arr.optJSONObject(i)
                    if (o == null) {
                        val raw = arr.optString(i, "").trim()
                        if (raw.startsWith("{")) o = try { JSONObject(raw) } catch (_: Exception) { null }
                    }
                    if (o == null) continue
                    val id = o.optString("id", "").trim().ifBlank { "task-${i + 1}" }
                    val title = o.optString("title", "").trim()
                    val instructions = o.optString("instructions", "").trim()
                    if (instructions.isBlank()) { problems.add("$id: instructions 为空，跳过"); continue }
                    val spec = SubTaskSpec(
                        id = id.take(48),
                        title = title.ifBlank { id }.take(80),
                        instructions = instructions.take(SubAgentLimits.MAX_INSTRUCTIONS_CHARS),
                        readPaths = parsePatterns(asJsonArray(o.opt("read_paths"))),
                        writePaths = parsePatterns(asJsonArray(o.opt("write_paths"))),
                        allowedTools = parseTools(asJsonArray(o.opt("tools"))),
                        groupId = o.optString("group_id", "").trim().ifBlank { null },
                        timeoutSec = (o.optInt("timeout_sec", SubAgentLimits.DEFAULT_TIMEOUT_SEC))
                            .coerceIn(30, SubAgentLimits.MAX_TIMEOUT_SEC),
                        maxTurns = (o.optInt("max_turns", SubAgentLimits.DEFAULT_MAX_TURNS))
                            .coerceIn(1, SubAgentLimits.MAX_MAX_TURNS),
                    )
                    specs.add(spec)
                } catch (e: Exception) {
                    problems.add("subtasks[$i] 解析失败: ${e.message}")
                }
            }
            // Duplicate ids would break result correlation — de-dupe by suffixing.
            val seen = HashMap<String, Int>()
            val deduped = specs.map { s ->
                val count = seen.merge(s.id, 1) { a, b -> a + b } ?: 1
                if (count > 1) s.copy(id = "${s.id}-${count}") else s
            }
            return deduped to problems
        }

        private fun parsePatterns(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until minOf(arr.length(), SubAgentLimits.MAX_PATH_PATTERNS)) {
                val p = arr.optString(i, "").trim()
                if (p.isNotEmpty()) out.add(p)
            }
            return out
        }

        private fun parseTools(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val t = arr.optString(i, "").trim()
                if (t in KNOWN_TOOLS && t !in out) out.add(t)
            }
            return out
        }
    }
}

/** Outcome of one dispatched subtask (facts pack + bookkeeping). */
data class SubTaskOutcome(
    val taskId: String,
    val title: String,
    val status: SubTaskStatus,
    val model: String? = null,
    val summary: String = "",
    val facts: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val issues: String? = null,
    /**
     * [T-subagent-verify-files] (1.16.0) Harness-side disk verification of the
     * sub-agent's declared FILES, rendered into the pack and the handoff
     * ledger. A sub-agent that reports DONE with declared files that do not
     * exist on disk is either hallucinating its output or wrote outside its
     * scope — either way the orchestrator must be told instead of trusting
     * the pack. Null = nothing declared (pure-reasoning task) or nothing
     * verifiable (path unresolvable); "n/n verified" = all present; any
     * MISSING entry names the fabricated path explicitly.
     */
    val filesVerification: String? = null,
    val error: String? = null,
    val turnsUsed: Int = 0,
    val durationMs: Long = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Offload path when the raw result overflowed the pack budget. */
    val resultFile: String? = null,
    /**
     * [T-subagent-reasoning] The sub-agent's own reasoning trace, condensed.
     *
     * This is the single highest-value field for a follow-up round and it used
     * to be thrown away entirely: [SubAgentRunner] pinned the child to
     * [ThinkingLevel.OFF] and its collect loop had a bare `else -> {}`, so
     * whether a child reasoned at all depended on the model volunteering
     * reasoning deltas unbidden, and when it did, they vanished. The child
     * knew what it tried, which approach it rejected and why, which files it
     * already read — and the next agent inherited none of it.
     *
     * Condensed rather than raw: a child's reasoning is routinely longer than
     * its facts, and the pack budget is per-section ([PACK_SECTION_BUDGET]),
     * so this is trimmed to a budget and the full text is offloaded to
     * [reasoningFile]. Trimmed from the FRONT as well as the back — a child
     * that hit its turn limit has its plan at the start and its
     * half-finished state at the end, and the ending is what the next agent
     * needs most.
     */
    val reasoning: String = "",
    /** Offload path for the untrimmed [reasoning] trace. */
    val reasoningFile: String? = null,
) {
    val success: Boolean get() = status == SubTaskStatus.DONE
}

/** Whole-run report for one dispatch_subtasks call. */
data class OrchestrationReport(
    val planSummary: String,
    val mode: String,
    val outcomes: List<SubTaskOutcome>,
    val packForMainModel: String = "",
    val reportFile: String? = null,
    /** [T-android-subagent-handoff] System-written ledger at
     *  /var/minis-euleros/workspace/.collab/HANDOFF.md — survives compaction. */
    val handoffFile: String? = null,
    val plannerProblems: List<String> = emptyList(),
) {
    val allDone: Boolean get() = outcomes.isNotEmpty() && outcomes.all { it.success }
    val anyDone: Boolean get() = outcomes.any { it.success }
}

object SubTaskOutcomeJson {
    fun toArray(outcomes: List<SubTaskOutcome>): JSONArray = JSONArray().apply {
        outcomes.forEach { o ->
            put(JSONObject().apply {
                put("task_id", o.taskId)
                put("title", o.title)
                put("status", o.status.name)
                put("model", o.model ?: "")
                put("summary", o.summary)
                put("facts", JSONArray(o.facts))
                put("files", JSONArray(o.files))
                put("issues", o.issues ?: "")
                put("error", o.error ?: "")
                put("turns", o.turnsUsed)
                put("duration_ms", o.durationMs)
                put("tokens_in", o.inputTokens)
                put("tokens_out", o.outputTokens)
                put("result_file", o.resultFile ?: "")
                // [T-subagent-reasoning] decision trail travels with the
                // outcome so a later round (or the ledger reader) can see how
                // the child reached its facts.
                put("reasoning", o.reasoning)
                put("reasoning_file", o.reasoningFile ?: "")
            })
        }
    }
}
