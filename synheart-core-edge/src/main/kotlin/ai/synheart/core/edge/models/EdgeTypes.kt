package ai.synheart.core.edge.models

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** RFC §6 — Delivery mode. */
enum class DeliveryMode { REALTIME, PASSIVE_SYNC }

/** RFC §4 — Session origin. */
enum class SessionOrigin { PHONE, EDGE }

/** RFC §4.2.1 — Session kinds (presets). */
enum class SessionKind { NAP, SLEEP, WORKOUT, FOCUS, BREATHING, DEEP_WORK }

/** RFC §6 — HSI artifact envelope. */
data class HsiArtifactEnvelope(
    val artifactId: String,
    val sessionId: String,
    val seq: Int,
    val createdAtMs: Long,
    val schemaVersion: String,
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
        put("payload_hash_sha256", payloadHashSha256)
        put("payload_json", payloadJson)
        put("delivery_mode", deliveryMode.name)
        put("session_origin", sessionOrigin.name)
        sessionKind?.let { put("session_kind", it.name) }
    }

    companion object {
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
            payloadHashSha256 = json.getString("payload_hash_sha256"),
            payloadJson = json.getString("payload_json"),
            deliveryMode = DeliveryMode.valueOf(json.getString("delivery_mode")),
            sessionOrigin = SessionOrigin.valueOf(json.getString("session_origin")),
            sessionKind = json.optString("session_kind").takeIf { it.isNotEmpty() }?.let { SessionKind.valueOf(it) },
        )
    }
}
