package com.miniseuleros.app.provider

import com.miniseuleros.app.provider.openai.isCompleteJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sse-trunc-retry] Detection probe for "the stream died mid tool-args".
 *
 * The contract this test locks in is narrow and adversarial: a payload must
 * be reported as truncated if and only if it would NOT have been silently
 * flattened to `{}` by parseToolArgs. A false POSITIVE is expensive (we
 * re-issue a request that was actually fine, wasting a full round-trip and
 * risking a retry loop); a false NEGATIVE is the 2026-09-28 failure mode we
 * are fixing (a truncated dispatch reaches preflight as `{}` and the model
 * gets told it sent empty arguments).
 *
 * Samples marked "observed" are verbatim prefixes lifted from the real
 * loomy/qwen-3.8-max stream that died on 2026-09-28 (the last accumulated
 * args snapshot was 284 chars / 555 bytes ending in `", "subtasks": `), so
 * the regression stays anchored to the actual failure rather than a shape I
 * invented.
 */
class ToolArgsTruncationDetectionTest {

    // ---------------------------------------------------------------- observed

    @Test
    fun `observed qwen snapshot cut right before the subtasks array is truncated`() {
        // The exact 284-char snapshot from the 16:22 stream. Valid so far, but
        // `"subtasks"` has no value yet — the array never streamed.
        val observed = """{"plan":"6 subtasks, each authoring one screen under screens/","subtasks": """
        assertFalse(isCompleteJsonObject(observed))
    }

    @Test
    fun `observed qwen snapshot cut mid-plan-string is truncated`() {
        // The 188-char variant: died inside the plan string literal itself.
        val observed = """{"plan":"(a) shell+navigation skeleton, (b) shared token sheet, (c) six screen"""
        assertFalse(isCompleteJsonObject(observed))
    }

    // ----------------------------------------------------------------- complete

    @Test
    fun `empty object is complete`() {
        // No bytes arrived at all. parseToolArgs yields {} either way, but the
        // producer guards this case upstream (raw.isNotEmpty()), so the probe
        // itself must not claim "truncated" — it only answers "does it parse".
        assertTrue(isCompleteJsonObject("{}"))
    }

    @Test
    fun `fully streamed dispatch args are complete`() {
        val full = """{"plan":"build 6 screens","subtasks":[{"id":"t1","title":"shell"}]}"""
        assertTrue(isCompleteJsonObject(full))
    }

    @Test
    fun `complete args with non-ascii content parse`() {
        // 555 bytes vs 284 chars in the observed case: the gap is CJK. Length
        // must be judged in characters, never bytes, or a healthy CJK-heavy
        // payload gets misread as truncated.
        val cjk = """{"plan":"车载投屏：并行产出 6 屏设计稿","subtasks":[{"id":"t1"}]}"""
        assertTrue(isCompleteJsonObject(cjk))
    }

    @Test
    fun `fenced json is tolerated as complete`() {
        // A model that wraps args in a ```json fence would otherwise be
        // reported as truncated on EVERY turn — a retry loop generator.
        assertTrue(isCompleteJsonObject("```json\n{\"plan\":\"x\",\"subtasks\":[]}\n```"))
    }

    @Test
    fun `surrounding whitespace does not change the verdict`() {
        assertTrue(isCompleteJsonObject("  \n {\"plan\":\"x\"}  \n "))
        assertFalse(isCompleteJsonObject("  \n {\"plan\":\"x\", \"subtasks\": "))
    }

    // ---------------------------------------------------------------- truncated

    @Test
    fun `array cut mid element is truncated`() {
        assertFalse(isCompleteJsonObject("""{"subtasks":[{"id":"t1","title":"shell"},""""))
    }

    @Test
    fun `unterminated string is truncated`() {
        assertFalse(isCompleteJsonObject("""{"path":"/var/minis-euleros/workspace/music-u"""))
    }

    @Test
    fun `non-object payloads are not silently passed off as complete`() {
        // A bare array is a shape parseToolArgs would also reject, so calling
        // it "complete" would let a malformed call through the retry path.
        assertFalse(isCompleteJsonObject("[1,2,3"))
    }
}
