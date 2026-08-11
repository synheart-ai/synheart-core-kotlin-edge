// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.engine

import ai.synheart.core.edge.models.SessionKind
import ai.synheart.core.edge.security.ArtifactCipher
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages standalone edge sessions: creates edge session IDs, tracks manifests,
 * and manages local storage.
 *
 * Persistence is namespaced so two SDK-based apps in the same process / on the
 * same device don't collide. The optional [namespace] parameters default to the
 * current canonical values (unchanged on-disk layout); a white-label fork or a
 * host embedding multiple Synheart sessions can pass distinct values to isolate
 * its SharedPreferences file, device-id key, and session directory.
 *
 * @param prefsName SharedPreferences file holding the persisted device id.
 * @param deviceKey key inside [prefsName] for the opaque device id.
 * @param sessionsDirName subdirectory under `filesDir` holding session manifests.
 */
class EdgeSessionManager(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME,
    deviceKey: String = DEFAULT_DEVICE_KEY,
    sessionsDirName: String = DEFAULT_SESSIONS_DIR,
) {
    private val sessionsDir: File = File(context.filesDir, sessionsDirName).apply { mkdirs() }
    private val deviceId: String

    /**
     * Encrypt-at-rest boundary for session manifests. Backed by Jetpack
     * Security `EncryptedFile`; falls back to plaintext only when the Keystore
     * is unavailable. The on-disk JSON shape (and the sync-manifest wire shape)
     * is unchanged — only the at-rest manifest bytes differ.
     */
    private val cipher: ArtifactCipher = ArtifactCipher.default(context, sessionsDir)

    init {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val key = deviceKey
        deviceId = prefs.getString(key, null) ?: run {
            val id = UUID.randomUUID().toString().take(8)
            prefs.edit().putString(key, id).apply()
            id
        }
    }

    /**
     * Stable, per-device opaque id used as the runtime `subject_id` for edge
     * personalization. Reuses the same persisted device-opaque value that seeds
     * [generateSessionId], so runtime personalization is keyed to this physical
     * device and survives process restarts — never re-shared across users.
     */
    val subjectId: String get() = "sub_$deviceId"

    /** Generate a unique standalone edge session ID. */
    fun generateSessionId(): String {
        val ts = System.currentTimeMillis() / 1000
        val rand = UUID.randomUUID().toString().take(6)
        return "edge_w_${deviceId}_${ts}_$rand"
    }

    /** Persisted manifest describing one standalone edge session. */
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
                // Tolerant by name, matching SessionPreset/SessionConfig: a
                // manifest written by a newer build must stay readable after a
                // downgrade. `kind` is non-null here, so an unknown name lands
                // on FOCUS rather than failing the whole manifest.
                kind = runCatching { SessionKind.valueOf(json.getString("kind")) }
                    .getOrDefault(SessionKind.FOCUS),
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
                    SessionManifest.fromJson(JSONObject(cipher.read(manifestFile)))
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

    /** Build the sync-manifest message sent to the phone when backfilling. */
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
        // sessionId becomes a directory name; reject any non-flat token so a
        // phone-supplied id can't escape sessionsDir (mirrors the outbox H1
        // sanitiser). Legit ids are `edge_w_<id>_<ts>_<rand>` or the phone's
        // own session_id.
        if (!isSafeSessionId(manifest.sessionId)) {
            logWarn("saveManifest: rejecting unsafe session_id='${manifest.sessionId}'")
            return
        }
        val sessionDir = File(sessionsDir, manifest.sessionId).apply { mkdirs() }
        cipher.write(File(sessionDir, "manifest.json"), manifest.toJson().toString())
    }

    private fun loadManifest(sessionId: String): SessionManifest? {
        if (!isSafeSessionId(sessionId)) return null
        val file = File(sessionsDir, "$sessionId/manifest.json")
        if (!file.exists()) return null
        return try {
            SessionManifest.fromJson(JSONObject(cipher.read(file)))
        } catch (e: Exception) {
            null
        }
    }

    /** WARN-level log, tolerating the unit-test android.jar Log stub. */
    private fun logWarn(message: String) {
        try {
            android.util.Log.w("EdgeSessionManager", message)
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** Default SharedPreferences file for the persisted device id. */
        const val DEFAULT_PREFS_NAME = "synheart_device"
        /** Default key for the opaque device id inside the prefs file. */
        const val DEFAULT_DEVICE_KEY = "device_opaque"
        /** Default subdirectory (under `filesDir`) for session manifests. */
        const val DEFAULT_SESSIONS_DIR = "edge_sessions"

        /** session_id is used as a directory name; restrict to a flat token so
         *  it can never escape the sessions dir (path-traversal hardening). */
        private val SAFE_SESSION_ID = Regex("^[A-Za-z0-9_-]+$")
        internal fun isSafeSessionId(id: String): Boolean = SAFE_SESSION_ID.matches(id)
    }
}
