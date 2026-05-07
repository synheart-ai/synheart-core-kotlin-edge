package ai.synheart.core.edge.models

import org.json.JSONObject

data class ComputeProfile(
    val windowSec: Int = 60,
    val emitIntervalSec: Int = 5,
) {
    companion object {
        fun fromJson(json: JSONObject): ComputeProfile = ComputeProfile(
            windowSec = json.optInt("window_sec", 60),
            emitIntervalSec = json.optInt("emit_interval_sec", 5),
        )
    }
}

data class SessionConfig(
    val sessionId: String,
    val mode: String,
    val durationSec: Int,
    val profile: ComputeProfile = ComputeProfile(),
    val windowLabel: String? = null,
    val origin: SessionOrigin = SessionOrigin.PHONE,
    val kind: SessionKind = SessionKind.FOCUS,
) {
    /** Delivery mode derived from origin (RFC §6). */
    val deliveryMode: DeliveryMode
        get() = if (origin == SessionOrigin.PHONE) DeliveryMode.REALTIME else DeliveryMode.PASSIVE_SYNC

    companion object {
        /** Parse from phone command (Mode A — phone-initiated). */
        fun fromPhoneCommand(json: JSONObject): SessionConfig {
            val mode = json.getString("mode")
            val profile = if (json.has("profile")) {
                ComputeProfile.fromJson(json.getJSONObject("profile"))
            } else {
                ComputeProfile()
            }
            val kindStr = json.optString("kind", "FOCUS").uppercase()
            return SessionConfig(
                sessionId = json.getString("session_id"),
                mode = mode,
                durationSec = json.getInt("duration_sec"),
                profile = profile,
                windowLabel = if (json.has("window_label")) json.getString("window_label") else null,
                origin = SessionOrigin.PHONE,
                kind = try { SessionKind.valueOf(kindStr) } catch (_: Exception) { SessionKind.FOCUS },
            )
        }
    }
}
