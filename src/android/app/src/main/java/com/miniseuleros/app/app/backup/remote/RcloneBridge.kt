package com.miniseuleros.app.backup.remote

import com.miniseuleros.app.logging.AppLogger
import org.json.JSONObject

/**
 * Kotlin access to the bundled rclone library, mirroring
 * `src/ios/Agent/Backup/Remote/RcloneBridge.swift`.
 *
 * Everything rclone can do goes through ONE entry point — a JSON-RPC call:
 *
 *     RcloneBridge.rpc("operations/list", mapOf("fs" to "remote:", "remote" to "dir"))
 *
 * That is rclone's own librclone API, not an interface invented here. The
 * binding comes from `deps/rclone-mobile/gomobile`, built by
 * `deps/build_rclone_android.sh`; backends linked in are decided by
 * `deps/rclone-mobile/backends/backends.go` — the same list the iOS build uses,
 * so a remote configured on one platform behaves identically on the other.
 *
 * NOTE (MinisEulerOS): the rclone .aar is NOT bundled in this build (it is a
 * build artifact produced by `deps/build_rclone_android.sh` from
 * `deps/rclone-mobile`, which is not part of the public source tree). Every
 * RPC therefore fails with a clear "not bundled" error instead of crashing.
 * The public API surface is preserved so the rest of the backup UI compiles
 * and degrades gracefully. To restore remote-backup support, run the rclone
 * build script and drop the resulting .aar into app/libs.
 *
 * Every call blocks on network I/O. Callers must be off the main thread.
 */
object RcloneBridge {

    private const val TAG = "Rclone"
    private const val NOT_BUNDLED = "rclone is not bundled in this build (MinisEulerOS)"

    /** rclone's RPC returns an HTTP-style status; anything but 200 is a failure. */
    class RPCException(val status: Int, payload: String) :
        Exception(extractMessage(status, payload)) {
        companion object {
            // rclone puts a human-readable reason in `error` when it can.
            private fun extractMessage(status: Int, payload: String): String =
                runCatching { JSONObject(payload).optString("error").takeIf { it.isNotEmpty() } }
                    .getOrNull() ?: "rclone RPC failed (status $status)"
        }
    }

    @Volatile
    private var initialised = false

    /** Idempotent. Must run before any RPC; safe to call from anywhere. */
    @Synchronized
    fun initializeIfNeeded() {
        if (initialised) return
        initialised = true
        AppLogger.warning(TAG, "[Rclone] $NOT_BUNDLED — remote backup unavailable")
    }

    /** How long to wait for a server to accept a connection. */
    const val CONNECT_TIMEOUT_SECONDS = 20L

    /**
     * How long a single transfer may STALL before it is treated as dead.
     *
     * This is an inactivity timeout, not a cap on total transfer time: rclone
     * resets it whenever bytes move, so a multi-hundred-megabyte upload over a
     * slow home link is unaffected as long as it is making progress. That is
     * what lets the same value protect a list call and a large upload without
     * a separate budget for each.
     *
     * Applies PER ATTEMPT, so the worst-case wait is roughly this times the
     * retry count — which is why the retry counts come down with it.
     */
    const val IO_TIMEOUT_SECONDS = 45L

    /**
     * Low-level and high-level retry budgets.
     *
     * Retries MULTIPLY the visible freeze because the timeout above is applied
     * per attempt. rclone's defaults (10 low-level) would turn a 45s stall into
     * minutes of apparent hang against a server that accepts a connection and
     * then says nothing.
     */
    const val LOW_LEVEL_RETRIES = 1L
    const val RETRIES = 1L

    /** Nanoseconds, as rclone's int64 Duration options expect. */
    fun durationNanos(seconds: Long): Long = seconds * 1_000_000_000L

    /**
     * The `options/set` payload for the global transfer budget.
     *
     * Split out from [applyGlobalOptions] so the unit tests can assert the
     * exact wire values — the nanosecond conversion is the easiest thing here
     * to get wrong by three orders of magnitude, and a `Timeout` of 45 (ns
     * interpreted as 45 billionths of a second) would fail every transfer
     * instantly rather than obviously.
     */
    fun globalTimeoutOptions(): Map<String, Any> = mapOf(
        "Timeout" to durationNanos(IO_TIMEOUT_SECONDS),
        "ConnectTimeout" to durationNanos(CONNECT_TIMEOUT_SECONDS),
        "LowLevelRetries" to LOW_LEVEL_RETRIES,
        "Retries" to RETRIES,
    )

    /**
     * rclone's defaults are tuned for a desktop batch job, not a phone.
     *
     * `Gomobile.rcloneRPC` is a BLOCKING call into the Go runtime — nothing on
     * the Kotlin side can interrupt it, so whatever rclone decides to wait for,
     * the calling thread waits too. Out of the box that is a 300s IO timeout
     * with 10 low-level retries, i.e. a server that accepts a connection and
     * then stops responding can hold the caller for the better part of an hour.
     * On a user's device that reads as "backup frozen" rather than "backup
     * failed, try again".
     *
     * Mirrors iOS `RcloneBridge.applyGlobalOptions` (a28f8721b).
     */
    fun applyGlobalOptions() {
        AppLogger.warning(TAG, "[Rclone] $NOT_BUNDLED — skipping global options")
    }

    /**
     * Whether to accept self-signed / untrusted TLS certificates.
     *
     * Applied GLOBALLY rather than per-remote on purpose: of the backends this
     * app offers, only FTP exposes a `no_check_certificate` option — WebDAV and
     * S3 have none, and their verification is governed by rclone's global
     * `InsecureSkipVerify`. A per-remote toggle would therefore be a lie for
     * exactly the backends most likely to need it (a NAS serving HTTPS WebDAV
     * with its own certificate).
     *
     * Mirrors iOS `RcloneBridge.setInsecureTLS` (a28f8721b).
     */
    fun setInsecureTLS(allow: Boolean) {
        AppLogger.warning(TAG, "[Rclone] $NOT_BUNDLED — skipping TLS option")
    }

    /**
     * `rpc` without the initialise guard — for calls made DURING
     * initialisation. Always fails in this build (rclone not bundled).
     */
    private fun rpcUnchecked(method: String, params: Map<String, Any?>): JSONObject {
        throw RPCException(501, "{\"error\":\"$NOT_BUNDLED\"}")
    }

    /**
     * One RPC call. [params] is encoded to JSON; the reply is decoded from it.
     *
     * Always fails in this build (rclone not bundled). Kept so callers compile
     * unchanged and surface a clear error.
     */
    fun rpc(method: String, params: Map<String, Any?> = emptyMap()): JSONObject {
        initializeIfNeeded()
        throw RPCException(501, "{\"error\":\"$NOT_BUNDLED\"}")
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(value.entries.associate { (k, v) -> k.toString() to toJsonValue(v) })
        is List<*> -> org.json.JSONArray(value.map { toJsonValue(it) })
        else -> value
    }

    /**
     * Smoke test: prove the Go runtime starts and answers on-device.
     *
     * `core/version` needs no config, no network and no credentials, so a
     * successful reply isolates exactly one thing — that the linked-in Go
     * runtime is alive inside this app, next to PRoot.
     */
    fun smokeTest(): String {
        AppLogger.error(TAG, "[Rclone] smoke FAILED: $NOT_BUNDLED")
        return "FAILED: $NOT_BUNDLED"
    }

    /** Backends actually compiled in — confirms the trim did what it claims. */
    fun supportedBackends(): List<String> = emptyList()
}
