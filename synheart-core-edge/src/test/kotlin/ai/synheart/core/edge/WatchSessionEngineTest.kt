package ai.synheart.core.edge

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
    }

    @Test
    fun `delivery mode derived from origin`() {
        val phoneConfig = testConfig(origin = SessionOrigin.PHONE)
        assertEquals(DeliveryMode.REALTIME, phoneConfig.deliveryMode)

        val edgeConfig = testConfig(origin = SessionOrigin.EDGE)
        assertEquals(DeliveryMode.PASSIVE_SYNC, edgeConfig.deliveryMode)
    }

    // ── ComputeProfile.edgeMode (edge-tiering RFC §3.2) ──────────────────

    @Test
    fun `ComputeProfile defaults edgeMode to CANONICAL`() {
        // Pre-RFC callers constructed ComputeProfile() with no third arg.
        // Default MUST stay CANONICAL so back-compat callers behave as before.
        assertEquals(EdgeMode.CANONICAL, ComputeProfile().edgeMode)
    }

    @Test
    fun `EdgeMode wire form is snake_case lowercase`() {
        // The Rust side (core-runtime SynheartConfig::from_value) parses
        // these exact strings. Any drift breaks the producer-side
        // session_role selection silently.
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

        // Pre-RFC JSON (no edge_mode key) — falls back to CANONICAL.
        val withoutEdgeMode = ComputeProfile.fromJson(
            org.json.JSONObject("""{"window_sec":60,"emit_interval_sec":5}""")
        )
        assertEquals(EdgeMode.CANONICAL, withoutEdgeMode.edgeMode)
    }

    // ── pause / resume (RFC §8.1 + pause extension) ──────────────────────

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
}
