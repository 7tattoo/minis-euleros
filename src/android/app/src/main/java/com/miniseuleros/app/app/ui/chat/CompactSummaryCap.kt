package com.miniseuleros.app.ui.chat

/**
 * [T-android-compact-redundancy] (1.16.0) Pure size bounds for the compact
 * pipeline. Kept as top-level functions with zero Android dependencies so the
 * behaviour is pinnable by plain unit tests (see CompactSummaryCapTest).
 *
 * Why bounds at all: the compact summary REPLACES the transcript in the
 * context window, and chained compaction feeds the previous summary back in
 * as merge input. Without a hard ceiling each compaction round can grow the
 * next round's input, and the summary itself eventually becomes the context
 * wall it was meant to prevent. The system prompt orders the model to keep
 * the merge output shorter than previous-summary + new-transcript; these
 * helpers are the mechanical source-side enforcement behind that instruction:
 *
 *  - [elideMiddle]           lossy head+tail elision used on oversized text
 *  - [applyCompactSummaryHardCap] the stored-summary ceiling (16k chars)
 *  - [COMPACT_PREVIOUS_SUMMARY_FOLD_CHARS] input-side fold for the previous
 *    summary before it is merged (10k chars — well above what a healthy
 *    chain produces per round, so honest chains never touch it)
 *
 * Elision keeps ~40% head / ~60% tail on purpose: a five-part compact summary
 * is front-loaded (core requirement + plan live in the head) but the
 * resume-critical "last operation / next step" lives in the tail — losing
 * either end is worse than losing the middle, and the tail is what a model
 * continuing the work actually reads first.
 */
internal const val COMPACT_SUMMARY_HARD_CAP_CHARS = 16_000
internal const val COMPACT_PREVIOUS_SUMMARY_FOLD_CHARS = 10_000

/** Rough worst-case length of the elision marker line [elideMiddle] inserts. */
private const val ELIDE_MARKER_BUDGET = 96

internal fun elideMiddle(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val keep = (maxChars - ELIDE_MARKER_BUDGET).coerceAtLeast(200)
    val headLen = keep * 2 / 5
    val tailLen = keep - headLen
    val head = text.take(headLen)
    val tail = text.takeLast(tailLen)
    val omitted = text.length - headLen - tailLen
    return head +
        "\n…[middle elided: $omitted of ${text.length} chars — compact summary exceeded its hard cap]…\n" +
        tail
}

internal fun applyCompactSummaryHardCap(summary: String): String =
    elideMiddle(summary, COMPACT_SUMMARY_HARD_CAP_CHARS)
