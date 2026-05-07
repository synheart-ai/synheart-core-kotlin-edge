package ai.synheart.core.edge.models

import org.json.JSONArray
import org.json.JSONObject

sealed class SessionEvent {
    abstract fun toJson(): JSONObject

    data class Started(
        val sessionId: String,
        val startedAtMs: Long,
    ) : SessionEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "session_started")
            put("session_id", sessionId)
            put("started_at_ms", startedAtMs)
        }
    }

    data class Frame(
        val sessionId: String,
        val seq: Int,
        val emittedAtMs: Long,
        val metrics: Map<String, Any>,
    ) : SessionEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "session_frame")
            put("session_id", sessionId)
            put("seq", seq)
            put("emitted_at_ms", emittedAtMs)
            put("metrics", JSONObject(metrics))
        }
    }

    data class Artifact(
        val envelope: HsiArtifactEnvelope,
    ) : SessionEvent() {
        override fun toJson() = envelope.toJson()
    }

    data class Summary(
        val sessionId: String,
        val durationActualSec: Int,
        val metrics: Map<String, Any>,
    ) : SessionEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "session_summary")
            put("session_id", sessionId)
            put("duration_actual_sec", durationActualSec)
            put("metrics", JSONObject(metrics))
        }
    }

    data class Error(
        val sessionId: String,
        val code: String,
        val message: String,
    ) : SessionEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "session_error")
            put("session_id", sessionId)
            put("code", code)
            put("message", message)
        }
    }

    data class EdgeSessionManifest(
        val manifest: JSONObject,
    ) : SessionEvent() {
        override fun toJson() = manifest
    }

    data class ArtifactBatch(
        val sessionId: String,
        val envelopes: List<HsiArtifactEnvelope>,
    ) : SessionEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "hsi_artifact_batch")
            put("session_id", sessionId)
            put("artifacts", JSONArray().apply {
                envelopes.forEach { put(it.toJson()) }
            })
        }
    }

    data class SessionAck(
        val sessionId: String,
        val artifactIds: List<String>,
    ) : SessionEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "session_ack")
            put("session_id", sessionId)
            put("artifact_ids", JSONArray(artifactIds))
        }
    }
}
