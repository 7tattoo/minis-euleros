package com.miniseuleros.app.data

/**
 * Pure-logic policy that decides, given a token estimate and the model's
 * context window, whether the agent loop should offload big tool results,
 * trigger a compact/summarize pass, or surface "context exhausted" to the UI.
 *
 * Mirrors iOS `ContextPolicy` (ContextPolicy.swift): 4-tier thresholds keyed
 * off the model's context window, with no summarization algorithm embedded —
 * the actual compact + offload execution lives in the agent loop, this struct
 * only answers "what state are we in?".
 *
 * Thresholds intentionally leave generous headroom below the ceiling so one
 * more agent turn always fits and the compact pass itself runs on a smaller
 * transcript (faster) instead of slamming into the provider limit.
 *
 * [T-android-compact-early-threshold] (1.16.0): compact/auto-compact lines were
 * lowered (10k/20k headroom -> 20k/40k) and the 32-64K tier gained auto-compact
 * (12k headroom) so compaction fires earlier and autonomously per the
 * context-management plan: monitor pressure in real time, compact before the
 * wall, never let the post-limit compact get slow. iOS parity note: iOS still
 * uses the older tight thresholds (ctx-10k/ctx-20k, 32-64K exhaustedOnly) —
 * do not silently reuse these numbers there.
 */
data class ContextPolicy(
    /** Above this token count, next tool result should be written to disk. 0 disables. */
    val offloadThreshold: Int,
    /** After offload, shrink context toward this target (lower than threshold). */
    val offloadTarget: Int,
    /** Above this, trigger a compact/summarize pass. 0 disables. */
    val compactThreshold: Int,
    /** When true, the tier is too small for auto-compact; only surface .exhausted. */
    val exhaustedOnly: Boolean,
    /** Whether the "Compact now" button is offered in the UI. */
    val manualCompactAllowed: Boolean,
) {
    enum class CheckResult { OK, NEEDS_COMPACT, EXHAUSTED }

    /**
     * Classify the current turn's token pressure. Priority:
     *   1. If compact is available and tokens crossed [compactThreshold] → NEEDS_COMPACT.
     *   2. If this is a small-window tier (`exhaustedOnly`) and we're past the
     *      offload line or 90% of the window → EXHAUSTED.
     *   3. Otherwise OK.
     */
    fun check(estimatedTokens: Int, contextWindow: Int): CheckResult {
        if (compactThreshold > 0 && estimatedTokens >= compactThreshold) {
            return CheckResult.NEEDS_COMPACT
        }
        if (exhaustedOnly) {
            val exhaustLine = if (offloadThreshold > 0) offloadThreshold else (contextWindow * 9 / 10)
            if (estimatedTokens >= exhaustLine) return CheckResult.EXHAUSTED
        }
        return CheckResult.OK
    }

    /** Whether the next tool result should be offloaded to disk. */
    fun shouldOffload(estimatedTokens: Int): Boolean =
        offloadThreshold > 0 && estimatedTokens >= offloadThreshold

    companion object {
        /**
         * Produce the policy for a given context window size. Four tiers:
         *   - `<32K`   → offload/compact disabled; UI tells user to start a new chat.
         *   - `32K–64K` → offload + early auto-compact (12k headroom); small
         *     windows still compact autonomously rather than stalling.
         *   - `64K–128K` → offload + compact; 20k headroom for compact.
         *   - `≥128K`  → generous offload + compact; 40k headroom.
         * Offload lines sit below the compact lines so the cheap lossless
         * escape (disk) always gets first crack; compact is the lossy fallback.
         */
        fun forContextWindow(contextWindow: Int): ContextPolicy = when {
            contextWindow < 32_000 -> ContextPolicy(
                offloadThreshold = 0,
                offloadTarget = 0,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = false,
            )
            contextWindow < 64_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 14_000,
                offloadTarget = contextWindow - 18_000,
                compactThreshold = contextWindow - 12_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
            contextWindow < 128_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 30_000,
                offloadTarget = contextWindow - 40_000,
                compactThreshold = contextWindow - 20_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
            else -> ContextPolicy(
                offloadThreshold = contextWindow - 60_000,
                offloadTarget = contextWindow - 80_000,
                compactThreshold = contextWindow - 40_000,
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
        }
    }
}
