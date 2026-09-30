package com.miniseuleros.app.agent.orchestrator

/**
 * [T-android-subagent-orchestration] Hard permission gate for sub-agent file
 * access. The orchestrator declares read/write path globs per subtask; any
 * file tool call outside the scope is rejected HERE — before a tool executor
 * runs — with an explanatory error the sub-model can adapt to. Deny-by-
 * default: an empty write scope forbids every write, an empty read scope
 * forbids every read.
 *
 * Pattern semantics (mirrors common glob intuition, WanXiang-lease-style):
 *  - `/a/b`          → exactly `/a/b` AND everything under it (subtree)
 *  - `/a/b` + recursive wildcard → everything under `/a/b` (not `/a/b` itself)
 *  - `/a/X/c`        → `X` = single-segment wildcard (`*` matches one segment)
 *  - `**`            → any number of segments (recursive)
 *  - `?`             → one non-separator char
 * `..` segments in the requested path are always rejected (traversal guard);
 * normalization happens before matching.
 */
class SubAgentPathGuard(
    readPatterns: List<String>,
    writePatterns: List<String>,
) {
    private val readMatchers = readPatterns.mapNotNull { compile(it) }
    private val writeMatchers = writePatterns.mapNotNull { compile(it) }
    val readScopeDescription: String = if (readPatterns.isEmpty()) NONE_DESC else readPatterns.joinToString(", ")
    val writeScopeDescription: String = if (writePatterns.isEmpty()) NONE_DESC else writePatterns.joinToString(", ")

    sealed class Verdict {
        data object Allow : Verdict()
        data class Deny(val reason: String) : Verdict()
    }

    fun checkRead(path: String): Verdict = check(path, readMatchers, "读取", readScopeDescription, "READ")
    fun checkWrite(path: String): Verdict = check(path, writeMatchers, "写入/修改", writeScopeDescription, "WRITE")

    private fun check(
        rawPath: String,
        matchers: List<Regex>,
        actionZh: String,
        scopeDesc: String,
        scopeTag: String,
    ): Verdict {
        val normalized = normalize(rawPath)
            ?: return Verdict.Deny("[SCOPE $scopeTag DENIED] 路径非法（包含 .. 或为空）：$rawPath。本子任务的请求一律不允许超出授权范围。")
        if (matchers.isEmpty()) {
            return Verdict.Deny(
                "[SCOPE $scopeTag DENIED] 本子任务未授权任何文件${actionZh}权限（授权范围：$scopeDesc）。" +
                    "路径 $normalized 被拒绝。请在不触碰文件的前提下完成任务，或回报 BLOCKED 说明所需范围。"
            )
        }
        if (matchers.any { it.matches(normalized) }) return Verdict.Allow
        return Verdict.Deny(
            "[SCOPE $scopeTag DENIED] 路径 $normalized 不在本子任务的${actionZh}授权范围内（范围：$scopeDesc）。" +
                "超过权限范围的请求一律不允许执行。请改用范围内的路径，或回报 BLOCKED。"
        )
    }

    private fun normalize(raw: String): String? {
        var p = raw.trim()
        if (p.isEmpty()) return null
        if (!p.startsWith("/")) p = "/$p"
        val out = ArrayList<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> return null
                else -> out.add(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    private fun compile(pattern: String): Regex? {
        val normalized = normalize(pattern) ?: return null
        val hasWildcard = normalized.contains('*') || normalized.contains('?')
        if (!hasWildcard) {
            // Bare prefix: exact file OR any path under it.
            return Regex(Regex.escape(normalized) + "(/.*)?$")
        }
        val sb = StringBuilder("^")
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            when {
                c == '*' && i + 1 < normalized.length && normalized[i + 1] == '*' -> {
                    // `**` — swallow the following '/' too (`/a/**` matches `/a/x/y`).
                    var j = i + 2
                    if (j < normalized.length && normalized[j] == '/') {
                        sb.append("(?:.*/)?")
                        j++
                    } else {
                        sb.append(".*")
                    }
                    i = j
                }
                c == '*' -> { sb.append("[^/]*"); i++ }
                c == '?' -> { sb.append("[^/]"); i++ }
                else -> { sb.append(Regex.escape(c.toString())); i++ }
            }
        }
        sb.append('$')
        return try { Regex(sb.toString()) } catch (_: Exception) { null }
    }

    companion object {
        private const val NONE_DESC = "无（禁止一切文件访问）"

        /**
         * Conservative write-lease overlap: two specs collide when any write
         * pattern pair could ever match the same path. Uses static prefixes
         * (the literal part before the first wildcard) — a broad pattern
         * like `/w/one/` + recursive-wildcard collides with a concrete file
         * `/w/one/two.txt` inside its lease. False positives merely
         * serialize (safe); false negatives would let two sub-agents stomp
         * the same file (unsafe), so the check errs wide.
         */
        fun writeScopesOverlap(a: SubTaskSpec, b: SubTaskSpec): Boolean {
            if (a.writePaths.isEmpty() || b.writePaths.isEmpty()) return false
            val pa = a.writePaths.map { staticPrefix(it) }
            val pb = b.writePaths.map { staticPrefix(it) }
            return pa.any { x -> pb.any { y -> x.startsWith(y) || y.startsWith(x) } }
        }

        /** Literal head of a pattern before its first wildcard char. */
        private fun staticPrefix(pattern: String): String {
            val idx = pattern.indexOfFirst { it == '*' || it == '?' }
            return if (idx < 0) pattern else pattern.substring(0, idx)
        }
    }
}
