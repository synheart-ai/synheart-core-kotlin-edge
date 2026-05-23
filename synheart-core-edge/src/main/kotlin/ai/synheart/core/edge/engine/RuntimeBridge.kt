package ai.synheart.core.edge.engine

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA interface to the synheart-core-runtime native library (edge pipeline).
 *
 * All methods returning [Pointer] allocate heap memory that MUST be freed
 * with [synheart_core_edge_free_string]. Returns null on error.
 */
internal interface RuntimeNative : Library {
    companion object {
        val INSTANCE: RuntimeNative? = try {
            Native.load("synheart_core_runtime", RuntimeNative::class.java)
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }

    fun synheart_core_edge_create(config_json: String?): Pointer?
    fun synheart_core_edge_destroy(handle: Pointer?)
    fun synheart_core_edge_push_rr(handle: Pointer?, ts_ms: Long, rr_ms: Double)
    fun synheart_core_edge_push_hr(handle: Pointer?, ts_ms: Long, bpm: Double)
    fun synheart_core_edge_push_accel(handle: Pointer?, ts_ms: Long, x: Double, y: Double, z: Double)
    fun synheart_core_edge_tick(handle: Pointer?, now_ms: Long): Pointer?
    fun synheart_core_edge_last_quality(handle: Pointer?): Pointer?
    fun synheart_core_edge_last_preprocessed(handle: Pointer?): Pointer?
    fun synheart_core_edge_frame_count(handle: Pointer?): Long
    fun synheart_core_edge_reset(handle: Pointer?)
    fun synheart_core_edge_free_string(ptr: Pointer?)
    fun synheart_core_edge_version(): Pointer?
}

/**
 * Configuration for the edge pipeline. Internal — only [RuntimeBridge.createIfAvailable]
 * consumes this, and it's a translation step from the host-facing
 * [ai.synheart.core.edge.models.SessionConfig] to the native FFI JSON.
 */
internal data class RuntimeConfig(
    val windowMs: Long = 60_000,
    val stepMs: Long = 5_000,
    val subjectId: String,
    val sessionId: String,
    val behaviorEnabled: Boolean = false,
    /**
     * Forwarded to the native runtime as `compute_profile.edge_mode` (edge-
     * tiering RFC §3.2). Drives the `session_role` stamped on
     * `meta.synheart.compute` of every emitted HSI envelope. Defaults to
     * [ai.synheart.core.edge.models.EdgeMode.CANONICAL].
     */
    val edgeMode: ai.synheart.core.edge.models.EdgeMode =
        ai.synheart.core.edge.models.EdgeMode.CANONICAL,
)

/**
 * Kotlin wrapper around the synheart-core-runtime edge C ABI.
 *
 * Use [createIfAvailable] to attempt loading. Returns `null` if the native
 * library is not bundled, and the caller falls back gracefully.
 */
class RuntimeBridge private constructor(private val handle: Pointer) {

    private val native: RuntimeNative = RuntimeNative.INSTANCE!!

    companion object {
        // `internal` because the only caller in this package is
        // WatchSessionEngine; RuntimeConfig is itself internal (FFI detail).
        // Marking createIfAvailable internal lets Kotlin's visibility rules
        // pass without re-exposing RuntimeConfig.
        internal fun createIfAvailable(config: RuntimeConfig): RuntimeBridge? {
            val lib = RuntimeNative.INSTANCE ?: return null
            // Nested `compute_profile` is read by core-runtime/SynheartConfig
            // (edge-tiering RFC §3.2) and shapes the `session_role` stamped
            // on every emitted HSI envelope. Pre-RFC native runtimes ignore
            // the extra key, so this is forward-compatible.
            val configJson = buildString {
                append("{")
                append("\"window_ms\":${config.windowMs},")
                append("\"step_ms\":${config.stepMs},")
                append("\"subject_id\":\"${config.subjectId}\",")
                append("\"session_id\":\"${config.sessionId}\",")
                append("\"behavior_enabled\":${config.behaviorEnabled},")
                append("\"compute_profile\":{\"edge_mode\":\"${config.edgeMode.toWire()}\"}")
                append("}")
            }
            val handle = lib.synheart_core_edge_create(configJson) ?: return null
            return RuntimeBridge(handle)
        }

        fun version(): String? {
            val lib = RuntimeNative.INSTANCE ?: return null
            val ptr = lib.synheart_core_edge_version() ?: return null
            val str = ptr.getString(0)
            lib.synheart_core_edge_free_string(ptr)
            return str
        }
    }

    fun pushRr(tsMs: Long, rrMs: Double) {
        native.synheart_core_edge_push_rr(handle, tsMs, rrMs)
    }

    fun pushHr(tsMs: Long, bpm: Double) {
        native.synheart_core_edge_push_hr(handle, tsMs, bpm)
    }

    fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {
        native.synheart_core_edge_push_accel(handle, tsMs, x, y, z)
    }

    fun tick(nowMs: Long): String? {
        val ptr = native.synheart_core_edge_tick(handle, nowMs) ?: return null
        val json = ptr.getString(0)
        native.synheart_core_edge_free_string(ptr)
        return json
    }

    // Diagnostic accessors below — exposed by the native ABI but not used by
    // any current consumer of the OSS SDK. Kept `internal` so they remain
    // available for in-package use without enlarging the public API surface.
    internal fun lastQuality(): String? {
        val ptr = native.synheart_core_edge_last_quality(handle) ?: return null
        val json = ptr.getString(0)
        native.synheart_core_edge_free_string(ptr)
        return json
    }

    internal fun lastPreprocessed(): String? {
        val ptr = native.synheart_core_edge_last_preprocessed(handle) ?: return null
        val json = ptr.getString(0)
        native.synheart_core_edge_free_string(ptr)
        return json
    }

    internal fun frameCount(): Long {
        return native.synheart_core_edge_frame_count(handle)
    }

    internal fun reset() {
        native.synheart_core_edge_reset(handle)
    }

    fun close() {
        native.synheart_core_edge_destroy(handle)
    }
}
