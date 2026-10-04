package com.miniseuleros.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-auto-compact-inloop] Pins the ContextPolicy behaviour the in-loop
 * guard in ChatViewModel.runAgentLoop depends on (iOS f70ac173).
 *
 * The guard itself needs a live ViewModel (Context + DB + provider), so these
 * cover the decision inputs rather than the coroutine plumbing: which tiers can
 * auto-compact, and which can only ever report exhaustion.
 *
 * This distinction is load-bearing. `check()` returns NEEDS_COMPACT first
 * whenever compactThreshold > 0, so EXHAUSTED can ONLY come from an
 * `exhaustedOnly` tier — one where compactThreshold is deliberately 0 because
 * the window is too small for compaction to pay for itself. The guard must
 * therefore STOP on EXHAUSTED rather than attempt a "rescue" compaction.
 */
class InLoopContextPolicyTest {

    @Test
    fun `large windows can auto-compact`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        // Just past the compact threshold.
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(p.compactThreshold, window),
        )
    }

    @Test
    fun `mid windows can auto-compact`() {
        val window = 100_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(p.compactThreshold + 1, window),
        )
    }

    @Test
    fun `small windows auto-compact too - 32-64K tier gained compaction`() {
        // [T-android-compact-early-threshold] (1.16.0) The 32K-64K tier used to
        // be offload-only (compactThreshold = 0, EXHAUSTED past the exhaust
        // line). Per the context-management plan it now auto-compacts with a
        // 12k headroom so small windows keep working autonomously instead of
        // stalling at the wall.
        val window = 40_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(window - 12_000, p.compactThreshold)
        assertEquals(
            ContextPolicy.CheckResult.NEEDS_COMPACT,
            p.check(p.compactThreshold, window),
        )
        assertEquals(
            ContextPolicy.CheckResult.OK,
            p.check(p.compactThreshold - 1, window),
        )
        // Between the offload line (26k) and the compact line (28k): OK, but
        // big tool results already go to disk — the cheap lossless escape
        // fires before the lossy one.
        assertEquals(ContextPolicy.CheckResult.OK, p.check(window - 13_000, window))
        assertEquals(true, p.shouldOffload(window - 13_000))
        assertEquals(false, p.shouldOffload(window - 15_000))
    }

    @Test
    fun `tiny windows never ask for compaction - only exhaustion`() {
        val window = 16_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(0, p.compactThreshold)
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            p.check(window - 100, window),
        )
    }

    @Test
    fun `compact headrooms are pinned per tier`() {
        // [T-android-compact-early-threshold] Guard against accidental
        // threshold drift: 32-64K → 12k headroom, 64-128K → 20k, ≥128K → 40k.
        assertEquals(40_000 - 12_000, ContextPolicy.forContextWindow(40_000).compactThreshold)
        assertEquals(100_000 - 20_000, ContextPolicy.forContextWindow(100_000).compactThreshold)
        assertEquals(200_000 - 40_000, ContextPolicy.forContextWindow(200_000).compactThreshold)
    }

    @Test
    fun `an idle context proceeds untouched`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        assertEquals(ContextPolicy.CheckResult.OK, p.check(1_000, window))
    }

    @Test
    fun `compaction is only requested at or above the threshold`() {
        val window = 200_000
        val p = ContextPolicy.forContextWindow(window)
        // One token below the line must NOT trigger a compaction — otherwise the
        // in-loop guard would compact on every iteration near the boundary.
        assertEquals(
            ContextPolicy.CheckResult.OK,
            p.check(p.compactThreshold - 1, window),
        )
    }
}
