package com.miniseuleros.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-android-thinking-watchdog] App-level persisted cap on the PRE-CONTENT
 * thinking phase of a streamed reply, in seconds (0 = disabled).
 *
 * The failure being guarded: some models occasionally fall into a runaway
 * thinking loop — observed live as a 391K-char "Deep Thinking" panel that
 * never converged — and the SSE connection just sits in reasoning deltas
 * until the server's own idle/total-duration limit kills it. The client then
 * shows a broken turn with nothing to show for minutes of waiting.
 *
 * The fix lives in ChatViewModel's stream loop: the first thinking/reasoning
 * delta of an attempt arms a wall-clock stamp; once the phase outlives this
 * limit (and no final content has arrived), the collector throws
 * [com.miniseuleros.app.ui.chat.ThinkingOverrunException], the stream catch
 * rolls back the partial blocks and retries ONCE with thinking OFF — the
 * answer then arrives without the server ever reaching its cut-off.
 *
 * Global rather than per-session on purpose: the runaway is a model/gateway
 * pathology, not a conversation property. Default 300s — long enough for
 * legitimate deep thinking at HIGH/XHIGH levels, short enough to beat typical
 * relay timeouts (which usually land in the 2–5min range).
 */
object ThinkingWatchdogPrefs {
    private const val PREFS = "minis_thinking_watchdog_prefs"
    private const val KEY_LIMIT_SECONDS = "thinkingWatchdogLimitSeconds"

    const val DEFAULT_LIMIT_SECONDS = 300

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedLimitSeconds: Int = DEFAULT_LIMIT_SECONDS

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Capture the app context and warm the cache. Called from MinisApp.onCreate,
     * so [limitSeconds] is safe from call sites that have no Context — the same
     * arrangement [AutoCompactPrefs] uses.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedLimitSeconds = prefs(context).getInt(KEY_LIMIT_SECONDS, DEFAULT_LIMIT_SECONDS)
    }

    /** Context-free read. Falls back to the default before [prime] runs. */
    fun limitSeconds(): Int = cachedLimitSeconds

    fun setLimitSeconds(context: Context, seconds: Int) {
        cachedLimitSeconds = seconds.coerceAtLeast(0)
        prefs(context).edit().putInt(KEY_LIMIT_SECONDS, cachedLimitSeconds).apply()
    }
}
