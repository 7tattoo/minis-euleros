package com.miniseuleros.app.ui.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [T-android-subagent-collab-chip] User-intent gate for multi-model
 * orchestration (dispatch_subtasks), surfaced as a Coomi-style pill chip in
 * the composer's button row (the input-bar "Collab" toggle).
 *
 * Two-gate model:
 *  - STRUCTURAL: a sub-agent group can resolve
 *    (ProviderRepository.subAgentsAvailable — a default binding or any group).
 *  - INTENT: this persisted flag (chip ON / OFF).
 *
 * dispatch_subtasks reaches the tool schema only when BOTH gates are true,
 * and buildSystemPrompt drops the orchestration workflow section + tool
 * bullet the same way — the model must never see guidance for a tool it
 * can't call (same coherence rule as the memory toggle's prompt gating).
 *
 * Default OFF: orchestration is opt-in via the chip, mirroring Coomi's
 * unselected mode chips — a bound group alone no longer auto-exposes the
 * tool, and the ~600-token workflow section stays out of every prompt until
 * the user asks for it.
 */
object SubAgentCollabPrefs {

    private const val PREFS = "subagent_collab_prefs"
    private const val KEY_ENABLED = "collab_enabled"

    private val _enabled = MutableStateFlow(false)

    /** Chip state; read by the composer pill and both build-time gates. */
    val enabled: StateFlow<Boolean> = _enabled

    @Volatile
    private var loaded = false

    /** Load the persisted flag once per process (SharedPreferences). */
    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _enabled.value = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
            loaded = true
        }
    }

    /** Flip the chip; persists immediately (apply, no UI-thread blocker). */
    fun set(context: Context, value: Boolean) {
        _enabled.value = value
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
    }
}
