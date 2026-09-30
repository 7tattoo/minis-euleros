package com.miniseuleros.app.agent.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-reasoning] Guards the trimming policy for a sub-agent's
 * reasoning trace.
 *
 * The policy is load-bearing in both directions: a trace that is trimmed from
 * the tail only loses the child's final state (where a re-dispatch must
 * resume), while one trimmed from both ends keeps exactly the two ends that
 * carry signal. These tests pin that, plus the passthrough boundary.
 */
class SubAgentReasoningCondenseTest {

    @Test
    fun shortTracePassesThroughVerbatim() {
        val t = "considered approach A, rejected it"
        assertEquals(t, condenseReasoning(t))
    }

    @Test
    fun traceExactlyAtBudgetIsUntouched() {
        val t = "x".repeat(SubAgentLimits.REASONING_PACK_BUDGET)
        assertEquals(t, condenseReasoning(t))
    }

    @Test
    fun traceOneOverBudgetIsClampedToBudget() {
        val t = "x".repeat(SubAgentLimits.REASONING_PACK_BUDGET + 1)
        assertEquals(SubAgentLimits.REASONING_PACK_BUDGET, condenseReasoning(t).length)
    }

    @Test
    fun moderateOverageTruncatesHeadOnly() {
        // Between budget and elide threshold: a plain prefix cut, no marker.
        val t = "x".repeat(SubAgentLimits.REASONING_PACK_BUDGET + 100)
        val out = condenseReasoning(t)
        assertEquals(SubAgentLimits.REASONING_PACK_BUDGET, out.length)
        assertTrue("should not contain an elision marker", !out.contains("middle chars elided"))
    }

    @Test
    fun largeTraceKeepsHeadAndTailWithElisionMarker() {
        val t = "x".repeat(SubAgentLimits.REASONING_ELIDE_THRESHOLD + 1000)
        val out = condenseReasoning(t)
        assertTrue("marker expected", out.contains("middle chars elided"))
        // Head is 60% of budget, tail 40% — both ends survive.
        assertTrue("head part", out.startsWith("x".repeat(100)))
        assertTrue("tail part", out.endsWith("x".repeat(100)))
    }

    @Test
    fun elidedCountMatchesActualGap() {
        val t = "x".repeat(SubAgentLimits.REASONING_ELIDE_THRESHOLD + 1000)
        val out = condenseReasoning(t)
        val head = SubAgentLimits.REASONING_PACK_BUDGET * 3 / 5
        val tail = SubAgentLimits.REASONING_PACK_BUDGET - head
        val expected = t.length - head - tail
        assertTrue("marker should report $expected", out.contains("$expected middle chars elided"))
    }

    @Test
    fun surroundingWhitespaceIsStripped() {
        val t = "   \n\n real content \n  "
        assertEquals("real content", condenseReasoning(t))
    }

    @Test
    fun planSurvivesFromTheHead() {
        // The real failure mode this guards: a child that ran out of turns had
        // its approach recorded FIRST, so a head-only-when-short or a
        // tail-biased cut would erase it.
        val plan = "PLAN: audit the parser, then patch the tokenizer"
        val t = plan + "y".repeat(SubAgentLimits.REASONING_ELIDE_THRESHOLD)
        val out = condenseReasoning(t)
        assertTrue("plan must survive condensation", out.contains(plan))
    }

    @Test
    fun resumeStateSurvivesFromTheTail() {
        val state = "STOPPED AT: editing file X, 3 of 7 call sites done"
        val t = "z".repeat(SubAgentLimits.REASONING_ELIDE_THRESHOLD) + state
        val out = condenseReasoning(t)
        assertTrue("resume state must survive condensation", out.contains(state))
    }
}
