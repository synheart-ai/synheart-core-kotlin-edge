// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.models

import org.json.JSONArray
import org.json.JSONObject

sealed class SessionEvent {
    abstract fun toJson(): JSONObject

    /**
     * [origin], [kind] and [durationTargetSec] describe how the session was
     * started. A host that did not send the start command itself (a session
     * started on the watch, or by another process on the phone) has no other
     * way to learn them, so without them it can neither tell a watch-started
     * session from a phone-started one nor tell completed from abandoned.
     * Optional on the wire: omitted when null, so older readers are unaffected.
     */
    data class Started(
        val sessionId: String,
        val startedAtMs: Long,
        val origin: SessionOrigin? = null,
        val kind: SessionKind? = null,
        val durationTargetSec: Int? = null,
    ) : SessionEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "session_started")
            put("session_id", sessionId)
            put("started_at_ms", startedAtMs)
            origin?.let { put("origin", it.name) }
            kind?.let { put("kind", it.name) }
            durationTargetSec?.let { put("duration_target_sec", it) }
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

    /** Carries the same start facts as [Started], for a host that missed it. */
    data class Summary(
        val sessionId: String,
        val durationActualSec: Int,
        val metrics: Map<String, Any>,
        val origin: SessionOrigin? = null,
        val kind: SessionKind? = null,
        val durationTargetSec: Int? = null,
    ) : SessionEvent() {
        override fun toJson() = JSONObject().apply {
            put("type", "session_summary")
            put("session_id", sessionId)
            put("duration_actual_sec", durationActualSec)
            put("metrics", JSONObject(metrics))
            origin?.let { put("origin", it.name) }
            kind?.let { put("kind", it.name) }
            durationTargetSec?.let { put("duration_target_sec", it) }
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
