package ai.synheart.core.edge.engine

import ai.synheart.core.edge.models.SessionKind
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * RFC §4.2 — Manages standalone edge sessions.
 * Creates edge session IDs, tracks manifests, manages local storage.
 */
class EdgeSessionManager(context: Context) {
    private val sessionsDir: File = File(context.filesDir, "edge_sessions").apply { mkdirs() }
    private val deviceId: String

    init {
        val prefs = context.getSharedPreferences("synheart_device", Context.MODE_PRIVATE)
        val key = "device_opaque"
        deviceId = prefs.getString(key, null) ?: run {
            val id = UUID.randomUUID().toString().take(8)
            prefs.edit().putString(key, id).apply()
            id
        }
    }

    /** Generate an edge session ID per RFC §4.2.1. */
    fun generateSessionId(): String {
        val ts = System.currentTimeMillis() / 1000
        val rand = UUID.randomUUID().toString().take(6)
        return "edge_w_${deviceId}_${ts}_$rand"
    }

    /** Session manifest per RFC §4.2.2. */
    data class SessionManifest(
        val sessionId: String,
        val kind: SessionKind,
        val startMs: Long,
        var endMs: Long? = null,
        val schemaVersion: String = "1.1",
        var artifactCount: Int = 0,
        var syncStatus: String = "pending",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("session_id", sessionId)
            put("kind", kind.name)
            put("start_ms", startMs)
            endMs?.let { put("end_ms", it) }
            put("schema_version", schemaVersion)
            put("artifact_count", artifactCount)
            put("sync_status", syncStatus)
        }

        companion object {
            fun fromJson(json: JSONObject): SessionManifest = SessionManifest(
                sessionId = json.getString("session_id"),
                kind = SessionKind.valueOf(json.getString("kind")),
                startMs = json.getLong("start_ms"),
                endMs = if (json.has("end_ms")) json.getLong("end_ms") else null,
                schemaVersion = json.optString("schema_version", "1.1"),
                artifactCount = json.optInt("artifact_count", 0),
                syncStatus = json.optString("sync_status", "pending"),
            )
        }
    }

    /** Create a new edge session manifest. */
    fun createSession(sessionId: String, kind: SessionKind): SessionManifest {
        val manifest = SessionManifest(
            sessionId = sessionId,
            kind = kind,
            startMs = System.currentTimeMillis(),
        )
        saveManifest(manifest)
        return manifest
    }

    /** Update a session manifest. */
    fun updateManifest(manifest: SessionManifest) {
        saveManifest(manifest)
    }

    /** Load all session manifests with pending sync status. */
    fun pendingSessions(): List<SessionManifest> {
        return sessionsDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val manifestFile = File(dir, "manifest.json")
                if (!manifestFile.exists()) return@mapNotNull null
                try {
                    SessionManifest.fromJson(JSONObject(manifestFile.readText()))
                } catch (e: Exception) {
                    null
                }
            }
            ?.filter { it.syncStatus == "pending" }
            ?: emptyList()
    }

    /** Mark a session as synced. */
    fun markSynced(sessionId: String) {
        val manifest = loadManifest(sessionId) ?: return
        manifest.syncStatus = "synced"
        saveManifest(manifest)
    }

    /** Build a sync manifest message per RFC §5.1 Step 1. */
    fun buildSyncManifest(manifest: SessionManifest): JSONObject = JSONObject().apply {
        put("type", "edge_session_manifest")
        put("session_id", manifest.sessionId)
        put("kind", manifest.kind.name)
        put("start_ms", manifest.startMs)
        manifest.endMs?.let { put("end_ms", it) }
        put("schema_version", manifest.schemaVersion)
        put("artifact_count", manifest.artifactCount)
        put("ingest_mode", "BACKFILL")
        put("session_origin", "EDGE")
        put("origin_device", "WATCH")
    }

    private fun saveManifest(manifest: SessionManifest) {
        val sessionDir = File(sessionsDir, manifest.sessionId).apply { mkdirs() }
        File(sessionDir, "manifest.json").writeText(manifest.toJson().toString())
    }

    private fun loadManifest(sessionId: String): SessionManifest? {
        val file = File(sessionsDir, "$sessionId/manifest.json")
        if (!file.exists()) return null
        return try {
            SessionManifest.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            null
        }
    }
}
