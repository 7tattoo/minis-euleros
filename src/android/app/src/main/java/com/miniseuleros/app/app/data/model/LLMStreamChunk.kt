package com.miniseuleros.app.data.model

import org.json.JSONObject

sealed class LLMStreamChunk {
    data object Started : LLMStreamChunk()
    data class Text(val text: String) : LLMStreamChunk()
    data class Usage(val usage: LLMUsage) : LLMStreamChunk()
    data class Finished(val stopReason: String?) : LLMStreamChunk()

    /** Thinking/reasoning streaming event */
    data class ThinkingDelta(val text: String) : LLMStreamChunk()

    /** Opaque accumulated reasoning content (DeepSeek/Kimi/QwQ `reasoning_content` field).
     *  Unlike ThinkingDelta (real-time increments), this is the full accumulated blob
     *  echoed back on subsequent turns to preserve the model's chain of thought. */
    data class ReasoningContent(val content: String) : LLMStreamChunk()

    /** Tool use streaming events */
    data class ToolUseStart(val id: String, val name: String) : LLMStreamChunk()
    data class ToolInputDelta(val id: String, val accumulated: String) : LLMStreamChunk()
    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        // [T-android-gemini3-thoughtsig / #179] Gemini 3.x returns a
        // `thoughtSignature` on each functionCall part; it MUST be replayed on
        // the historical functionCall or the next request 400s. Null for every
        // other provider (only Gemini populates it).
        val thoughtSignature: String? = null,
    ) : LLMStreamChunk()

    /**
     * [T-sse-trunc-retry] The stream TERMINATED without a finish_reason while
     * one or more tool calls had accumulated partial (unterminated) argument
     * JSON — i.e. the upstream died mid tool-args and the connection closed
     * without [DONE] or any terminal event. Emitted at most once per stream,
     * immediately BEFORE the Finished(null) tail, so the ChatViewModel agent
     * loop can detect the "truncated tool args without finish" shape and
     * transparently re-issue the same request instead of feeding the model
     * unparseable/empty args for a whole round-trip.
     *
     * Field provenance (all captured by the provider at stream end):
     *  - [truncatedToolIds]  ids of tool calls whose args StringBuilder did
     *                        not parse as complete JSON
     *  - [truncatedArgsLen]  total chars accumulated across those builders
     *  - [hasFinishReason]   always false when this chunk is emitted (guard
     *                        on the producer side; kept explicit for the
     *                        consumer's readability)
     */
    data class StreamTruncated(
        val truncatedToolIds: List<String>,
        val truncatedArgsLen: Int,
        val hasFinishReason: Boolean = false,
    ) : LLMStreamChunk()

    /**
     * [T-codex-gpt-image2-oauth-android] A model-generated media attachment
     * (e.g. an image from the Codex image_generation tool). Carried through the
     * stream so non-streaming callers (sendMessage → minis-model-use) can
     * collect it into LLMResponse.mediaAttachments. Image-output models are
     * one-shot, so this typically arrives once near the end of the stream.
     */
    data class MediaAttachment(val attachment: LLMMediaAttachment) : LLMStreamChunk()
}
