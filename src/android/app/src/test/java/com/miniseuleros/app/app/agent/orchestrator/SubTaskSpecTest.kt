package com.miniseuleros.app.agent.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubTaskSpecTest {

    @Test
    fun parseMinimal() {
        val arr = org.json.JSONArray("""[{"id":"t1","title":"T","instructions":"do it"}]""")
        val (specs, problems) = SubTaskSpec.parseList(arr)
        assertTrue(problems.isEmpty())
        assertEquals(1, specs.size)
        assertEquals("t1", specs[0].id)
        // Empty scopes → no file tools derived (deny-by-default).
        assertEquals(emptyList<String>(), specs[0].effectiveTools)
        assertEquals(300, specs[0].timeoutSec)
        assertEquals(8, specs[0].maxTurns)
    }

    @Test
    fun parseFullAndClamps() {
        val arr = org.json.JSONArray("""
            [{"id":"t1","title":"T","instructions":"i",
              "read_paths":["/a/**"],"write_paths":["/a/out/**"],
              "tools":["file_read","shell_execute","not_a_tool"],
              "group_id":"g","timeout_sec":99999,"max_turns":99}]
        """.trimIndent())
        val (specs, problems) = SubTaskSpec.parseList(arr)
        assertTrue(problems.isEmpty())
        val s = specs[0]
        assertEquals(listOf("/a/**"), s.readPaths)
        assertEquals(listOf("file_read", "shell_execute"), s.allowedTools)
        assertEquals("g", s.groupId)
        assertEquals(SubAgentLimits.MAX_TIMEOUT_SEC, s.timeoutSec)
        assertEquals(SubAgentLimits.MAX_MAX_TURNS, s.maxTurns)
    }

    @Test
    fun missingFieldsAreReported() {
        val arr = org.json.JSONArray("""[{"id":"","title":"T","instructions":""},{"title":"no-instructions"}]""")
        val (specs, problems) = SubTaskSpec.parseList(arr)
        assertEquals(0, specs.size)
        assertEquals(2, problems.size)
    }

    @Test
    fun duplicateIdsAreDeduped() {
        val arr = org.json.JSONArray("""
            [{"id":"x","title":"A","instructions":"i"},
             {"id":"x","title":"B","instructions":"i"}]
        """.trimIndent())
        val (specs, problems) = SubTaskSpec.parseList(arr)
        assertTrue(problems.isEmpty())
        assertEquals(2, specs.size)
        assertEquals(setOf("x", "x-2"), specs.map { it.id }.toSet())
    }

    @Test
    fun capOnSubtaskCount() {
        val json = (1..12).joinToString(",") { """{"id":"t$it","title":"T","instructions":"i"}""" }
        val arr = org.json.JSONArray("[$json]")
        val (specs, problems) = SubTaskSpec.parseList(arr)
        assertEquals(SubAgentLimits.MAX_SUBTASKS, specs.size)
        assertTrue(problems.isNotEmpty())
    }
}
