package com.miniseuleros.app.agent.orchestrator

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/** [T-android-subagent-orchestration] Scope guard is the security boundary
 *  of dispatch_subtasks — every rejection here is a guarantee the harness
 *  enforces against out-of-scope sub-agent requests. */
class SubAgentPathGuardTest {

    @Test
    fun emptyReadScopeDeniesEverything() {
        val guard = SubAgentPathGuard(emptyList(), listOf("/var/minis-euleros/workspace/**"))
        assertTrue(guard.checkRead("/var/minis-euleros/workspace/a.txt") is SubAgentPathGuard.Verdict.Deny)
        assertTrue(guard.checkRead("/etc/passwd") is SubAgentPathGuard.Verdict.Deny)
    }

    @Test
    fun emptyWriteScopeDeniesEverything() {
        val guard = SubAgentPathGuard(listOf("/var/minis-euleros/workspace/**"), emptyList())
        assertTrue(guard.checkRead("/var/minis-euleros/workspace/a.txt") is SubAgentPathGuard.Verdict.Allow)
        assertTrue(guard.checkWrite("/var/minis-euleros/workspace/a.txt") is SubAgentPathGuard.Verdict.Deny)
        assertTrue(guard.checkWrite("/tmp/x") is SubAgentPathGuard.Verdict.Deny)
    }

    @Test
    fun barePrefixCoversSubtree() {
        val guard = SubAgentPathGuard(listOf("/var/minis-euleros/workspace/proj"), emptyList())
        assertTrue(guard.checkRead("/var/minis-euleros/workspace/proj") is SubAgentPathGuard.Verdict.Allow)
        assertTrue(guard.checkRead("/var/minis-euleros/workspace/proj/src/Main.kt") is SubAgentPathGuard.Verdict.Allow)
        assertTrue(guard.checkRead("/var/minis-euleros/workspace/project") is SubAgentPathGuard.Verdict.Deny)
    }

    @Test
    fun singleSegmentWildcard() {
        val guard = SubAgentPathGuard(listOf("/w/*/build.txt"), emptyList())
        assertTrue(guard.checkRead("/w/a/build.txt") is SubAgentPathGuard.Verdict.Allow)
        assertTrue(guard.checkRead("/w/a/b/build.txt") is SubAgentPathGuard.Verdict.Deny)
    }

    @Test
    fun recursiveWildcard() {
        val guard = SubAgentPathGuard(listOf("/w/**"), emptyList())
        assertTrue(guard.checkRead("/w/a") is SubAgentPathGuard.Verdict.Allow)
        assertTrue(guard.checkRead("/w/a/b/c.txt") is SubAgentPathGuard.Verdict.Allow)
        assertTrue(guard.checkRead("/wx") is SubAgentPathGuard.Verdict.Deny)
    }

    @Test
    fun dotDotIsRejectedBeforeMatching() {
        val guard = SubAgentPathGuard(listOf("/w/**"), listOf("/w/**"))
        assertTrue(guard.checkRead("/w/../etc/passwd") is SubAgentPathGuard.Verdict.Deny)
        assertTrue(guard.checkWrite("/w/../../root/x") is SubAgentPathGuard.Verdict.Deny)
    }

    @Test
    fun denialCarriesScopeContract() {
        val guard = SubAgentPathGuard(emptyList(), emptyList())
        val v = guard.checkWrite("/tmp/x") as SubAgentPathGuard.Verdict.Deny
        assertTrue(v.reason.contains("SCOPE"))
        assertTrue(v.reason.contains("BLOCKED"))
    }

    @Test
    fun writeOverlapUnionFindsPair() {
        val a = SubTaskSpec(
            id = "a", title = "A", instructions = "x",
            writePaths = listOf("/w/one/**"),
        )
        val b = a.copy(id = "b", writePaths = listOf("/w/two/**"))
        val c = a.copy(id = "c", writePaths = listOf("/w/one/two.txt"))
        assertFalse(SubAgentPathGuard.writeScopesOverlap(a, b))
        assertTrue(SubAgentPathGuard.writeScopesOverlap(a, c))
        // Empty write scope never collides (no lease needed).
        val d = a.copy(id = "d", writePaths = emptyList())
        assertFalse(SubAgentPathGuard.writeScopesOverlap(a, d))
    }

    // ---- [T-subagent-default-shell] 2026-09-29 p2 事故回归：tools 留空时
    //      默认集必须包含 shell_execute，否则子代理一调 shell 就被白名单拒。

    @Test
    fun defaultToolsIncludeShellForFileScopes() {
        val spec = SubTaskSpec(id = "p", title = "t", instructions = "x",
            readPaths = listOf("/var/minis-euleros/workspace/proj/**"))
        assertTrue(SubTaskSpec.TOOL_SHELL in spec.effectiveTools)
        assertTrue(SubTaskSpec.TOOL_FILE_READ in spec.effectiveTools)
    }

    @Test
    fun defaultToolsForWriteScopeIncludeShellAndWrites() {
        val spec = SubTaskSpec(id = "p", title = "t", instructions = "x",
            writePaths = listOf("/var/minis-euleros/workspace/proj/**"))
        assertTrue(SubTaskSpec.TOOL_SHELL in spec.effectiveTools)
        assertTrue(SubTaskSpec.TOOL_FILE_READ in spec.effectiveTools)
        assertTrue(SubTaskSpec.TOOL_FILE_WRITE in spec.effectiveTools)
        assertTrue(SubTaskSpec.TOOL_FILE_EDIT in spec.effectiveTools)
    }

    @Test
    fun pureReasoningTaskGetsNoTools() {
        // read/write 全空 = 纯推理任务，连 shell 都不给（无法落盘，避免无谓的文件操作）。
        val spec = SubTaskSpec(id = "p", title = "t", instructions = "x")
        assertTrue(spec.effectiveTools.isEmpty())
    }

    @Test
    fun explicitToolsOverrideDefault() {
        val spec = SubTaskSpec(id = "p", title = "t", instructions = "x",
            readPaths = listOf("/var/minis-euleros/workspace/proj/**"),
            allowedTools = listOf(SubTaskSpec.TOOL_FILE_READ))
        // 显式声明会完全覆盖默认集——即使有 write 范围也不自动给 write/shell。
        assertEquals(listOf(SubTaskSpec.TOOL_FILE_READ), spec.effectiveTools)
    }
}
