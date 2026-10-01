package com.miniseuleros.app.provider

import com.miniseuleros.app.data.model.LLMError
import com.miniseuleros.app.data.model.LLMMessage
import com.miniseuleros.app.data.model.LLMModel
import com.miniseuleros.app.data.model.LLMStreamChunk
import com.miniseuleros.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-hw-gateway-upstream-error regression tests.
 *
 * 2026-10-01: a local HW-code-path relay (127.0.0.1:8321) emits an inline SSE
 * error `{"error":{"code":0,"message":"upstream error"}}` and closes the
 * stream when its own upstream hiccups. `code: 0` carries no HTTP status, so
 * the error must classify as TransientError (bounded same-model retry), not
 * ProviderError (no retry — the turn fails outright with nothing shown).
 */
class OpenAIProviderInlineErrorTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `inline SSE error with code 0 throws TransientError`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """data: {"error":{"code":0,"message":"upstream error"}}\n\ndata: [DONE]\n\n""",
            ).setHeader("Content-Type", "text/event-stream"),
        )
        val err = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024)
        }.exceptionOrNull()
        // callbackFlow cancels the collector with the LLMError as cause.
        val cause = generateSequence(err as? java.util.concurrent.CancellationException) { it.cause as? java.util.concurrent.CancellationException }
            .lastOrNull()?.cause ?: err
        assertTrue("expected TransientError, got $err / $cause", cause is LLMError.TransientError)
        Unit
    }

    @Test
    fun `inline SSE error with 503 code stays TransientError`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """data: {"error":{"code":503,"message":"upstream overloaded"}}\n\ndata: [DONE]\n\n""",
            ).setHeader("Content-Type", "text/event-stream"),
        )
        val err = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024)
        }.exceptionOrNull()
        val cause = generateSequence(err as? java.util.concurrent.CancellationException) { it.cause as? java.util.concurrent.CancellationException }
            .lastOrNull()?.cause ?: err
        assertTrue("expected TransientError, got $err / $cause", cause is LLMError.TransientError)
        Unit
    }

    @Test
    fun `inline SSE error with 401 code stays InvalidApiKey`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """data: {"error":{"code":401,"message":"bad key"}}\n\ndata: [DONE]\n\n""",
            ).setHeader("Content-Type", "text/event-stream"),
        )
        val err = runCatching {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024)
        }.exceptionOrNull()
        val cause = generateSequence(err as? java.util.concurrent.CancellationException) { it.cause as? java.util.concurrent.CancellationException }
            .lastOrNull()?.cause ?: err
        assertTrue("expected InvalidApiKey, got $err / $cause", cause is LLMError.InvalidApiKey)
        Unit
    }

    @Test
    fun `normal stream after inline error is unaffected`() = runBlocking {
        // Sanity: the classification change must not break the happy path.
        server.enqueue(
            MockResponse().setBody(
                """data: {"choices":[{"delta":{"content":"ok"}}]}
data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
data: [DONE]

""",
            ).setHeader("Content-Type", "text/event-stream"),
        )
        val chunks = provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024,
        ).toList()
        val text = chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text }
        assertTrue(text.contains("ok"))
        Unit
    }
}
