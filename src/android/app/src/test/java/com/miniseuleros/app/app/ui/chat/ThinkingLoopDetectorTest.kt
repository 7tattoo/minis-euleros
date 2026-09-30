package com.miniseuleros.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-30 调优后的边界契约（与 ChatViewModel.kt 的 ThinkingLoopDetector 严格对齐）：
 *
 *  - 检测器每次触发只会抛 [ThinkingLoopHint]（软触发），**永不**直接抛
 *    [ThinkingLoopException]。硬中断由调用方在"同一 turn 内累计达到
 *    LOOP_HINT_HARD_LIMIT(3) 次软触发"时升级。
 *  - SYMBOL 规则：ASCII/emoji 标点 15 连、CJK 标点（。！？，、；：…）30 连才触发；
 *    中文句号 20 连不算 loop（合法停顿）。
 *  - SEGMENT 规则：len>4 或英文短语 10 连触发；短 CJK 词（len<=4）需 20 连才触发，
 *    避免"但是不对但是不对"10 次被误判。
 *  - 单次触发的语义是"提示模型换措辞"，不直接中断流。
 */
class ThinkingLoopDetectorTest {

    /** 喂一段文本直到检测器抛出 ThinkingLoopHint，返回触发时的异常（未触发为 null）。 */
    private fun feedUntilHint(prefix: String, repeat: String, maxCopies: Int = 200): ThinkingLoopHint? {
        val d = ThinkingLoopDetector()
        for (i in 0 until maxCopies) {
            val chunk = if (i == 0) prefix + repeat else repeat
            try {
                d.feed(chunk)
            } catch (e: ThinkingLoopHint) {
                return e
            }
        }
        return null
    }

    @Test
    fun `pure symbol barrage of 15 triggers`() {
        val e = feedUntilHint("", "!", 20)
        assertTrue("expected ThinkingLoopHint for 15 '!'", e != null)
    }

    @Test
    fun `cjk period run of 20 is NOT a loop (legit pause)`() {
        val e = feedUntilHint("", "。", 25)
        assertNull("20 fullwidth periods must NOT be a loop", e)
    }

    @Test
    fun `cjk period run of 30 IS a loop`() {
        val e = feedUntilHint("", "。", 35)
        assertTrue("30 fullwidth periods IS a loop", e != null)
    }

    @Test
    fun `emoji run of 15 triggers`() {
        val e = feedUntilHint("", "🤔", 20)
        assertTrue("15 emoji must trigger", e != null)
    }

    @Test
    fun `han char run 10 triggers (cjk char, 10-threshold letter digit han)`() {
        val e = feedUntilHint("结论是", "好", 15)
        assertTrue(e?.sample?.first() == '好')
    }

    @Test
    fun `short cjk phrase 10 copies is NOT a loop (needs 20)`() {
        val e = feedUntilHint("", "但是不对", 12)
        assertNull("'但是不对' x10 must NOT be a loop", e)
    }

    @Test
    fun `short cjk phrase 20 copies IS a loop`() {
        val e = feedUntilHint("", "但是不对", 22)
        assertTrue("'但是不对' x20 IS a loop", e != null)
    }

    @Test
    fun `english phrase loop triggers at 10`() {
        val e = feedUntilHint("", "let me think. ", 12)
        assertTrue(e != null)
    }

    @Test
    fun `letter run a-10 triggers via segment rule`() {
        val e = feedUntilHint("x", "a", 12)
        assertTrue(e != null)
    }

    @Test
    fun `loop inside long stream with chunk splits fires`() {
        val d = ThinkingLoopDetector()
        var thrown: ThinkingLoopHint? = null
        val prose = "我先分析一下需求，然后给出方案。"
        for (i in 0 until 15) {
            try {
                d.feed(if (i == 0) prose else "")
                d.feed("重复的片段重复的片段")
            } catch (e: ThinkingLoopHint) {
                thrown = e
                break
            }
        }
        assertTrue("repeated segment across chunks should fire", thrown != null)
    }

    @Test
    fun `reset clears buffer so a prior loop does not carry over`() {
        val d = ThinkingLoopDetector()
        var fired = false
        try { d.feed("!!!!!!!!!!!!!!!") } catch (e: ThinkingLoopHint) { fired = true }
        assertTrue(fired)
        d.reset()
        // after reset, feeding the same barrage again must fire again (not be stuck)
        var fired2 = false
        try { d.feed("!!!!!!!!!!!!!!!") } catch (e: ThinkingLoopHint) { fired2 = true }
        assertTrue("reset() must allow re-detection", fired2)
    }

    @Test
    fun `hard abort is caller driven not detector driven (detector only emits hint)`() {
        // The detector must NEVER throw ThinkingLoopException directly.
        val d = ThinkingLoopDetector()
        var exceptionThrown = false
        try {
            d.feed("!!!!!!!!!!!!!!!")
        } catch (e: Throwable) {
            exceptionThrown = e is ThinkingLoopException
        }
        assertTrue("detector must NOT throw ThinkingLoopException (only Hint)", !exceptionThrown)
    }

    @Test
    fun `normal english prose with no repeat never fires`() {
        val d = ThinkingLoopDetector()
        val text = "The quick brown fox jumps over the lazy dog. " +
            "We should investigate the build failure and then propose a fix. " +
            "Let us check the logs for the root cause before changing anything."
        d.feed(text)
        // no exception expected; reach here only if nothing thrown
        assertTrue(true)
    }

    @Test
    fun `mixed cjk with natural punctuation never fires`() {
        val d = ThinkingLoopDetector()
        val text = "第一步，我们先确认需求。第二步，设计接口。第三步，实现并测试。" +
            "如果发现问题，就回到第一步重新评估。"
        d.feed(text)
        assertTrue(true)
    }
}
