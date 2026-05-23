package ai.synheart.core.edge.engine

import ai.synheart.core.edge.models.*
import ai.synheart.core.edge.sensor.MotionSensor
import ai.synheart.session.BiosignalProvider
import ai.synheart.session.BiosignalSample
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

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
 * On-watch session engine with formal state machine (RFC §8.1).
 *
 * **Architecture (0.0.2):** biosignals come from a [BiosignalProvider]
 * (typically `HealthConnectBiosignalProvider` from synheart-session-kotlin,
 * which wraps synheart-wear-kotlin's HealthConnectAdapter). Motion is captured
 * locally since Health Connect doesn't stream raw IMU. Session lifecycle is
 * owned by this engine; the runtime owns all signal math when
 * `mode == EngineMode.COMPUTE_LOCAL`.
 */
class WatchSessionEngine(
    private val provider: BiosignalProvider,
    private val motionSensor: MotionSensor? = null,
    val outbox: EdgeOutbox? = null,
    val sessionManager: EdgeSessionManager? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
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

    private var config: SessionConfig? = null
    private var edgeManifest: EdgeSessionManager.SessionManifest? = null
    private var runtimeBridge: RuntimeBridge? = null
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

        // Resolve mode: try runtime first.
        val runtime = RuntimeBridge.createIfAvailable(RuntimeConfig(
            windowMs = config.profile.windowSec * 1000L,
            stepMs = config.profile.emitIntervalSec * 1000L,
            subjectId = "sub_watch",
            sessionId = config.sessionId,
            behaviorEnabled = false,
            edgeMode = config.profile.edgeMode,
        ))
        this.mode = requestedMode ?: if (runtime != null) EngineMode.COMPUTE_LOCAL else EngineMode.STREAM
        this.runtimeBridge = if (mode == EngineMode.COMPUTE_LOCAL) runtime else null

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
                if (mode == EngineMode.COMPUTE_LOCAL) {
                    runtimeBridge?.let { bridge ->
                        bridge.pushHr(sample.timestampMs, sample.bpm)
                        sample.rrIntervalsMs?.forEach { rr ->
                            bridge.pushRr(sample.timestampMs, rr)
                        }
                    }
                } else {
                    _bioSamples.tryEmit(sample)
                }
                _hrEvents.tryEmit(sample.bpm to sample.timestampMs)
                _state.update { it.copy(currentHr = sample.bpm) }
            }
        } catch (e: Exception) {
            scope.launch {
                _events.emit(SessionEvent.Error(
                    sessionId = config.sessionId,
                    code = "sensor_unavailable",
                    message = e.message ?: "$e",
                ))
            }
        }

        // Motion is only piped to runtime in COMPUTE_LOCAL mode. In STREAM mode,
        // the host app can capture motion separately if it needs it. Paused
        // samples are ignored — same rationale as the HR provider gate.
        if (mode == EngineMode.COMPUTE_LOCAL && motionSensor != null && motionSensor.isAvailable) {
            motionJob = scope.launch {
                motionSensor.motionFlow().collect { sample ->
                    if (_state.value.watchState != WatchSessionState.RUNNING) return@collect
                    runtimeBridge?.pushAccel(sample.timestampMs, sample.x, sample.y, sample.z)
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

    /** Start a standalone edge session from a preset (RFC §4.2). */
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

    private fun transition(next: WatchSessionState) {
        _state.update { it.copy(watchState = next) }
    }

    private fun emitFrame() {
        val config = config ?: return
        if (_state.value.watchState != WatchSessionState.RUNNING) return

        seq++
        val nowMs = System.currentTimeMillis()
        val elapsed = ((nowMs - startedAtMs) / 1000).toInt()

        if (elapsed >= config.durationSec) {
            stopSession()
            return
        }

        // Tick the runtime pipeline — returns HSI JSON when a window completes
        val hsiJson = runtimeBridge?.tick(nowMs)

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

        _state.update { it.copy(lastMetrics = metrics) }

        // Wrap as HSI artifact envelope only when runtime produced output (RFC §6)
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

            // Persist to outbox (RFC §7.1)
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
        runtimeBridge?.tick(nowMs)?.let { hsiJson ->
            try {
                metrics["hsi"] = JSONObject(hsiJson)
            } catch (_: Exception) { }
        }

        _state.update { it.copy(lastMetrics = metrics) }

        // Finalize edge manifest
        if (config.origin == SessionOrigin.EDGE) {
            edgeManifest?.let { manifest ->
                manifest.endMs = nowMs
                sessionManager?.updateManifest(manifest)
            }
        }

        scope.launch {
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
