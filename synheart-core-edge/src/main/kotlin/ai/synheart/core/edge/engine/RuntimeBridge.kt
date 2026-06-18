// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.engine

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.json.JSONObject

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
    /**
     * Returns the last error code recorded on the handle (0 = OK, nonzero =
     * error, e.g. ERR_CONCURRENT_CALL when a call lost the internal try_lock).
     * Returns -1 if the handle pointer is null.
     */
    fun synheart_core_edge_last_error(handle: Pointer?): Int
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
     * Forwarded to the native runtime as `compute_profile.edge_mode`. Drives
     * the `session_role` stamped on `meta.synheart.compute` of every emitted
     * HSI envelope. Defaults to
     * [ai.synheart.core.edge.models.EdgeMode.CANONICAL].
     */
    val edgeMode: ai.synheart.core.edge.models.EdgeMode =
        ai.synheart.core.edge.models.EdgeMode.CANONICAL,
)

/**
 * Abstraction over the per-window edge runtime the [WatchSessionEngine]
 * drives. [RuntimeBridge] is the production implementation (native FFI);
 * tests supply fakes. Internal so the public API surface stays the native
 * [RuntimeBridge] wrapper.
 *
 * Exposes the three accessors the engine needs to surface real biosignal
 * numbers per window: [tick] (HSI 1.3 artifact), [lastPreprocessed] (raw
 * derived features — HRV/motion/quality JSON), and [lastQuality] (quality
 * summary JSON).
 */
internal interface RuntimeHandle {
    fun pushRr(tsMs: Long, rrMs: Double)
    fun pushHr(tsMs: Long, bpm: Double)
    fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double)
    fun tick(nowMs: Long): String?
    fun lastPreprocessed(): String?
    fun lastQuality(): String?
    /**
     * Last error code recorded on the handle (0 = OK, nonzero = error such as
     * a concurrent-call rejection). Checked by the engine after [tick] to make
     * dropped frames observable. Default 0 for fakes that don't model errors.
     */
    fun lastError(): Int = 0
    /** Number of frames the runtime has emitted (diagnostic). */
    fun frameCount(): Long = 0
    fun close()
}

/**
 * Kotlin wrapper around the synheart-core-runtime edge C ABI.
 *
 * Use [createIfAvailable] to attempt loading. Returns `null` if the native
 * library is not bundled, and the caller falls back gracefully.
 *
 * `internal`: this is FFI plumbing with an internal constructor. The only
 * consumer is [WatchSessionEngine] (in-package), so it never needs to be on
 * the public API surface.
 */
internal class RuntimeBridge internal constructor(
    handle: Pointer,
    // Injectable for unit tests (e.g. double-close); production uses the
    // loaded native instance via the private convenience constructor below.
    private val native: RuntimeNative,
) : RuntimeHandle {

    private constructor(handle: Pointer) : this(handle, RuntimeNative.INSTANCE!!)

    // Nullable + cleared in close() so a double-close is a guarded no-op.
    // Rust's synheart_core_edge_destroy is NOT idempotent against an
    // already-freed non-null pointer, so dropping the reference here is what
    // prevents a double-free. All native calls bail out when handle == null.
    private var handle: Pointer? = handle

    companion object {
        // `internal` because the only caller in this package is
        // WatchSessionEngine; RuntimeConfig is itself internal (FFI detail).
        // Marking createIfAvailable internal lets Kotlin's visibility rules
        // pass without re-exposing RuntimeConfig.
        internal fun createIfAvailable(config: RuntimeConfig): RuntimeBridge? {
            val lib = RuntimeNative.INSTANCE ?: return null
            return createIfAvailable(config, lib)
        }

        /**
         * Testable overload taking an explicit [RuntimeNative] so unit tests can
         * verify the FFI config JSON without the loaded native library. The
         * production path delegates here with [RuntimeNative.INSTANCE].
         */
        internal fun createIfAvailable(config: RuntimeConfig, lib: RuntimeNative): RuntimeBridge? {
            val handle = lib.synheart_core_edge_create(buildConfigJson(config)) ?: return null
            return RuntimeBridge(handle, lib)
        }

        /**
         * Build the create-config JSON sent over FFI to the native runtime.
         *
         * Built with org.json.JSONObject (NOT string interpolation): the
         * phone-supplied `session_id` / `subject_id` are untrusted, and a value
         * containing a quote or backslash would otherwise break out of the JSON
         * and corrupt — or inject into — the FFI config. JSONObject escapes them
         * safely.
         *
         * The nested `compute_profile` is read by the native runtime and shapes
         * the `session_role` stamped on every emitted HSI envelope. Older native
         * runtimes ignore the extra key, so this is forward-compatible.
         */
        internal fun buildConfigJson(config: RuntimeConfig): String =
            JSONObject().apply {
                put("window_ms", config.windowMs)
                put("step_ms", config.stepMs)
                put("subject_id", config.subjectId)
                put("session_id", config.sessionId)
                put("behavior_enabled", config.behaviorEnabled)
                put("compute_profile", JSONObject().apply {
                    put("edge_mode", config.edgeMode.toWire())
                })
            }.toString()

        fun version(): String? {
            val lib = RuntimeNative.INSTANCE ?: return null
            val ptr = lib.synheart_core_edge_version() ?: return null
            val str = ptr.getString(0)
            lib.synheart_core_edge_free_string(ptr)
            return str
        }
    }

    override fun pushRr(tsMs: Long, rrMs: Double) {
        val h = handle ?: return
        native.synheart_core_edge_push_rr(h, tsMs, rrMs)
    }

    override fun pushHr(tsMs: Long, bpm: Double) {
        val h = handle ?: return
        native.synheart_core_edge_push_hr(h, tsMs, bpm)
    }

    override fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {
        val h = handle ?: return
        native.synheart_core_edge_push_accel(h, tsMs, x, y, z)
    }

    override fun tick(nowMs: Long): String? {
        val h = handle ?: return null
        val ptr = native.synheart_core_edge_tick(h, nowMs) ?: return null
        val json = ptr.getString(0)
        native.synheart_core_edge_free_string(ptr)
        return json
    }

    // Diagnostic accessors below — exposed by the native ABI but not used by
    // any current consumer of the OSS SDK. Kept `internal` so they remain
    // available for in-package use without enlarging the public API surface.
    override fun lastQuality(): String? {
        val h = handle ?: return null
        val ptr = native.synheart_core_edge_last_quality(h) ?: return null
        val json = ptr.getString(0)
        native.synheart_core_edge_free_string(ptr)
        return json
    }

    override fun lastPreprocessed(): String? {
        val h = handle ?: return null
        val ptr = native.synheart_core_edge_last_preprocessed(h) ?: return null
        val json = ptr.getString(0)
        native.synheart_core_edge_free_string(ptr)
        return json
    }

    override fun lastError(): Int {
        val h = handle ?: return -1
        return native.synheart_core_edge_last_error(h)
    }

    override fun frameCount(): Long {
        val h = handle ?: return 0
        return native.synheart_core_edge_frame_count(h)
    }

    internal fun reset() {
        val h = handle ?: return
        native.synheart_core_edge_reset(h)
    }

    /**
     * Destroy the native handle. Idempotent: the [handle] reference is cleared
     * before the destroy call returns, so a second [close] is a guarded no-op.
     * This is required because the Rust `synheart_core_edge_destroy` is NOT
     * idempotent against an already-freed pointer — a double-close would be a
     * double-free.
     */
    override fun close() {
        val h = handle ?: return
        handle = null
        native.synheart_core_edge_destroy(h)
    }
}
