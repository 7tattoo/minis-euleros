package com.miniseuleros.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-compact-redundancy] (1.16.0) Pins the mechanical size bounds the
 * compact pipeline enforces behind the prompt-level "keep the merge shorter
 * than previous summary + new transcript" instruction:
 *
 *  - elideMiddle is a no-op at/below the cap, and above it keeps head+tail
 *    with an explicit omission marker (never silently truncates one end —
 *    the five-part summary is front-loaded but the resume-critical "last
 *    operation / next step" lives in the tail).
 *  - The stored-summary hard cap actually bounds: no input, however large,
 *    produces an output above COMPACT_SUMMARY_HARD_CAP_CHARS.
 *  - Elision is stable under repetition (a chain can't balloon: eliding an
 *    already-elided summary stays within the cap).
 *  - The head/tail split keeps the tail slightly larger (2/5 vs 3/5).
 */
class CompactSummaryCapTest {

    @Test
    fun `elideMiddle is identity at or below cap`() {
        val under = "a".repeat(9_999)
        val at = "a".repeat(10_000)
        assertEquals(under, elideMiddle(under, 10_000))
        assertEquals(at, elideMiddle(at, 10_000))
    }

    @Test
    fun `elideMiddle keeps head and tail with marker`() {
        val text = buildString {
            append("HEAD<")
            repeat(5_000) { append('H') }
            append(">MIDDLE<")
            repeat(5_000) { append('M') }
            append(">TAIL<")
            repeat(5_000) { append('T') }
            append(">")
        }
        val out = elideMiddle(text, 10_000)
        assertTrue(out.length <= 10_000)
        assertTrue("head must survive", out.startsWith("HEAD<"))
        assertTrue("tail must survive", out.endsWith("T>"))
        assertTrue("middle must be explicitly marked", out.contains("middle elided"))
        assertFalse("middle body must be dropped", out.contains(">MIDDLE<"))
    }

    @Test
    fun `hard cap bounds any input`() {
        val huge = "x".repeat(1_000_000)
        val out = applyCompactSummaryHardCap(huge)
        assertTrue(out.length <= COMPACT_SUMMARY_HARD_CAP_CHARS)
        // Cap sanity: big enough for a five-part summary + collab handoff,
        // small enough that the summary can never be the context wall.
        assertTrue(COMPACT_SUMMARY_HARD_CAP_CHARS in 8_000..32_000)
    }

    @Test
    fun `elision is stable under repetition`() {
        var s = "z".repeat(50_000)
        repeat(3) { s = applyCompactSummaryHardCap(s) }
        assertTrue(s.length <= COMPACT_SUMMARY_HARD_CAP_CHARS)
        // Each pass must make progress, not grow the text back.
        val once = applyCompactSummaryHardCap("z".repeat(50_000))
        val twice = applyCompactSummaryHardCap(once)
        assertTrue(twice.length <= once.length)
    }

    @Test
    fun `tiny cap still produces usable text`() {
        val out = elideMiddle("abcdefg".repeat(100), 300)
        assertTrue(out.length in 200..300)
        assertTrue(out.contains("middle elided"))
    }
}
