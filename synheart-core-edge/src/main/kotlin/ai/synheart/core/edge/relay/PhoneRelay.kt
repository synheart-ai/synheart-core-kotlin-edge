package ai.synheart.core.edge.relay

import android.content.Context
import android.content.SharedPreferences
import ai.synheart.core.edge.engine.EdgeOutbox
import ai.synheart.core.edge.engine.EdgeSessionManager
import ai.synheart.core.edge.models.*
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Watch-side relay with outbox integration and sync protocol (RFC §5).
 */
class PhoneRelay(context: Context) {

    companion object {
        const val EVENT_PATH = "/synheart/session/event"
        const val ARTIFACT_PATH = "/synheart/session/artifact"
        const val COMMAND_PATH = "/synheart/session/command"
        const val HR_SAMPLE_PATH = "/synheart/session/hr_sample"
        const val PRESETS_DATA_PATH = "/synheart/presets"
        private const val PREFS_NAME = "synheart_presets"
        private const val PREFS_KEY = "cached_presets_json"
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    val dataClient: DataClient = Wearable.getDataClient(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _presets = MutableStateFlow<List<SessionPreset>>(emptyList())
    val presets: StateFlow<List<SessionPreset>> = _presets.asStateFlow()

    var outbox: EdgeOutbox? = null
    var sessionManager: EdgeSessionManager? = null

    init {
        loadCachedPresets()
    }

    fun configure(outbox: EdgeOutbox, sessionManager: EdgeSessionManager) {
        this.outbox = outbox
        this.sessionManager = sessionManager
    }

    /** Send a session event to the connected phone. */
    suspend fun sendEvent(event: SessionEvent) {
        val nodes = nodeClient.connectedNodes.await()
        val type = when (event) {
            is SessionEvent.Started -> "session_started"
            is SessionEvent.Frame -> "session_frame"
            is SessionEvent.Summary -> "session_summary"
            else -> event.toJson().optString("type", "other")
        }
        if (nodes.isEmpty()) {
            android.util.Log.w("PhoneRelay", "sendEvent type=$type: no connected nodes, cannot send to phone")
            return
        }
        android.util.Log.d("PhoneRelay", "sendEvent type=$type to ${nodes.size} node(s)")
        val payload = event.toJson().toString().toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, EVENT_PATH, payload).await()
                android.util.Log.d("PhoneRelay", "sendEvent sent to ${node.displayName} (${node.id})")
            } catch (e: Exception) {
                android.util.Log.w("PhoneRelay", "sendMessage failed to ${node.displayName}: ${e.message}")
            }
        }

        // Drain the durable outbox at session boundaries. The artifact outbox
        // survives an unreachable phone, but the push path only self-heals if
        // something re-sends — and we now have connected node(s). Started
        // catches up any backlog stranded from a prior session; Summary flushes
        // this session's tail. Idempotent: the phone dedups on hsi_id and ACKs,
        // which clears the outbox.
        if (event is SessionEvent.Started || event is SessionEvent.Summary) {
            retryPendingArtifacts()
        }
    }

    /** Send a real-time HR sample to the phone. */
    suspend fun sendHrSample(json: JSONObject) {
        val nodes = nodeClient.connectedNodes.await()
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, HR_SAMPLE_PATH, payload).await()
            } catch (_: Exception) { }
        }
    }

    /** Send an artifact envelope with dedicated path for reliability. */
    suspend fun sendArtifact(envelope: HsiArtifactEnvelope) {
        val nodes = nodeClient.connectedNodes.await()
        val payload = envelope.toJson().toString().toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, ARTIFACT_PATH, payload).await()
            } catch (e: Exception) {
                android.util.Log.w("PhoneRelay", "artifact send failed: ${e.message}")
            }
        }
    }

    /** Retry all pending artifacts from outbox (RFC §7.1). */
    suspend fun retryPendingArtifacts() {
        val pending = outbox?.pending() ?: return
        for (envelope in pending) {
            sendArtifact(envelope)
        }
    }

    /** Sync all pending edge sessions (RFC §5.1). */
    suspend fun syncEdgeSessions() {
        val mgr = sessionManager ?: return
        val pendingSessions = mgr.pendingSessions()
        for (manifest in pendingSessions) {
            val manifestMsg = mgr.buildSyncManifest(manifest)
            sendEvent(SessionEvent.EdgeSessionManifest(manifestMsg))
        }
    }

    /** Handle ACK from phone. */
    fun handleAck(json: JSONObject) {
        val artifactIds = mutableListOf<String>()
        val arr = json.optJSONArray("artifact_ids")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                artifactIds.add(arr.getString(i))
            }
        }
        outbox?.ackBatch(artifactIds)

        val sessionId = json.optString("session_id")
        val syncStatus = json.optString("sync_status")
        if (sessionId.isNotEmpty() && syncStatus == "complete") {
            sessionManager?.markSynced(sessionId)
        }
    }

    /** Handle sync response from phone (RFC §5.1 Step 2). */
    suspend fun handleSyncResponse(json: JSONObject) {
        val sessionId = json.optString("session_id")
        val response = json.optString("response")

        if (response == "SYNC_ALLOWED" && sessionId.isNotEmpty()) {
            val artifacts = outbox?.pending()?.filter { it.sessionId == sessionId } ?: return
            if (artifacts.isNotEmpty()) {
                sendEvent(SessionEvent.ArtifactBatch(sessionId, artifacts))
            }
        }
    }

    /** Load presets from DataClient (called on startup). */
    suspend fun loadPresetsFromDataLayer() {
        try {
            val dataItems = dataClient.getDataItems(
                android.net.Uri.parse("wear://*$PRESETS_DATA_PATH")
            ).await()
            for (item in dataItems) {
                if (item.uri.path == PRESETS_DATA_PATH) {
                    val data = item.data ?: continue
                    val json = JSONObject(String(data, Charsets.UTF_8))
                    val parsed = SessionPreset.parsePresets(json)
                    if (parsed.isNotEmpty()) {
                        updatePresets(parsed)
                    }
                }
            }
            dataItems.release()
        } catch (e: Exception) {
            android.util.Log.w("PhoneRelay", "loadPresetsFromDataLayer failed: ${e.message}")
        }
    }

    fun updatePresetsFromMessage(json: JSONObject) {
        val parsed = SessionPreset.parsePresets(json)
        if (parsed.isNotEmpty()) updatePresets(parsed)
    }

    fun updatePresetsFromData(data: ByteArray) {
        try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val parsed = SessionPreset.parsePresets(json)
            if (parsed.isNotEmpty()) updatePresets(parsed)
        } catch (e: Exception) {
            android.util.Log.w("PhoneRelay", "updatePresetsFromData failed: ${e.message}")
        }
    }

    private fun updatePresets(parsed: List<SessionPreset>) {
        _presets.value = parsed
        cachePresets(parsed)
    }

    private fun loadCachedPresets() {
        val json = prefs.getString(PREFS_KEY, null)
        if (json != null) {
            try {
                val obj = JSONObject(json)
                val parsed = SessionPreset.parsePresets(obj)
                if (parsed.isNotEmpty()) {
                    _presets.value = parsed
                    return
                }
            } catch (e: Exception) {
                android.util.Log.w("PhoneRelay", "loadCachedPresets failed: ${e.message}")
            }
        }
        _presets.value = SessionPreset.defaults
    }

    private fun cachePresets(presets: List<SessionPreset>) {
        val obj = JSONObject().apply {
            put("presets", SessionPreset.toJsonArray(presets))
        }
        prefs.edit().putString(PREFS_KEY, obj.toString()).apply()
    }
}
