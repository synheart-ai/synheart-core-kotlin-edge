// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.relay

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import ai.synheart.core.edge.engine.EdgeOutbox
import ai.synheart.core.edge.engine.EdgeSessionManager
import ai.synheart.core.edge.models.*
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Watch-side relay: durable-outbox integration plus the watch→phone sync
 * protocol. See the Synheart Edge wire contract (EDGE-WIRE-CONTRACT.md in the
 * synheart-edge repo) for the message shapes and paths.
 *
 * The cached-presets SharedPreferences file is namespaced so two SDK-based apps
 * on the same device don't collide. [prefsName] defaults to the canonical value
 * (unchanged); a white-label fork or multi-tenant host can pass a distinct one.
 *
 * @param context Android context used for the Wearable clients and prefs.
 * @param prefsName SharedPreferences file used to cache synced presets.
 */
class PhoneRelay(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME,
) {

    companion object {
        // ── Wire protocol message paths (canonical contract) ────────────────
        // These `/synheart/...` paths are the canonical watch↔phone wire
        // protocol. They MUST match the phone-side receiver and the listener
        // service below byte-for-byte — see EDGE-WIRE-CONTRACT.md in the
        // synheart-edge repo. A
        // white-label fork that wants its own namespace must change these
        // values consistently on BOTH the watch and the phone (and the
        // OS-routed listener service), or messages will silently fail to route.
        const val EVENT_PATH = "/synheart/session/event"
        const val ARTIFACT_PATH = "/synheart/session/artifact"
        const val COMMAND_PATH = "/synheart/session/command"
        const val HR_SAMPLE_PATH = "/synheart/session/hr_sample"
        /** Full raw biosignal sample (HR + RR + accel) for STREAM mode, where
         *  HRV is computed phone-side. Distinct from [HR_SAMPLE_PATH] (scalar
         *  bpm only) so the phone can route RR/motion-bearing samples to its
         *  own physiology pipeline. */
        const val BIO_SAMPLE_PATH = "/synheart/session/bio_sample"
        const val PRESETS_DATA_PATH = "/synheart/presets"
        /** Default SharedPreferences file used to cache synced presets. */
        const val DEFAULT_PREFS_NAME = "synheart_presets"
        private const val PREFS_KEY = "cached_presets_json"

        /**
         * How long a connected-node lookup is reused before re-querying. Node
         * membership is stable within a session, so 30 s collapses ~1 Hz
         * per-sample IPC into ~1 lookup / 30 s. A reconnection is picked up on
         * the next reachability refresh (resume) or within this window.
         */
        private const val NODE_CACHE_TTL_MS = 30_000L
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    val dataClient: DataClient = Wearable.getDataClient(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

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

    /**
     * Live reachability flag exposed as `phoneReachable`.
     *
     * Semantics: true when at least one paired Wear node (the phone) is
     * currently CONNECTED — i.e. a transport link exists. (Note this is a
     * coarser notion than "the companion app is reachable right now"; the two
     * differ by transport mechanism.)
     *
     * Distinct from "presets are non-empty" (always true once defaults load).
     * Drives the EDGE vs PHONE origin decision in the host so
     * `WatchSessionEngine.startEdgeSession` (standalone / passive-sync) is
     * actually reachable when the phone is away. Refreshed via
     * [refreshReachability].
     */
    private val _phoneReachable = MutableStateFlow(false)
    val phoneReachable: StateFlow<Boolean> = _phoneReachable.asStateFlow()

    // ── Connected-node cache ────────────────────────────────────────────────
    // The connected-node set was previously queried (`nodeClient.connectedNodes
    // .await()`, a binder/IPC round-trip) on EVERY message — including live HR /
    // biosignal samples at ~1 Hz. Node membership changes rarely during a
    // session, so we cache it with a short TTL: one lookup per [NODE_CACHE_TTL_MS]
    // instead of one per sample. This is the single biggest avoidable wake/IPC
    // pattern during a running session. Reachability checks force a refresh so
    // the UI/origin decision still sees fresh state on resume.
    private val nodeCacheMutex = Mutex()
    @Volatile private var cachedNodes: List<Node> = emptyList()
    @Volatile private var cachedNodesAtMs: Long = 0L
    @Volatile private var cachedNodesValid: Boolean = false

    private suspend fun connectedNodesCached(forceRefresh: Boolean = false): List<Node> {
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh && cachedNodesValid && (now - cachedNodesAtMs) < NODE_CACHE_TTL_MS) {
            return cachedNodes
        }
        return nodeCacheMutex.withLock {
            val t = SystemClock.elapsedRealtime()
            if (!forceRefresh && cachedNodesValid && (t - cachedNodesAtMs) < NODE_CACHE_TTL_MS) {
                return@withLock cachedNodes
            }
            val nodes = try {
                nodeClient.connectedNodes.await()
            } catch (e: Exception) {
                android.util.Log.w("PhoneRelay", "connectedNodes lookup failed: ${e.message}")
                emptyList()
            }
            cachedNodes = nodes
            cachedNodesAtMs = SystemClock.elapsedRealtime()
            cachedNodesValid = true
            nodes
        }
    }

    /**
     * Query connected Wear nodes and update [phoneReachable]. Call on
     * resume / before deciding session origin. Returns the fresh value.
     * Forces a node-cache refresh so a just-connected phone is seen immediately.
     */
    suspend fun refreshReachability(): Boolean {
        val reachable = connectedNodesCached(forceRefresh = true).isNotEmpty()
        _phoneReachable.value = reachable
        return reachable
    }

    /** Send a session event to the connected phone. */
    suspend fun sendEvent(event: SessionEvent) {
        val nodes = connectedNodesCached()
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
        val nodes = connectedNodesCached()
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, HR_SAMPLE_PATH, payload).await()
            } catch (_: Exception) { }
        }
    }

    /**
     * Send a full raw biosignal sample to the phone (STREAM mode). Carries
     * `bpm`, the raw `rr_intervals_ms` array, and optional `accel` so the
     * phone can run the HRV / motion math the watch deliberately doesn't.
     * Fire-and-forget: dropped samples are tolerable because the phone
     * reconstructs HRV from the windowed stream.
     */
    suspend fun sendBiosignalSample(json: JSONObject) {
        val nodes = connectedNodesCached()
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, BIO_SAMPLE_PATH, payload).await()
            } catch (_: Exception) { }
        }
    }

    /** Send an artifact envelope with dedicated path for reliability. */
    suspend fun sendArtifact(envelope: HsiArtifactEnvelope) {
        val nodes = connectedNodesCached()
        val payload = envelope.toJson().toString().toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, ARTIFACT_PATH, payload).await()
            } catch (e: Exception) {
                android.util.Log.w("PhoneRelay", "artifact send failed: ${e.message}")
            }
        }
    }

    /** Retry all pending artifacts from the durable outbox. */
    suspend fun retryPendingArtifacts() {
        val pending = outbox?.pending() ?: return
        for (envelope in pending) {
            sendArtifact(envelope)
        }
    }

    /** Sync all pending standalone edge sessions to the phone. */
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

    /** Handle a sync-response message from the phone. */
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
