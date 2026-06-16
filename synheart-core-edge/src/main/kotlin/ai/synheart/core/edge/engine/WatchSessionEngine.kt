// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.engine

import ai.synheart.core.edge.models.*
import ai.synheart.core.edge.sensor.MotionSensor
import ai.synheart.session.BiosignalProvider
import ai.synheart.session.BiosignalSample
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Compute mode for an edge session. */
enum class EngineMode {
    /** Watch is a dumb sensor: raw [BiosignalSample]s are surfaced via
     *  [WatchSessionEngine.bioSamples] for the host app to relay to the
     *  paired phone. The on-device runtime is not loaded. */
    STREAM,
    /** `synheart-core-runtime` runs locally; raw samples are pushed to FFI
     *  and HSI artifacts emitted by the runtime are persisted + relayed. */
    COMPUTE_LOCAL,
}

/**
 * On-watch session engine with a formal state machine.
 *
 * Motion is captured locally since Health Connect doesn't stream raw IMU.
 * The runtime owns signal math when `mode == EngineMode.COMPUTE_LOCAL`.
 */
class WatchSessionEngine(
    private val provider: BiosignalProvider,
    private val motionSensor: MotionSensor? = null,
    val outbox: EdgeOutbox? = null,
    val sessionManager: EdgeSessionManager? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    /**
     * Context-bearing convenience constructor (parity with Swift's defaulted
     * `init`, which default-constructs `EdgeOutbox()` / `EdgeSessionManager()`).
     *
     * On the JVM an [EdgeSessionManager] / [EdgeOutbox] both need a [Context]
     * (SharedPreferences + `filesDir`), which the primary constructor can't
     * supply — so a caller that passed neither previously fell back to a random
     * per-instance subject id, diverging from Swift's stable `sub_<deviceId>`.
     * This constructor closes that gap: when the host has a Context but doesn't
     * want to wire the outbox/manager itself, it gets the SAME stable
     * per-device subject id Swift uses, persisted across process restarts.
     */
    constructor(
        context: android.content.Context,
        provider: BiosignalProvider,
        motionSensor: MotionSensor? = null,
        outbox: EdgeOutbox? = EdgeOutbox(context),
        sessionManager: EdgeSessionManager? = EdgeSessionManager(context),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    ) : this(provider, motionSensor, outbox, sessionManager, scope)

    /**
     * Factory that resolves the per-window edge runtime for a given config.
     * Production uses the native [RuntimeBridge] loader (the default); tests
     * swap in a fake [RuntimeHandle] so the frame/summary metric contract
     * (`preprocessed` / `quality`) is exercised without the native library.
     * Returns `null` when no runtime is available → STREAM mode.
     *
     * `internal` so it never widens the public API (its type references the
     * internal [RuntimeConfig] FFI translation type).
     */
    internal var runtimeFactory: (RuntimeConfig) -> RuntimeHandle? = { cfg ->
        RuntimeBridge.createIfAvailable(cfg)
    }

    companion object {
        /**
         * HSI envelope versions this SDK understands (the `hsi_version` field
         * carried inside the tick artifact JSON, NOT the relay envelope's
         * `schema_version`). A runtime/engine bump that emits an out-of-set
         * version is logged at WARN so the drift is observable. This does NOT
         * change any wire shape — purely an observability guard.
         */
        val SUPPORTED_HSI_VERSIONS: Set<String> = setOf("1.1", "1.2", "1.3")
        private const val TAG = "WatchSessionEngine"
    }

    /**
     * Last-resort fallback subject id for the Context-less primary constructor
     * when no [sessionManager] is injected (effectively unit tests — production
     * uses the Context-bearing constructor, which default-constructs an
     * [EdgeSessionManager] so the stable per-device [EdgeSessionManager.subjectId]
     * `sub_<deviceId>` is always used, matching Swift). A fresh random id per
     * engine instance — never a shared constant — so the test path can never
     * cross-contaminate runtime personalization across users / installs.
     */
    private val fallbackSubjectId: String =
        "sub_" + java.util.UUID.randomUUID().toString().take(8)

    /**
     * Number of runtime frames dropped because the native handle reported a
     * nonzero `last_error` after a tick (e.g. ERR_CONCURRENT_CALL when a call
     * lost the internal try_lock). Surfaced for diagnostics; serializing all
     * handle calls through [runtimeDispatcher] should keep this at zero, so a
     * climbing value signals a contention bug.
     */
    val droppedFrames: Long
        get() = _droppedFrames.get()
    private val _droppedFrames = AtomicLong(0)

    /**
     * Single-thread serial executor that ALL native handle calls for a session
     * are confined to (push_hr/push_rr/push_accel/tick/last_* /frame_count/
     * reset). The Rust side uses try_lock and silently drops on contention, so
     * confining every call to one thread guarantees they never race the handle.
     * Created on session start, shut down on session end.
     */
    private var runtimeExecutor: ExecutorService? = null
    private var runtimeDispatcher: ExecutorCoroutineDispatcher? = null

    /**
     * When true (production default), all native handle calls are confined to a
     * dedicated single-thread serial executor. Tests disable this so the
     * runtime calls run inline on the `runTest` virtual-time scheduler, keeping
     * frame timing deterministic without spawning a real OS thread.
     */
    internal var serializeRuntimeCalls: Boolean = true

    data class UiState(
        val watchState: WatchSessionState = WatchSessionState.IDLE,
        val currentHr: Double = 0.0,
        val elapsedSec: Int = 0,
        val lastMetrics: Map<String, Any>? = null,
        val sessionKind: SessionKind = SessionKind.FOCUS,
        val remainingSec: Int = 0,
        val pendingArtifacts: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _hrEvents = MutableSharedFlow<Pair<Double, Long>>(extraBufferCapacity = 64)
    val hrEvents: SharedFlow<Pair<Double, Long>> = _hrEvents.asSharedFlow()

    /** Stream-mode only: emits each raw [BiosignalSample] so the host app can
     *  relay it to the paired phone. Empty in COMPUTE_LOCAL mode. */
    private val _bioSamples = MutableSharedFlow<BiosignalSample>(extraBufferCapacity = 256)
    val bioSamples: SharedFlow<BiosignalSample> = _bioSamples.asSharedFlow()

    var mode: EngineMode = EngineMode.STREAM
        private set

    /**
     * Whether raw [BiosignalSample]s are surfaced on [bioSamples] for the host
     * to relay to the phone for this session (compute-provenance contract; see
     * docs/EDGE-WIRE-CONTRACT.md).
     *
     * Decoupled from [mode] so SHADOW can BOTH compute locally (COMPUTE_LOCAL)
     * and stream raw samples. Resolved from `config.profile.edgeMode`:
     *  - OFF       → true  (pure stream; runtime never started)
     *  - SHADOW    → true  (compute-local AND stream raw)
     *  - CANONICAL → false (edge HSI is product-of-record; raw not streamed)
     * When a runtime is unavailable, both SHADOW and CANONICAL degrade to
     * STREAM and this is forced true so the phone still receives samples.
     */
    var streamRawSamples: Boolean = true
        private set

    private var config: SessionConfig? = null
    private var edgeManifest: EdgeSessionManager.SessionManifest? = null
    private var runtimeBridge: RuntimeHandle? = null
    private var startedAtMs: Long = 0
    private var seq = 0
    private var frameJob: Job? = null
    private var elapsedJob: Job? = null
    private var durationJob: Job? = null
    private var motionJob: Job? = null
    /** Timestamp when the session entered PAUSED. Used by [resumeSession]
     *  to advance [startedAtMs] so the paused interval doesn't count
     *  against elapsed/remaining. */
    private var pausedAtMs: Long = 0

    /**
     * Start a session. If [requestedMode] is null the engine resolves it: try
     * to load `synheart-core-runtime`; if available, run [EngineMode.COMPUTE_LOCAL];
     * otherwise fall back to [EngineMode.STREAM].
     */
    fun startSession(config: SessionConfig, requestedMode: EngineMode? = null) {
        if (!_state.value.watchState.canTransitionTo(WatchSessionState.STARTING)) return
        transition(WatchSessionState.STARTING)

        this.config = config
        this.seq = 0
        this.startedAtMs = System.currentTimeMillis()

        // ── Mode resolution (edge_mode compute provenance) ──────────────────
        // An explicit requestedMode still wins (host override / tests). Absent
        // that, derive behaviour from config.profile.edgeMode:
        //   OFF       → STREAM, runtime is NOT started at all (skip the native
        //               factory); watch streams raw samples only.
        //   CANONICAL → COMPUTE_LOCAL if a runtime is available, else STREAM;
        //               raw bioSamples are NOT emitted (edge HSI is product-
        //               of-record).
        //   SHADOW    → COMPUTE_LOCAL if available AND raw bioSamples ALSO
        //               emitted (compute locally and stream raw). If no
        //               runtime is available it degrades to STREAM.
        // subject_id is the EdgeSessionManager per-device opaque id (so runtime
        // personalization is keyed to this device). When no session manager is
        // injected, fall back to a per-instance random id — NEVER a shared
        // constant — so personalization is never re-shared across users.
        val edgeMode = config.profile.edgeMode
        val subjectId = sessionManager?.subjectId ?: fallbackSubjectId

        val runtime: RuntimeHandle? = when {
            requestedMode == EngineMode.STREAM -> null
            edgeMode == EdgeMode.OFF -> null  // OFF: never start the native runtime
            else -> runtimeFactory(RuntimeConfig(
                windowMs = config.profile.windowSec * 1000L,
                stepMs = config.profile.emitIntervalSec * 1000L,
                subjectId = subjectId,
                sessionId = config.sessionId,
                behaviorEnabled = false,
                edgeMode = edgeMode,
            ))
        }

        this.mode = requestedMode
            ?: if (runtime != null) EngineMode.COMPUTE_LOCAL else EngineMode.STREAM
        this.runtimeBridge = if (mode == EngineMode.COMPUTE_LOCAL) runtime else null
        // Drop any runtime we resolved but won't use (requestedMode == STREAM).
        if (this.runtimeBridge == null && runtime != null) runtime.close()

        // Raw-sample streaming is independent of compute mode: CANONICAL
        // suppresses it, OFF/SHADOW/STREAM keep it. When COMPUTE_LOCAL didn't
        // resolve (no runtime), SHADOW/CANONICAL degrade to STREAM, so raw must
        // flow regardless to keep the phone fed.
        this.streamRawSamples = when {
            mode == EngineMode.STREAM -> true
            edgeMode == EdgeMode.CANONICAL -> false
            else -> true  // SHADOW (+ OFF, though OFF never reaches COMPUTE_LOCAL)
        }
        // Confine every native handle call for this session to one dedicated
        // serial thread so they never race the try_lock'd Rust handle.
        if (this.runtimeBridge != null) {
            _droppedFrames.set(0)
            if (serializeRuntimeCalls) {
                val exec = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "synheart-runtime-${config.sessionId}").apply { isDaemon = true }
                }
                runtimeExecutor = exec
                runtimeDispatcher = exec.asCoroutineDispatcher()
            }
        }

        _state.update {
            it.copy(
                sessionKind = config.kind,
                elapsedSec = 0,
                currentHr = 0.0,
                lastMetrics = null,
                remainingSec = config.durationSec,
                pendingArtifacts = outbox?.pendingCount ?: 0,
            )
        }

        // For standalone edge sessions, create a manifest
        if (config.origin == SessionOrigin.EDGE) {
            sessionManager?.let { mgr ->
                edgeManifest = mgr.createSession(config.sessionId, config.kind)
            }
        }

        // Emit started
        scope.launch {
            _events.emit(SessionEvent.Started(sessionId = config.sessionId, startedAtMs = startedAtMs))
        }

        transition(WatchSessionState.RUNNING)

        // Start the biosignal provider. Each sample either feeds the runtime
        // (COMPUTE_LOCAL) or is surfaced as-is for the host app to relay (STREAM).
        // The provider keeps streaming through PAUSED state (turning the HR
        // sensor on/off would hammer the radio for short pauses) but the
        // callback short-circuits so paused samples don't accumulate into
        // the runtime or move UI state.
        try {
            provider.startStreaming { sample ->
                if (_state.value.watchState != WatchSessionState.RUNNING) return@startStreaming
                // Feed the local runtime in COMPUTE_LOCAL (CANONICAL + SHADOW).
                if (mode == EngineMode.COMPUTE_LOCAL) {
                    runtimeBridge?.let { bridge ->
                        // Route through the serial executor so the sensor
                        // callback thread never races the handle.
                        runtimeSubmit {
                            bridge.pushHr(sample.timestampMs, sample.bpm)
                            sample.rrIntervalsMs?.forEach { rr ->
                                bridge.pushRr(sample.timestampMs, rr)
                            }
                        }
                    }
                }
                // Emit raw samples for relay independently of compute:
                // STREAM + SHADOW + OFF stream raw; CANONICAL suppresses. SHADOW
                // therefore BOTH computes locally above AND streams raw here.
                if (streamRawSamples) {
                    _bioSamples.tryEmit(sample)
                }
                _hrEvents.tryEmit(sample.bpm to sample.timestampMs)
                _state.update { it.copy(currentHr = sample.bpm) }
            }
        } catch (e: Exception) {
            handleError(
                sessionId = config.sessionId,
                code = "sensor_unavailable",
                message = e.message ?: "$e",
            )
        }

        // Motion is only piped to runtime in COMPUTE_LOCAL mode. In STREAM mode,
        // the host app can capture motion separately if it needs it. Paused
        // samples are ignored — same rationale as the HR provider gate.
        if (mode == EngineMode.COMPUTE_LOCAL && motionSensor != null && motionSensor.isAvailable) {
            motionJob = scope.launch {
                motionSensor.motionFlow().collect { sample ->
                    if (_state.value.watchState != WatchSessionState.RUNNING) return@collect
                    val bridge = runtimeBridge ?: return@collect
                    // Serialize accel pushes onto the same runtime thread.
                    runtimeSubmit {
                        bridge.pushAccel(sample.timestampMs, sample.x, sample.y, sample.z)
                    }
                }
            }
        }

        // Periodic frame emission
        frameJob = scope.launch {
            while (isActive) {
                delay(config.profile.emitIntervalSec * 1000L)
                if (_state.value.watchState != WatchSessionState.RUNNING) break
                emitFrame()
            }
        }

        // Elapsed timer
        elapsedJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (_state.value.watchState != WatchSessionState.RUNNING) break
                val elapsed = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
                _state.update {
                    it.copy(
                        elapsedSec = elapsed,
                        remainingSec = (config.durationSec - elapsed).coerceAtLeast(0),
                    )
                }
            }
        }

        // Duration timeout
        durationJob = scope.launch {
            delay(config.durationSec * 1000L)
            stopSession()
        }
    }

    /** Start a standalone edge session from a preset. */
    fun startEdgeSession(preset: SessionPreset, requestedMode: EngineMode? = null) {
        val mgr = sessionManager ?: run {
            startSession(preset.toSessionConfig(), requestedMode)
            return
        }
        startSession(preset.toEdgeSessionConfig(mgr), requestedMode)
    }

    fun stopSession() {
        if (!_state.value.watchState.canTransitionTo(WatchSessionState.STOPPING)) return
        transition(WatchSessionState.STOPPING)
        finishSession()
    }

    /**
     * Pause an active session. Loops self-exit on their next tick (state !=
     * RUNNING guard); the biosignal provider keeps streaming through the
     * pause but its samples are dropped at the engine boundary so the
     * runtime doesn't accumulate paused-window data. Re-entrant; transitions
     * other than RUNNING → PAUSED are no-ops.
     *
     * Vibration / haptic feedback is intentionally NOT triggered here — that's
     * a UX decision the host makes by observing [state]. Keeping it out of
     * the SDK avoids hard-wiring a Vibrator service into a library that ships
     * on every Synheart-edge consumer.
     */
    fun pauseSession() {
        if (!_state.value.watchState.canTransitionTo(WatchSessionState.PAUSED)) return
        pausedAtMs = System.currentTimeMillis()
        transition(WatchSessionState.PAUSED)
        // Frame / elapsed loops will see state != RUNNING and exit on the
        // next iteration; durationJob is left as-is and rescheduled on
        // resume (it's an absolute one-shot delay that would fire at the
        // wrong wall-clock time after pause adjustment).
        durationJob?.cancel()
    }

    /**
     * Resume a paused session. Advances [startedAtMs] by the paused
     * duration so the elapsed counter skips the gap, then re-launches the
     * frame / elapsed / duration loops. No-op if not currently PAUSED.
     */
    fun resumeSession() {
        if (!_state.value.watchState.canTransitionTo(WatchSessionState.RUNNING)) return
        val cfg = config ?: return
        val now = System.currentTimeMillis()
        val pausedDuration = (now - pausedAtMs).coerceAtLeast(0)
        startedAtMs += pausedDuration
        pausedAtMs = 0
        transition(WatchSessionState.RUNNING)
        relaunchEngineLoops(cfg)
    }

    /**
     * Restart the time-driven loops (frame emission, elapsed counter,
     * duration backstop) using the current [startedAtMs] and [config].
     * Called from [resumeSession]; not called from [startSession] which
     * launches them inline so the start-up ordering stays explicit.
     */
    private fun relaunchEngineLoops(cfg: SessionConfig) {
        frameJob?.cancel()
        elapsedJob?.cancel()
        durationJob?.cancel()
        frameJob = scope.launch {
            while (isActive) {
                delay(cfg.profile.emitIntervalSec * 1000L)
                if (_state.value.watchState != WatchSessionState.RUNNING) break
                emitFrame()
            }
        }
        elapsedJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (_state.value.watchState != WatchSessionState.RUNNING) break
                val elapsed = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
                _state.update {
                    it.copy(
                        elapsedSec = elapsed,
                        remainingSec = (cfg.durationSec - elapsed).coerceAtLeast(0),
                    )
                }
            }
        }
        val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
        val remainingSec = (cfg.durationSec - elapsedSec).coerceAtLeast(0)
        if (remainingSec > 0) {
            durationJob = scope.launch {
                delay(remainingSec * 1000L)
                stopSession()
            }
        } else {
            stopSession()
        }
    }

    /** Acknowledge artifacts from phone ACK. */
    fun acknowledgeArtifacts(ids: List<String>) {
        outbox?.ackBatch(ids)
        _state.update { it.copy(pendingArtifacts = outbox?.pendingCount ?: 0) }
    }

    /**
     * Apply a state transition, re-checking [WatchSessionState.canTransitionTo]
     * and no-opping on an invalid edge (parity with Swift's `transition(to:)`,
     * which guards on `canTransition` and returns). Callers that already gate on
     * `canTransitionTo` stay correct; this is a defensive backstop so a future
     * caller can't drive the machine through an illegal edge.
     */
    private fun transition(next: WatchSessionState) {
        _state.update { current ->
            if (current.watchState == next) return@update current
            if (!current.watchState.canTransitionTo(next)) {
                logWarn("invalid transition ${current.watchState} -> $next (ignored)")
                return@update current
            }
            current.copy(watchState = next)
        }
    }

    /**
     * Handle a session error: transition
     * RUNNING/STARTING → ERROR (respecting [WatchSessionState.canTransitionTo]),
     * emit the [SessionEvent.Error], then auto-recover ERROR → IDLE after ~1s.
     * If the current state can't move to ERROR (e.g. already IDLE), the event
     * is still emitted so callers see the error, but no state change is forced.
     */
    private fun handleError(sessionId: String, code: String, message: String) {
        // Parity with Swift handleError: tear the session down before recovering
        // so a sensor failure doesn't leave the frame/elapsed/duration/motion
        // loops or the biosignal provider running against a dead session.
        cancelJobs()
        try {
            provider.stopStreaming()
        } catch (_: Throwable) { }
        try {
            motionSensor?.stopStreaming()
        } catch (_: Throwable) { }
        // Drop the per-session runtime + serial dispatcher so a half-started
        // session leaves nothing behind.
        runtimeBridge?.close()
        runtimeBridge = null
        runtimeDispatcher?.close()
        runtimeExecutor?.shutdown()
        runtimeDispatcher = null
        runtimeExecutor = null

        if (_state.value.watchState.canTransitionTo(WatchSessionState.ERROR)) {
            transition(WatchSessionState.ERROR)
        }
        scope.launch {
            _events.emit(SessionEvent.Error(
                sessionId = sessionId,
                code = code,
                message = message,
            ))
        }
        scope.launch {
            delay(1000)
            if (_state.value.watchState == WatchSessionState.ERROR &&
                WatchSessionState.ERROR.canTransitionTo(WatchSessionState.IDLE)
            ) {
                transition(WatchSessionState.IDLE)
            }
        }
    }

    private suspend fun emitFrame() {
        val config = config ?: return
        if (_state.value.watchState != WatchSessionState.RUNNING) return

        seq++
        val nowMs = System.currentTimeMillis()
        val elapsed = ((nowMs - startedAtMs) / 1000).toInt()

        if (elapsed >= config.durationSec) {
            stopSession()
            return
        }

        // Tick the runtime pipeline — returns HSI JSON when a window completes.
        // Confined to the serial runtime dispatcher; lastError is read on
        // the same thread immediately after so a concurrent-reject is observed.
        // tick + the raw-feature reads (lastPreprocessed/lastQuality) all run in
        // ONE block on the serial runtime dispatcher, so the hot frame path
        // never races a concurrent push on the try_lock'd handle.
        val readout = runOnRuntime { bridge ->
            val out = bridge.tick(nowMs)
            val err = bridge.lastError()
            if (err != 0) {
                _droppedFrames.incrementAndGet()
                logWarn(
                    "runtime tick reported last_error=$err (frame dropped); " +
                        "droppedFrames=${_droppedFrames.get()}",
                )
            }
            RuntimeReadout(out, bridge.lastPreprocessed(), bridge.lastQuality())
        }
        val hsiJson = readout?.hsiJson
        if (hsiJson != null) checkHsiVersion(hsiJson)

        // Build metrics (always, even without HSI)
        val metrics = mutableMapOf<String, Any>(
            "session_id" to config.sessionId,
            "mode" to config.mode,
            "seq" to seq,
        )
        if (hsiJson != null) {
            try {
                metrics["hsi"] = JSONObject(hsiJson)
            } catch (_: Exception) { }
        }
        // Surface the runtime's RAW derived features + quality alongside the
        // HSI 1.3 artifact. The HSI carries only inferred axis scores +
        // embeddings; the real HR/HRV/motion numbers (hr_mean_bpm, sdnn_ms,
        // rmssd_ms, accel_rms, …) live in the preprocessed accessor. Stable
        // keys `preprocessed` / `quality`.
        // Already fetched on the serial dispatcher above; just attach here.
        attachRaw(metrics, readout?.preprocessed, readout?.quality)

        _state.update { it.copy(lastMetrics = metrics) }

        // Wrap as HSI artifact envelope only when the runtime produced output.
        var envelope: HsiArtifactEnvelope? = null
        if (hsiJson != null) {
            envelope = HsiArtifactEnvelope.wrap(
                sessionId = config.sessionId,
                seq = seq,
                hsiJson = hsiJson,
                deliveryMode = config.deliveryMode,
                origin = config.origin,
                kind = config.kind,
            )

            // Persist to the durable outbox.
            outbox?.enqueue(envelope)

            // Update edge manifest if standalone
            if (config.origin == SessionOrigin.EDGE) {
                edgeManifest?.let { manifest ->
                    manifest.artifactCount++
                    edgeManifest = manifest
                    sessionManager?.updateManifest(manifest)
                }
            }

            _state.update { it.copy(pendingArtifacts = outbox?.pendingCount ?: 0) }
        }

        scope.launch {
            _events.emit(SessionEvent.Frame(
                sessionId = config.sessionId,
                seq = seq,
                emittedAtMs = nowMs,
                metrics = metrics,
            ))
            if (envelope != null) {
                _events.emit(SessionEvent.Artifact(envelope = envelope))
            }
        }
    }

    /**
     * Pull the runtime's raw preprocessed features + quality summary and put
     * them on [metrics] under the stable keys `preprocessed` / `quality`.
     *
     * COMPUTE_LOCAL only — when [runtimeBridge] is null (STREAM mode) nothing
     * is attached, so consumers see the keys' genuine absence (no math runs
     * on the watch in STREAM mode; HRV is computed phone-side from relayed
     * raw samples). The raw JSON is parsed to a [JSONObject] and nested
     * verbatim so the shape matches the native runtime's
     * `last_preprocessed_json` / `last_quality_json`.
     */
    private fun attachRuntimeRaw(metrics: MutableMap<String, Any>) {
        val bridge = runtimeBridge ?: return
        // Caller guarantees this runs on the serial runtime thread: either
        // inside runOnRuntime or the finishSession executor task.
        attachRaw(metrics, bridge.lastPreprocessed(), bridge.lastQuality())
    }

    /** Parse the raw preprocessed/quality JSON strings onto [metrics]. Pure;
     *  no native calls, so it is safe to invoke off the runtime thread. */
    private fun attachRaw(
        metrics: MutableMap<String, Any>,
        preprocessed: String?,
        quality: String?,
    ) {
        preprocessed?.let { json ->
            try {
                metrics["preprocessed"] = JSONObject(json)
            } catch (_: Exception) { }
        }
        quality?.let { json ->
            try {
                metrics["quality"] = JSONObject(json)
            } catch (_: Exception) { }
        }
    }

    /** Snapshot of one serial runtime read: tick HSI + raw feature/quality JSON,
     *  all fetched in a single critical section on the runtime dispatcher. */
    private data class RuntimeReadout(
        val hsiJson: String?,
        val preprocessed: String?,
        val quality: String?,
    )

    /**
     * Run [block] against the live runtime handle on the dedicated serial
     * dispatcher, so all native handle calls for a session execute on one
     * thread and never race the try_lock'd Rust handle. Returns null when there
     * is no runtime (STREAM mode) or no dispatcher.
     */
    private suspend fun <T> runOnRuntime(block: (RuntimeHandle) -> T): T? {
        val bridge = runtimeBridge ?: return null
        val dispatcher = runtimeDispatcher ?: return block(bridge)
        return withContext(dispatcher) { block(bridge) }
    }

    /**
     * Submit a fire-and-forget native push onto the serial runtime executor
     *. Falls back to inline execution when no executor exists (STREAM mode
     * has no runtime; tests disable serialization for deterministic timing).
     */
    private fun runtimeSubmit(block: () -> Unit) {
        val exec = runtimeExecutor
        if (exec != null) exec.execute(block) else block()
    }

    /** Log at WARN, tolerating the unit-test android.jar Log stub that throws. */
    private fun logWarn(message: String) {
        try {
            android.util.Log.w(TAG, message)
        } catch (_: Throwable) {
            // android.util.Log is a "Stub!" in JVM unit tests — ignore.
        }
    }

    /**
     * Read the `hsi_version` from a tick artifact JSON and WARN if it falls
     * outside [SUPPORTED_HSI_VERSIONS]. Observability only — no wire shape
     * or behavior changes; an unknown version is still surfaced as-is.
     */
    private fun checkHsiVersion(hsiJson: String) {
        val version = try {
            JSONObject(hsiJson).optString("hsi_version", "")
        } catch (_: Exception) { "" }
        if (version.isNotEmpty() && version !in SUPPORTED_HSI_VERSIONS) {
            logWarn(
                "tick HSI hsi_version='$version' is outside supported set " +
                    "$SUPPORTED_HSI_VERSIONS — runtime/engine may have bumped HSI version",
            )
        }
    }

    private fun finishSession() {
        val config = config ?: run {
            transition(WatchSessionState.IDLE)
            return
        }

        cancelJobs()
        provider.stopStreaming()
        motionSensor?.stopStreaming()

        val nowMs = System.currentTimeMillis()
        val durationActual = ((nowMs - startedAtMs) / 1000).toInt()

        // Final tick to capture any remaining data
        val metrics = mutableMapOf<String, Any>(
            "session_id" to config.sessionId,
            "mode" to config.mode,
            "seq" to seq,
        )
        // Run the final tick + raw-feature read on the same serial runtime
        // thread every other handle call used, so the teardown tick can't
        // race a still-draining push. Blocking here is fine: finishSession is
        // already a terminal, non-suspend step.
        //
        // B1: when the final tick produces an HSI window, it is NOT enough to
        // fold it into the Summary metrics — that artifact would never be
        // durable or ACK-tracked. Do exactly what emitFrame does: capture the
        // hsiJson here, then below bump seq, wrap an envelope, enqueue it to the
        // durable outbox, and emit a SessionEvent.Artifact so the last window is
        // persisted + relayed like every other window. OFF never reaches here
        // (no runtime); CANONICAL/SHADOW both have a runtime, so this fires for
        // both — matching emitFrame, which is mode-agnostic once a window lands.
        var finalHsiJson: String? = null
        val bridge = runtimeBridge
        if (bridge != null) {
            val task = Runnable {
                bridge.tick(nowMs)?.let { hsiJson ->
                    finalHsiJson = hsiJson
                    try {
                        metrics["hsi"] = JSONObject(hsiJson)
                    } catch (_: Exception) { }
                }
                attachRuntimeRaw(metrics)
            }
            val exec = runtimeExecutor
            if (exec != null) {
                try {
                    exec.submit(task).get()
                } catch (_: Exception) {
                    task.run()
                }
            } else {
                task.run()
            }
        }

        // B1: persist + relay the final window the same way emitFrame does.
        var finalEnvelope: HsiArtifactEnvelope? = null
        finalHsiJson?.let { hsiJson ->
            checkHsiVersion(hsiJson)
            seq++
            val envelope = HsiArtifactEnvelope.wrap(
                sessionId = config.sessionId,
                seq = seq,
                hsiJson = hsiJson,
                deliveryMode = config.deliveryMode,
                origin = config.origin,
                kind = config.kind,
            )
            finalEnvelope = envelope
            outbox?.enqueue(envelope)
            if (config.origin == SessionOrigin.EDGE) {
                edgeManifest?.let { manifest ->
                    manifest.artifactCount++
                    edgeManifest = manifest
                    sessionManager?.updateManifest(manifest)
                }
            }
        }

        _state.update {
            it.copy(lastMetrics = metrics, pendingArtifacts = outbox?.pendingCount ?: 0)
        }

        // Finalize edge manifest
        if (config.origin == SessionOrigin.EDGE) {
            edgeManifest?.let { manifest ->
                manifest.endMs = nowMs
                sessionManager?.updateManifest(manifest)
            }
        }

        scope.launch {
            // B1: emit the final-window artifact (if any) BEFORE the Summary so
            // the durable/ACK-tracked artifact is relayed in-order ahead of the
            // session close, matching the per-frame Frame→Artifact ordering.
            finalEnvelope?.let { _events.emit(SessionEvent.Artifact(envelope = it)) }
            _events.emit(SessionEvent.Summary(
                sessionId = config.sessionId,
                durationActualSec = durationActual,
                metrics = metrics,
            ))
        }

        this.config = null
        this.runtimeBridge?.close()
        this.runtimeBridge = null
        this.edgeManifest = null
        // Tear down the per-session serial runtime dispatcher.
        runtimeDispatcher?.close()
        runtimeExecutor?.shutdown()
        runtimeDispatcher = null
        runtimeExecutor = null

        transition(WatchSessionState.IDLE)
    }

    private fun cancelJobs() {
        frameJob?.cancel()
        elapsedJob?.cancel()
        durationJob?.cancel()
        motionJob?.cancel()
        frameJob = null
        elapsedJob = null
        durationJob = null
        motionJob = null
    }
}
