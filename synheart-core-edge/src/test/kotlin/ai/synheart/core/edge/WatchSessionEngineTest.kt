// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge

import ai.synheart.core.edge.engine.RuntimeHandle
import ai.synheart.core.edge.engine.WatchSessionEngine
import ai.synheart.core.edge.engine.WatchSessionState
import ai.synheart.core.edge.models.*
import ai.synheart.session.BiosignalProvider
import ai.synheart.session.BiosignalSample
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class WatchSessionEngineTest {

    /** No-op BiosignalProvider for state-machine and lifecycle tests. */
    private class FakeBioProvider : BiosignalProvider {
        override val isAvailable: Boolean = true
        override val name: String = "fake"
        override fun startStreaming(onSample: (BiosignalSample) -> Unit) {}
        override fun stopStreaming() {}
    }

    /** BiosignalProvider that captures the callback so a test can push a
     *  sample on demand (used by the edge_mode raw-streaming tests). */
    private class PushableBioProvider : BiosignalProvider {
        override val isAvailable: Boolean = true
        override val name: String = "pushable"
        private var cb: ((BiosignalSample) -> Unit)? = null
        override fun startStreaming(onSample: (BiosignalSample) -> Unit) { cb = onSample }
        override fun stopStreaming() { cb = null }
        fun push(sample: BiosignalSample) { cb?.invoke(sample) }
    }

    /**
     * Fake [RuntimeHandle] returning a known preprocessed JSON shaped exactly
     * like the native runtime's `last_preprocessed_json`: raw HRV/motion
     * nested under `derived_features`, plus a top-level `quality` block.
     */
    private class FakeRuntime : RuntimeHandle {
        var ticked = 0
        override fun pushRr(tsMs: Long, rrMs: Double) {}
        override fun pushHr(tsMs: Long, bpm: Double) {}
        override fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {}
        override fun tick(nowMs: Long): String? {
            ticked++
            // HSI 1.3 artifact — axis scores only, NO raw features.
            return """{"schema_version":"1.3","axes":{}}"""
        }
        override fun lastPreprocessed(): String =
            """{"schema_version":"1.0.0","window_start_ms":0,"window_end_ms":10000,
               "session_id":"test-1",
               "quality":{"score":0.9,"coverage_pct":0.95,"dropout_count":0,"rr_count":12,"artifact_pct":0.0},
               "derived_features":{
                 "hrv":{"rmssd_ms":42.5,"sdnn_ms":55.1,"pnn50":0.12,"mean_rr_ms":850.0,"hr_mean_bpm":70.6,"hr_std_bpm":3.2,"rr_count":12},
                 "motion":{"accel_rms":0.07,"accel_var":0.001,"steps_est":0,"posture_proxy":0.5,"sample_count":120},
                 "artifact":null},
               "behavior_features":null,
               "srm_context":{"ready_count":0,"total_count":0,"deviations":{}},
               "embeddings":{"signal_embedding":{"vector":[],"dimension":0,"space":"none"}}}"""
        override fun lastQuality(): String =
            """{"score":0.9,"coverage":0.95,"artifact_pct":0.0}"""
        override fun close() {}
    }

    private fun testConfig(
        sessionId: String = "test-1",
        mode: String = "focus",
        origin: SessionOrigin = SessionOrigin.PHONE,
        kind: SessionKind = SessionKind.FOCUS,
    ) = SessionConfig(
        sessionId = sessionId,
        mode = mode,
        durationSec = 60,
        profile = ComputeProfile(windowSec = 10, emitIntervalSec = 5),
        origin = origin,
        kind = kind,
    )

    @Test
    fun `start transitions to RUNNING`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        assertEquals(WatchSessionState.IDLE, engine.state.value.watchState)

        engine.startSession(testConfig())
        advanceTimeBy(100)
        runCurrent()

        assertEquals(WatchSessionState.RUNNING, engine.state.value.watchState)

        engine.stopSession()
        advanceUntilIdle()
    }

    @Test
    fun `stop transitions to IDLE`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.startSession(testConfig())
        advanceTimeBy(100)
        runCurrent()

        engine.stopSession()
        advanceUntilIdle()

        assertEquals(WatchSessionState.IDLE, engine.state.value.watchState)
    }

    @Test
    fun `start emits started event`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        val events = mutableListOf<SessionEvent>()
        val job = launch { engine.events.collect { events.add(it) } }

        engine.startSession(testConfig())
        advanceTimeBy(100)
        runCurrent()

        assertTrue(events.any { it is SessionEvent.Started && it.sessionId == "test-1" })

        engine.stopSession()
        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun `double start ignored`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        val events = mutableListOf<SessionEvent>()
        val job = launch { engine.events.collect { events.add(it) } }

        engine.startSession(testConfig())
        engine.startSession(testConfig())
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, events.count { it is SessionEvent.Started })

        engine.stopSession()
        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun `state transition guard works`() {
        assertFalse(WatchSessionState.IDLE.canTransitionTo(WatchSessionState.RUNNING))
        assertTrue(WatchSessionState.IDLE.canTransitionTo(WatchSessionState.STARTING))
        assertTrue(WatchSessionState.RUNNING.canTransitionTo(WatchSessionState.STOPPING))
        assertFalse(WatchSessionState.STOPPING.canTransitionTo(WatchSessionState.RUNNING))
    }

    @Test
    fun `session kind tracks config`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.startSession(testConfig(kind = SessionKind.NAP))
        advanceTimeBy(100)
        runCurrent()

        assertEquals(SessionKind.NAP, engine.state.value.sessionKind)

        engine.stopSession()
        advanceUntilIdle()
    }

    @Test
    fun `artifact envelope creation`() {
        val envelope = HsiArtifactEnvelope.wrap(
            sessionId = "test-session",
            seq = 1,
            hsiJson = """{"test": true}""",
            deliveryMode = DeliveryMode.REALTIME,
            origin = SessionOrigin.PHONE,
            kind = SessionKind.FOCUS,
        )
        assertTrue(envelope.artifactId.startsWith("hsi_"))
        assertEquals("test-session", envelope.sessionId)
        assertEquals(1, envelope.seq)
        assertEquals("1.1", envelope.schemaVersion)
        assertTrue(envelope.payloadHashSha256.isNotEmpty())
        assertEquals(DeliveryMode.REALTIME, envelope.deliveryMode)
        // Payload had no hsi_version → sentinel, not the envelope schemaVersion.
        assertEquals(HsiArtifactEnvelope.UNKNOWN_HSI_VERSION, envelope.hsiVersion)
    }

    @Test
    fun `envelope JSON carries hsi_version matching the payload`() {
        // The envelope extracts the inner payload's top-level hsi_version and
        // re-emits it on the wire as a sibling of schema_version (the two are
        // distinct: wrapper version vs payload version). See
        // docs/EDGE-WIRE-CONTRACT.md.
        val envelope = HsiArtifactEnvelope.wrap(
            sessionId = "s",
            seq = 3,
            hsiJson = """{"hsi_version":"1.3","axes":{}}""",
            deliveryMode = DeliveryMode.REALTIME,
            origin = SessionOrigin.PHONE,
            kind = SessionKind.FOCUS,
        )
        assertEquals("1.3", envelope.hsiVersion)

        val json = envelope.toJson()
        assertEquals("hsi_artifact", json.getString("type"))
        assertEquals("1.3", json.getString("hsi_version"))
        // Envelope schema_version stays distinct from the payload hsi_version.
        assertEquals("1.1", json.getString("schema_version"))

        // Round-trips through fromJson, and a payload missing hsi_version
        // falls back to the sentinel (tolerant parse).
        val parsed = HsiArtifactEnvelope.fromJson(json)
        assertEquals("1.3", parsed.hsiVersion)

        val noVersion = HsiArtifactEnvelope.wrap(
            sessionId = "s",
            seq = 4,
            hsiJson = """{"axes":{}}""",
            deliveryMode = DeliveryMode.REALTIME,
            origin = SessionOrigin.PHONE,
            kind = null,
        )
        assertEquals(HsiArtifactEnvelope.UNKNOWN_HSI_VERSION, noVersion.hsiVersion)
        assertEquals(
            HsiArtifactEnvelope.UNKNOWN_HSI_VERSION,
            noVersion.toJson().getString("hsi_version"),
        )
    }

    @Test
    fun `delivery mode derived from origin`() {
        val phoneConfig = testConfig(origin = SessionOrigin.PHONE)
        assertEquals(DeliveryMode.REALTIME, phoneConfig.deliveryMode)

        val edgeConfig = testConfig(origin = SessionOrigin.EDGE)
        assertEquals(DeliveryMode.PASSIVE_SYNC, edgeConfig.deliveryMode)
    }

    // ── ComputeProfile.edgeMode ──────────────────────────────────────────

    @Test
    fun `ComputeProfile defaults edgeMode to CANONICAL`() {
        // Earlier callers constructed ComputeProfile() with no edgeMode arg.
        // Default MUST stay CANONICAL so back-compat callers behave as before.
        assertEquals(EdgeMode.CANONICAL, ComputeProfile().edgeMode)
    }

    @Test
    fun `EdgeMode wire form is snake_case lowercase`() {
        // The native runtime parses these exact strings. Any drift breaks the
        // producer-side session_role selection silently.
        assertEquals("off", EdgeMode.OFF.toWire())
        assertEquals("shadow", EdgeMode.SHADOW.toWire())
        assertEquals("canonical", EdgeMode.CANONICAL.toWire())
    }

    @Test
    fun `EdgeMode fromWire parses canonical wire strings`() {
        assertEquals(EdgeMode.OFF, EdgeMode.fromWire("off"))
        assertEquals(EdgeMode.SHADOW, EdgeMode.fromWire("shadow"))
        assertEquals(EdgeMode.CANONICAL, EdgeMode.fromWire("canonical"))
    }

    @Test
    fun `EdgeMode unknown wire string returns null`() {
        // Forward-compat: unknown strings (future enum values, typos)
        // return null so callers can fall back deliberately rather than
        // crash.
        assertNull(EdgeMode.fromWire("tomorrows_unknown_mode"))
    }

    @Test
    fun `ComputeProfile fromJson reads edge_mode and defaults to CANONICAL when absent`() {
        val withEdgeMode = ComputeProfile.fromJson(
            org.json.JSONObject(
                """{"window_sec":30,"emit_interval_sec":3,"edge_mode":"shadow"}"""
            )
        )
        assertEquals(30, withEdgeMode.windowSec)
        assertEquals(3, withEdgeMode.emitIntervalSec)
        assertEquals(EdgeMode.SHADOW, withEdgeMode.edgeMode)

        // Legacy JSON (no edge_mode key) — falls back to CANONICAL.
        val withoutEdgeMode = ComputeProfile.fromJson(
            org.json.JSONObject("""{"window_sec":60,"emit_interval_sec":5}""")
        )
        assertEquals(EdgeMode.CANONICAL, withoutEdgeMode.edgeMode)
    }

    // ── edge_mode resolution (compute provenance) ────────────────────────

    private fun edgeModeConfig(edgeMode: EdgeMode) = SessionConfig(
        sessionId = "edge-mode-test",
        mode = "focus",
        durationSec = 60,
        profile = ComputeProfile(windowSec = 10, emitIntervalSec = 5, edgeMode = edgeMode),
        origin = SessionOrigin.PHONE,
        kind = SessionKind.FOCUS,
    )

    @Test
    fun `OFF does not start the runtime and streams raw`() = runTest {
        var factoryInvoked = false
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.runtimeFactory = { factoryInvoked = true; FakeRuntime() }
        engine.serializeRuntimeCalls = false

        engine.startSession(edgeModeConfig(EdgeMode.OFF))
        advanceTimeBy(100); runCurrent()

        // OFF: the native runtime is NEVER created.
        assertFalse("OFF must skip the runtime factory", factoryInvoked)
        assertEquals("STREAM", engine.modeName())
        assertTrue("OFF streams raw samples", engine.streamRawSamples)

        engine.stopSession()
        advanceUntilIdle()
    }

    @Test
    fun `SHADOW computes locally AND streams raw`() = runTest {
        val provider = PushableBioProvider()
        val fake = FakeRuntime()
        val engine = WatchSessionEngine(provider = provider, scope = this)
        engine.runtimeFactory = { fake }
        engine.serializeRuntimeCalls = false

        val bio = mutableListOf<BiosignalSample>()
        val job = launch { engine.bioSamples.collect { bio.add(it) } }

        engine.startSession(edgeModeConfig(EdgeMode.SHADOW))
        advanceTimeBy(100); runCurrent()

        // Compute-local resolved (runtime available) ...
        assertEquals("COMPUTE_LOCAL", engine.modeName())
        // ... AND raw streaming is on (shadow does both).
        assertTrue("SHADOW must stream raw", engine.streamRawSamples)

        // Pushing a sample both feeds the runtime and emits a raw bioSample.
        provider.push(BiosignalSample(
            timestampMs = 1_000L, bpm = 70.0,
            rrIntervalsMs = listOf(850.0), deviceId = null, source = "test",
        ))
        runCurrent()
        assertEquals("raw sample must be streamed in SHADOW", 1, bio.size)
        // Frame tick drives the runtime → proves it computes locally too.
        advanceTimeBy(5_000); runCurrent()
        assertTrue("runtime must be ticked in SHADOW", fake.ticked > 0)

        engine.stopSession()
        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun `CANONICAL computes locally and does NOT stream raw`() = runTest {
        val provider = PushableBioProvider()
        val fake = FakeRuntime()
        val engine = WatchSessionEngine(provider = provider, scope = this)
        engine.runtimeFactory = { fake }
        engine.serializeRuntimeCalls = false

        val bio = mutableListOf<BiosignalSample>()
        val job = launch { engine.bioSamples.collect { bio.add(it) } }

        engine.startSession(edgeModeConfig(EdgeMode.CANONICAL))
        advanceTimeBy(100); runCurrent()

        assertEquals("COMPUTE_LOCAL", engine.modeName())
        assertFalse("CANONICAL must NOT stream raw (edge HSI is canonical)", engine.streamRawSamples)

        provider.push(BiosignalSample(
            timestampMs = 1_000L, bpm = 70.0,
            rrIntervalsMs = listOf(850.0), deviceId = null, source = "test",
        ))
        runCurrent()
        assertEquals("CANONICAL must NOT emit raw bioSamples", 0, bio.size)
        // But it still computes locally.
        advanceTimeBy(5_000); runCurrent()
        assertTrue("runtime must be ticked in CANONICAL", fake.ticked > 0)

        engine.stopSession()
        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun `CANONICAL with no runtime degrades to STREAM and streams raw`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        // No runtime available → factory returns null.
        engine.runtimeFactory = { null }
        engine.serializeRuntimeCalls = false

        engine.startSession(edgeModeConfig(EdgeMode.CANONICAL))
        advanceTimeBy(100); runCurrent()

        assertEquals("STREAM", engine.modeName())
        assertTrue("degraded CANONICAL must stream raw so the phone is fed", engine.streamRawSamples)

        engine.stopSession()
        advanceUntilIdle()
    }

    // ── pause / resume ───────────────────────────────────────────────────

    @Test
    fun `pause transitions RUNNING to PAUSED`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.startSession(testConfig())
        advanceTimeBy(100); runCurrent()
        assertEquals(WatchSessionState.RUNNING, engine.state.value.watchState)

        engine.pauseSession()
        runCurrent()
        assertEquals(WatchSessionState.PAUSED, engine.state.value.watchState)

        engine.stopSession()
        advanceUntilIdle()
    }

    @Test
    fun `pause from IDLE is a no-op`() = runTest {
        // canTransitionTo guards this; calling pause on a non-running
        // session must not move state or touch internal counters.
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.pauseSession()
        runCurrent()
        assertEquals(WatchSessionState.IDLE, engine.state.value.watchState)
    }

    @Test
    fun `resume from PAUSED returns to RUNNING`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.startSession(testConfig())
        advanceTimeBy(100); runCurrent()

        engine.pauseSession()
        runCurrent()
        assertEquals(WatchSessionState.PAUSED, engine.state.value.watchState)

        engine.resumeSession()
        runCurrent()
        assertEquals(WatchSessionState.RUNNING, engine.state.value.watchState)

        engine.stopSession()
        advanceUntilIdle()
    }

    @Test
    fun `pause then stop transitions PAUSED to IDLE`() = runTest {
        // Pause -> Stop is the cancel-from-paused path; must complete to
        // IDLE without going through RUNNING again.
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.startSession(testConfig())
        advanceTimeBy(100); runCurrent()
        engine.pauseSession()
        runCurrent()
        assertEquals(WatchSessionState.PAUSED, engine.state.value.watchState)

        engine.stopSession()
        advanceUntilIdle()
        assertEquals(WatchSessionState.IDLE, engine.state.value.watchState)
    }

    @Test
    fun `resume without pause is a no-op`() = runTest {
        // canTransitionTo allows PAUSED -> RUNNING only, so calling
        // resume on a RUNNING session must not double-restart loops or
        // mutate startedAtMs.
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.startSession(testConfig())
        advanceTimeBy(100); runCurrent()

        engine.resumeSession()
        runCurrent()
        assertEquals(WatchSessionState.RUNNING, engine.state.value.watchState)

        engine.stopSession()
        advanceUntilIdle()
    }

    // ── preprocessed / quality frame contract (COMPUTE_LOCAL) ────────────

    @Test
    fun `frame metrics carry preprocessed with hrv leaf in COMPUTE_LOCAL`() = runTest {
        val fake = FakeRuntime()
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        // Inject the fake runtime so COMPUTE_LOCAL is resolved without the
        // native library. emitIntervalSec=5 → first frame at +5s.
        engine.runtimeFactory = { fake }
        // Run runtime calls inline on the test scheduler for deterministic timing.
        engine.serializeRuntimeCalls = false

        val frames = mutableListOf<SessionEvent.Frame>()
        val job = launch { engine.events.collect { if (it is SessionEvent.Frame) frames.add(it) } }

        engine.startSession(testConfig())
        advanceTimeBy(100); runCurrent()
        assertEquals(EngineModeAssert.COMPUTE_LOCAL_NAME, engine.modeName())

        // Advance past one emit interval to fire emitFrame().
        advanceTimeBy(5_000); runCurrent()

        assertTrue("expected at least one frame", frames.isNotEmpty())
        val metrics = frames.first().metrics

        // hsi key still present (axis scores).
        assertTrue("hsi key must remain", metrics.containsKey("hsi"))

        // preprocessed carries the RAW nested HRV/motion numbers.
        val pre = metrics["preprocessed"] as? org.json.JSONObject
        assertNotNull("preprocessed must be a JSONObject", pre)
        val hrv = pre!!.getJSONObject("derived_features").getJSONObject("hrv")
        assertEquals(42.5, hrv.getDouble("rmssd_ms"), 1e-6)
        assertEquals(55.1, hrv.getDouble("sdnn_ms"), 1e-6)
        assertEquals(70.6, hrv.getDouble("hr_mean_bpm"), 1e-6)
        val motion = pre.getJSONObject("derived_features").getJSONObject("motion")
        assertEquals(0.07, motion.getDouble("accel_rms"), 1e-6)

        // quality block surfaced under its own stable key.
        val q = metrics["quality"] as? org.json.JSONObject
        assertNotNull("quality must be a JSONObject", q)
        assertEquals(0.9, q!!.getDouble("score"), 1e-6)

        engine.stopSession()
        advanceUntilIdle()
        job.cancel()
    }
}

/** Tiny helpers so the test reads the engine mode without widening API. */
private object EngineModeAssert {
    const val COMPUTE_LOCAL_NAME = "COMPUTE_LOCAL"
}

private fun WatchSessionEngine.modeName(): String = mode.name
