package ai.synheart.core.edge.models

import ai.synheart.core.edge.engine.EdgeSessionManager
import org.json.JSONArray
import org.json.JSONObject

data class SessionPreset(
    val id: String,
    val label: String,
    val mode: String,
    val durationSec: Int,
    val profile: ComputeProfile,
    val kind: SessionKind = SessionKind.FOCUS,
) {
    /** Create a phone-initiated SessionConfig from this preset. */
    fun toSessionConfig(): SessionConfig = SessionConfig(
        sessionId = java.util.UUID.randomUUID().toString(),
        mode = mode,
        durationSec = durationSec,
        profile = profile,
        windowLabel = "${mode}_session",
        origin = SessionOrigin.PHONE,
        kind = kind,
    )

    /** Create a standalone edge SessionConfig (RFC §4.2). */
    fun toEdgeSessionConfig(sessionManager: EdgeSessionManager): SessionConfig = SessionConfig(
        sessionId = sessionManager.generateSessionId(),
        mode = mode,
        durationSec = durationSec,
        profile = profile,
        windowLabel = "${mode}_edge",
        origin = SessionOrigin.EDGE,
        kind = kind,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("mode", mode)
        put("kind", kind.name)
        put("duration_sec", durationSec)
        put("profile", JSONObject().apply {
            put("window_sec", profile.windowSec)
            put("emit_interval_sec", profile.emitIntervalSec)
        })
    }

    companion object {
        val defaults: List<SessionPreset> = listOf(
            SessionPreset(
                id = "default_focus_5", label = "Focus 5 min", mode = "focus",
                durationSec = 300, profile = ComputeProfile(windowSec = 60, emitIntervalSec = 5),
                kind = SessionKind.FOCUS,
            ),
            SessionPreset(
                id = "default_breathing_3", label = "Breathing 3 min", mode = "breathing",
                durationSec = 180, profile = ComputeProfile(windowSec = 30, emitIntervalSec = 3),
                kind = SessionKind.BREATHING,
            ),
            SessionPreset(
                id = "default_nap_20", label = "Nap 20 min", mode = "nap",
                durationSec = 1200, profile = ComputeProfile(windowSec = 60, emitIntervalSec = 10),
                kind = SessionKind.NAP,
            ),
            SessionPreset(
                id = "default_sleep_8h", label = "Sleep 8h", mode = "sleep",
                durationSec = 28800, profile = ComputeProfile(windowSec = 60, emitIntervalSec = 30),
                kind = SessionKind.SLEEP,
            ),
            SessionPreset(
                id = "default_workout_30", label = "Workout 30 min", mode = "workout",
                durationSec = 1800, profile = ComputeProfile(windowSec = 30, emitIntervalSec = 5),
                kind = SessionKind.WORKOUT,
            ),
        )

        fun fromJson(json: JSONObject): SessionPreset {
            val kindStr = json.optString("kind", "FOCUS").uppercase()
            return SessionPreset(
                id = json.getString("id"),
                label = json.getString("label"),
                mode = json.getString("mode"),
                durationSec = json.getInt("duration_sec"),
                profile = if (json.has("profile")) {
                    ComputeProfile.fromJson(json.getJSONObject("profile"))
                } else {
                    ComputeProfile()
                },
                kind = try { SessionKind.valueOf(kindStr) } catch (_: Exception) { SessionKind.FOCUS },
            )
        }

        fun parsePresets(json: JSONObject): List<SessionPreset> {
            val arr = json.optJSONArray("presets") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                try { fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }
        }

        fun toJsonArray(presets: List<SessionPreset>): JSONArray {
            val arr = JSONArray()
            for (preset in presets) arr.put(preset.toJson())
            return arr
        }
    }
}
