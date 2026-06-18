// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.models

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Delivery mode: realtime relay vs. deferred passive sync. */
enum class DeliveryMode { REALTIME, PASSIVE_SYNC }

/** Where a session was initiated: the paired phone, or standalone on the watch. */
enum class SessionOrigin { PHONE, EDGE }

/** Session kinds backing the built-in presets. */
enum class SessionKind { NAP, SLEEP, WORKOUT, FOCUS, BREATHING, DEEP_WORK }

/** HSI artifact envelope. See EDGE-WIRE-CONTRACT.md in the synheart-edge repo. */
data class HsiArtifactEnvelope(
    val artifactId: String,
    val sessionId: String,
    val seq: Int,
    val createdAtMs: Long,
    val schemaVersion: String,
    /**
     * Inner HSI payload version, extracted from [payloadJson]'s top-level
     * `hsi_version` at [wrap] time (see EDGE-WIRE-CONTRACT.md in the
     * synheart-edge repo). Distinct from
     * [schemaVersion], which versions this envelope wrapper. Carried on the
     * wire so consumers can validate the payload version without parsing the
     * opaque [payloadJson]. Defaults to `"unknown"` when the payload can't be
     * parsed or omits the key. Additive — existing consumers ignore it.
     */
    val hsiVersion: String,
    val payloadHashSha256: String,
    val payloadJson: String,
    val deliveryMode: DeliveryMode,
    val sessionOrigin: SessionOrigin,
    val sessionKind: SessionKind?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "hsi_artifact")
        put("artifact_id", artifactId)
        put("session_id", sessionId)
        put("seq", seq)
        put("created_at_ms", createdAtMs)
        put("schema_version", schemaVersion)
        put("hsi_version", hsiVersion)
        put("payload_hash_sha256", payloadHashSha256)
        put("payload_json", payloadJson)
        put("delivery_mode", deliveryMode.name)
        put("session_origin", sessionOrigin.name)
        sessionKind?.let { put("session_kind", it.name) }
    }

    companion object {
        /** Sentinel for an HSI payload whose `hsi_version` can't be read. */
        const val UNKNOWN_HSI_VERSION = "unknown"

        /**
         * Read the top-level `hsi_version` from an HSI payload JSON string,
         * tolerating malformed/absent input by returning [UNKNOWN_HSI_VERSION].
         */
        private fun extractHsiVersion(hsiJson: String): String = try {
            JSONObject(hsiJson).optString("hsi_version", "").takeIf { it.isNotEmpty() }
                ?: UNKNOWN_HSI_VERSION
        } catch (_: Exception) {
            UNKNOWN_HSI_VERSION
        }

        /** Create an envelope wrapping an HSI JSON payload. */
        fun wrap(
            sessionId: String,
            seq: Int,
            hsiJson: String,
            deliveryMode: DeliveryMode,
            origin: SessionOrigin,
            kind: SessionKind?,
        ): HsiArtifactEnvelope {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(hsiJson.toByteArray(Charsets.UTF_8))
            val hashHex = hash.joinToString("") { "%02x".format(it) }

            return HsiArtifactEnvelope(
                artifactId = "hsi_${UUID.randomUUID().toString().take(12)}_$seq",
                sessionId = sessionId,
                seq = seq,
                createdAtMs = System.currentTimeMillis(),
                schemaVersion = "1.1",
                hsiVersion = extractHsiVersion(hsiJson),
                payloadHashSha256 = hashHex,
                payloadJson = hsiJson,
                deliveryMode = deliveryMode,
                sessionOrigin = origin,
                sessionKind = kind,
            )
        }

        fun fromJson(json: JSONObject): HsiArtifactEnvelope = HsiArtifactEnvelope(
            artifactId = json.getString("artifact_id"),
            sessionId = json.getString("session_id"),
            seq = json.getInt("seq"),
            createdAtMs = json.getLong("created_at_ms"),
            schemaVersion = json.getString("schema_version"),
            // Tolerant: older producers (and the persisted outbox) predate
            // hsi_version. Fall back to the payload, then to the sentinel.
            hsiVersion = json.optString("hsi_version", "").takeIf { it.isNotEmpty() }
                ?: extractHsiVersion(json.optString("payload_json", "")),
            payloadHashSha256 = json.getString("payload_hash_sha256"),
            payloadJson = json.getString("payload_json"),
            deliveryMode = DeliveryMode.valueOf(json.getString("delivery_mode")),
            sessionOrigin = SessionOrigin.valueOf(json.getString("session_origin")),
            sessionKind = json.optString("session_kind").takeIf { it.isNotEmpty() }?.let { SessionKind.valueOf(it) },
        )
    }
}
