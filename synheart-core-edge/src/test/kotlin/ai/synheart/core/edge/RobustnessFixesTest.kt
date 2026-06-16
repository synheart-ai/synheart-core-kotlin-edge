// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge

import ai.synheart.core.edge.engine.EdgeOutbox
import ai.synheart.core.edge.engine.RuntimeBridge
import ai.synheart.core.edge.engine.RuntimeConfig
import ai.synheart.core.edge.engine.RuntimeNative
import ai.synheart.core.edge.engine.RuntimeHandle
import ai.synheart.core.edge.engine.WatchSessionEngine
import ai.synheart.core.edge.engine.WatchSessionState
import ai.synheart.core.edge.models.*
import ai.synheart.session.BiosignalProvider
import ai.synheart.session.BiosignalSample
import com.sun.jna.Pointer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RobustnessFixesTest {

    private class FakeBioProvider : BiosignalProvider {
        override val isAvailable: Boolean = true
        override val name: String = "fake"
        override fun startStreaming(onSample: (BiosignalSample) -> Unit) {}
        override fun stopStreaming() {}
    }

    private fun testConfig() = SessionConfig(
        sessionId = "robustness-1",
        mode = "focus",
        durationSec = 60,
        profile = ComputeProfile(windowSec = 10, emitIntervalSec = 5),
        origin = SessionOrigin.PHONE,
        kind = SessionKind.FOCUS,
    )

    // ── handle lifecycle safety (double-close is a no-op) ─────────────────

    /** Counting fake of the native ABI: records how many times destroy runs. */
    private class CountingNative : RuntimeNative {
        var destroyCount = 0
        override fun synheart_core_edge_create(config_json: String?): Pointer? = null
        override fun synheart_core_edge_destroy(handle: Pointer?) { destroyCount++ }
        override fun synheart_core_edge_push_rr(handle: Pointer?, ts_ms: Long, rr_ms: Double) {}
        override fun synheart_core_edge_push_hr(handle: Pointer?, ts_ms: Long, bpm: Double) {}
        override fun synheart_core_edge_push_accel(handle: Pointer?, ts_ms: Long, x: Double, y: Double, z: Double) {}
        override fun synheart_core_edge_tick(handle: Pointer?, now_ms: Long): Pointer? = null
        override fun synheart_core_edge_last_quality(handle: Pointer?): Pointer? = null
        override fun synheart_core_edge_last_preprocessed(handle: Pointer?): Pointer? = null
        override fun synheart_core_edge_frame_count(handle: Pointer?): Long = 0
        override fun synheart_core_edge_reset(handle: Pointer?) {}
        override fun synheart_core_edge_last_error(handle: Pointer?): Int = 0
        override fun synheart_core_edge_free_string(ptr: Pointer?) {}
        override fun synheart_core_edge_version(): Pointer? = null
    }

    @Test
    fun `double close is safe and destroys exactly once`() {
        val native = CountingNative()
        // A non-null bogus pointer is fine — CountingNative never derefs it.
        val bridge = RuntimeBridge(Pointer(0x1000L), native)

        bridge.close()
        bridge.close()
        bridge.close()

        // destroy ran once; later closes were guarded no-ops (no double-free).
        assertEquals(1, native.destroyCount)
    }

    @Test
    fun `calls after close are no-ops not crashes`() {
        val native = CountingNative()
        val bridge = RuntimeBridge(Pointer(0x1000L), native)
        bridge.close()

        // All handle calls must bail out cleanly once the handle is null.
        bridge.pushHr(0, 70.0)
        bridge.pushRr(0, 850.0)
        bridge.pushAccel(0, 0.0, 0.0, 0.0)
        assertNull(bridge.tick(0))
        assertNull(bridge.lastPreprocessed())
        assertNull(bridge.lastQuality())
        assertEquals(-1, bridge.lastError())   // null handle → -1
        assertEquals(0L, bridge.frameCount())
        assertEquals(1, native.destroyCount)   // still only the original close
    }

    // ── atomic outbox write ──────────────────────────────────────────────

    private fun envelope(seq: Int) = HsiArtifactEnvelope.wrap(
        sessionId = "atomic-1",
        seq = seq,
        hsiJson = """{"hsi_version":"1.3","axes":{}}""",
        deliveryMode = DeliveryMode.REALTIME,
        origin = SessionOrigin.PHONE,
        kind = SessionKind.FOCUS,
    )

    @Test
    fun `enqueue persists round-trippable envelope`() {
        val dir = Files.createTempDirectory("outbox").toFile()
        val outbox = EdgeOutbox(dir)
        val env = envelope(1)

        outbox.enqueue(env)

        // Exactly one .json artifact, no .tmp left behind.
        val files = dir.listFiles()!!.map { it.name }
        assertEquals(listOf("${env.artifactId}.json"), files)
        assertEquals(1, outbox.pendingCount)

        val loaded = outbox.pending().single()
        assertEquals(env.artifactId, loaded.artifactId)
        assertEquals(env.payloadHashSha256, loaded.payloadHashSha256)
        assertEquals(env.seq, loaded.seq)
    }

    @Test
    fun `enqueue overwrites prior content for same id without leaving temp files`() {
        val dir = Files.createTempDirectory("outbox").toFile()
        val outbox = EdgeOutbox(dir)
        val env = envelope(7)

        outbox.enqueue(env)
        outbox.enqueue(env) // re-enqueue same id

        // Atomic rename must replace cleanly: one json, zero tmp.
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("${env.artifactId}.json"), names)
        assertEquals(1, outbox.pendingCount)
        assertTrue(names.none { it.endsWith(".tmp") })
    }

    // ── ERROR state + auto-recovery to IDLE ──────────────────────────────

    private class ThrowingProvider : BiosignalProvider {
        override val isAvailable: Boolean = true
        override val name: String = "throwing"
        override fun startStreaming(onSample: (BiosignalSample) -> Unit) {
            throw IllegalStateException("sensor boom")
        }
        override fun stopStreaming() {}
    }

    @Test
    fun `session error transitions to ERROR then auto-recovers to IDLE`() = runTest {
        val engine = WatchSessionEngine(provider = ThrowingProvider(), scope = this)
        val events = mutableListOf<SessionEvent>()
        val job = launch { engine.events.collect { events.add(it) } }

        engine.startSession(testConfig())
        advanceTimeBy(100); runCurrent()

        // Provider threw during startStreaming → handleError moved RUNNING→ERROR.
        assertEquals(WatchSessionState.ERROR, engine.state.value.watchState)
        assertTrue(events.any { it is SessionEvent.Error && it.code == "sensor_unavailable" })

        // Auto-recovery after ~1s back to IDLE.
        advanceTimeBy(1_100); runCurrent()
        assertEquals(WatchSessionState.IDLE, engine.state.value.watchState)

        job.cancel()
    }

    // ── last_error dropped-frame counter / HSI version awareness ──────────

    /** Fake runtime that reports a nonzero last_error after each tick and
     *  emits an out-of-set hsi_version, exercising both observability paths. */
    private class ErroringRuntime : RuntimeHandle {
        override fun pushRr(tsMs: Long, rrMs: Double) {}
        override fun pushHr(tsMs: Long, bpm: Double) {}
        override fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {}
        override fun tick(nowMs: Long): String = """{"hsi_version":"9.9","axes":{}}"""
        override fun lastPreprocessed(): String? = null
        override fun lastQuality(): String? = null
        override fun lastError(): Int = 7 // simulate ERR_CONCURRENT_CALL
        override fun close() {}
    }

    @Test
    fun `nonzero last_error after tick increments droppedFrames`() = runTest {
        val engine = WatchSessionEngine(provider = FakeBioProvider(), scope = this)
        engine.runtimeFactory = { ErroringRuntime() }
        // Run runtime calls inline on the test scheduler for deterministic timing.
        engine.serializeRuntimeCalls = false

        engine.startSession(testConfig())
        advanceTimeBy(100); runCurrent()
        assertEquals(0L, engine.droppedFrames)

        // Advance past one emit interval → emitFrame ticks → last_error=7.
        advanceTimeBy(5_000); runCurrent()
        assertTrue("droppedFrames must increment on last_error", engine.droppedFrames >= 1)

        engine.stopSession()
        advanceUntilIdle()
    }

    @Test
    fun `SUPPORTED_HSI_VERSIONS contains the known set`() {
        assertEquals(setOf("1.1", "1.2", "1.3"), WatchSessionEngine.SUPPORTED_HSI_VERSIONS)
    }

    // ── FFI config JSON injection (security) ─────────────────────────────

    /** Native fake that captures the create-config JSON for assertions. */
    private class CapturingNative : RuntimeNative {
        var lastConfig: String? = null
        override fun synheart_core_edge_create(config_json: String?): Pointer? {
            lastConfig = config_json
            return Pointer(0x2000L) // non-null so createIfAvailable succeeds
        }
        override fun synheart_core_edge_destroy(handle: Pointer?) {}
        override fun synheart_core_edge_push_rr(handle: Pointer?, ts_ms: Long, rr_ms: Double) {}
        override fun synheart_core_edge_push_hr(handle: Pointer?, ts_ms: Long, bpm: Double) {}
        override fun synheart_core_edge_push_accel(handle: Pointer?, ts_ms: Long, x: Double, y: Double, z: Double) {}
        override fun synheart_core_edge_tick(handle: Pointer?, now_ms: Long): Pointer? = null
        override fun synheart_core_edge_last_quality(handle: Pointer?): Pointer? = null
        override fun synheart_core_edge_last_preprocessed(handle: Pointer?): Pointer? = null
        override fun synheart_core_edge_frame_count(handle: Pointer?): Long = 0
        override fun synheart_core_edge_reset(handle: Pointer?) {}
        override fun synheart_core_edge_last_error(handle: Pointer?): Int = 0
        override fun synheart_core_edge_free_string(ptr: Pointer?) {}
        override fun synheart_core_edge_version(): Pointer? = null
    }

    // ── H1: artifact_id path-traversal sanitization ──────────────────────

    /** An envelope whose artifact_id is forged to a path-traversal token. The
     *  copy keeps every other field intact so only the id is hostile. */
    private fun maliciousEnvelope(id: String): HsiArtifactEnvelope =
        envelope(1).copy(artifactId = id)

    @Test
    fun `enqueue rejects a path-traversal artifact_id and writes nothing outside dir`() {
        val root = Files.createTempDirectory("outbox-root").toFile()
        val dir = File(root, "edge_outbox").apply { mkdirs() }
        val secret = File(root, "secret.json").apply { writeText("{\"keep\":true}") }
        val outbox = EdgeOutbox(dir)

        // "../secret" would resolve to root/secret.json — outside the outbox.
        outbox.enqueue(maliciousEnvelope("../secret"))
        // A nested traversal too.
        outbox.enqueue(maliciousEnvelope("../../etc/evil"))

        // Nothing was written into the outbox dir, and the sibling file is intact.
        assertEquals(0, outbox.pendingCount)
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals("{\"keep\":true}", secret.readText())
    }

    @Test
    fun `ack ignores a path-traversal artifact_id and does not delete outside dir`() {
        val root = Files.createTempDirectory("outbox-root2").toFile()
        val dir = File(root, "edge_outbox").apply { mkdirs() }
        // A file one level up the ack id would target if traversal were allowed:
        // dir/../secret.json == root/secret.json
        val secret = File(root, "secret.json").apply { writeText("payload") }
        val outbox = EdgeOutbox(dir)

        outbox.ack("../secret")
        outbox.ackBatch(listOf("../secret", "..", "a/b"))

        assertTrue("traversal ack must not delete an outside file", secret.exists())
    }

    @Test
    fun `legit hsi id is accepted`() {
        val dir = Files.createTempDirectory("outbox-ok").toFile()
        val outbox = EdgeOutbox(dir)
        // Shape: hsi_<12hex>_<seq>
        val env = maliciousEnvelope("hsi_0123456789ab_3")
        outbox.enqueue(env)
        assertEquals(1, outbox.pendingCount)
        assertEquals("hsi_0123456789ab_3", outbox.pending().single().artifactId)
    }

    // ── B2: atomic outbox write never drops the destination ───────────────

    @Test
    fun `re-enqueue keeps a valid artifact at the path the whole time`() {
        // Re-enqueue the same id repeatedly; at no point may the destination be
        // missing or a stray .tmp be left behind (the old delete-then-rename
        // fallback could leave a gap). End state: exactly one json, zero tmp,
        // round-trippable.
        val dir = Files.createTempDirectory("outbox-atomic").toFile()
        val outbox = EdgeOutbox(dir)
        val env = envelope(42)

        repeat(5) {
            outbox.enqueue(env)
            // The destination file exists after every enqueue.
            assertTrue(File(dir, "${env.artifactId}.json").exists())
        }

        val names = dir.listFiles()!!.map { it.name }
        assertEquals(listOf("${env.artifactId}.json"), names)
        assertTrue("no temp file may survive", names.none { it.endsWith(".tmp") })
        assertEquals(env.payloadHashSha256, outbox.pending().single().payloadHashSha256)
    }

    @Test
    fun `concurrent enqueue and ack from many threads never corrupts the outbox`() {
        // Hammer the outbox from many threads (mirrors the frame coroutine +
        // binder thread + retry coroutine all hitting it). With the serial
        // executor confinement this must never throw or leave a partial file.
        val dir = Files.createTempDirectory("outbox-concurrent").toFile()
        val outbox = EdgeOutbox(dir)
        val envs = (0 until 50).map { envelope(it) }

        val threads = envs.map { env ->
            Thread {
                outbox.enqueue(env)
                outbox.pendingCount
                outbox.pending()
                if (env.seq % 2 == 0) outbox.ack(env.artifactId)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Only odd-seq artifacts remain; every file is a clean, parseable json.
        val remaining = outbox.pending()
        assertTrue(remaining.all { it.seq % 2 == 1 })
        assertEquals(remaining.size, outbox.pendingCount)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    // ── H3: retention sweep drops old un-ACKed artifacts ──────────────────

    @Test
    fun `sweepExpired drops artifacts older than the retention bound`() {
        val dir = Files.createTempDirectory("outbox-retain").toFile()
        // 1-hour retention for the test.
        val outbox = EdgeOutbox(dir, retentionMs = 60 * 60 * 1000L)
        val oldEnv = envelope(1)
        val freshEnv = envelope(2)
        outbox.enqueue(oldEnv)
        outbox.enqueue(freshEnv)

        // Backdate the "old" artifact's mtime to 2 hours ago.
        File(dir, "${oldEnv.artifactId}.json")
            .setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)

        val dropped = outbox.sweepExpired()
        assertEquals(1, dropped)
        val ids = outbox.pending().map { it.artifactId }
        assertEquals(listOf(freshEnv.artifactId), ids)
    }

    // ── B1: final-window artifact is persisted + emitted ──────────────────

    /** Runtime whose tick ALWAYS returns an HSI window (so the final teardown
     *  tick in finishSession produces an artifact). */
    private class AlwaysWindowRuntime : RuntimeHandle {
        override fun pushRr(tsMs: Long, rrMs: Double) {}
        override fun pushHr(tsMs: Long, bpm: Double) {}
        override fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {}
        override fun tick(nowMs: Long): String = """{"hsi_version":"1.3","axes":{}}"""
        override fun lastPreprocessed(): String? = null
        override fun lastQuality(): String? = null
        override fun lastError(): Int = 0
        override fun close() {}
    }

    @Test
    fun `final tick persists and emits the last-window artifact`() = runTest {
        val dir = Files.createTempDirectory("outbox-final").toFile()
        val outbox = EdgeOutbox(dir)
        val engine = WatchSessionEngine(
            provider = FakeBioProvider(),
            outbox = outbox,
            scope = this,
        )
        engine.runtimeFactory = { AlwaysWindowRuntime() }
        engine.serializeRuntimeCalls = false

        val artifacts = mutableListOf<SessionEvent.Artifact>()
        val job = launch {
            engine.events.collect { if (it is SessionEvent.Artifact) artifacts.add(it) }
        }

        engine.startSession(testConfig())
        advanceTimeBy(100); runCurrent()

        val pendingBefore = outbox.pendingCount
        val artifactsBefore = artifacts.size

        // Stop immediately (before any periodic frame) so the ONLY artifact is
        // the final-window one produced by finishSession's teardown tick.
        engine.stopSession()
        advanceUntilIdle()

        // The final window was enqueued to the durable outbox ...
        assertEquals(
            "final window must be persisted to the outbox",
            pendingBefore + 1,
            outbox.pendingCount,
        )
        // ... and emitted as a SessionEvent.Artifact (durable + ACK-trackable).
        assertEquals(
            "final window must be emitted as an Artifact event",
            artifactsBefore + 1,
            artifacts.size,
        )
        // The persisted envelope round-trips and carries the final seq.
        val persisted = outbox.pending().last()
        assertEquals(artifacts.last().envelope.artifactId, persisted.artifactId)

        job.cancel()
    }

    @Test
    fun `session_id with a quote cannot break the FFI config JSON`() {
        // A malicious / malformed phone-supplied session_id with embedded
        // quotes + backslashes must NOT escape the JSON string: the config
        // must still parse and round-trip the value verbatim.
        val nasty = """abc" , "injected_key":"x","session_id":"\evil"""
        val native = CapturingNative()
        val bridge = RuntimeBridge.createIfAvailable(
            RuntimeConfig(
                subjectId = """sub_"; DROP""",
                sessionId = nasty,
                edgeMode = EdgeMode.SHADOW,
            ),
            native,
        )
        assertNotNull("createIfAvailable should return a bridge", bridge)

        val raw = native.lastConfig
        assertNotNull("native must have received a config json", raw)

        // It parses (string interpolation would have produced invalid JSON) ...
        val parsed = org.json.JSONObject(raw!!)
        // ... and the untrusted values survived intact, NOT as injected keys.
        assertEquals(nasty, parsed.getString("session_id"))
        assertEquals("""sub_"; DROP""", parsed.getString("subject_id"))
        assertFalse("no injected key may appear", parsed.has("injected_key"))
        assertEquals("shadow", parsed.getJSONObject("compute_profile").getString("edge_mode"))

        bridge!!.close()
    }
}
