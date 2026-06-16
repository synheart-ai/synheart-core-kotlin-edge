// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.relay

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import ai.synheart.core.edge.engine.WatchSessionEngine
import ai.synheart.core.edge.models.SessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Background service that receives commands from the phone app even when
 * the watch app is not in the foreground. Also handles DataClient changes
 * for preset sync.
 *
 * Commands are routed through a host-supplied [Bindings] as the AUTHORITATIVE
 * path (durable: works when the app has never been opened or was killed). The
 * Activity-supplied [onCommandReceived] is a non-authoritative UI notification
 * only — it is invoked (when set) so the foreground UI can react, but it does
 * NOT short-circuit or replace the durable [Bindings] routing, so a stale
 * Activity callback on a dead lifecycle scope can never drop a command.
 *
 * When no [Bindings] is installed (e.g. a host that only ever runs in the
 * foreground), [onCommandReceived] is used as the execution fallback so such
 * hosts keep working.
 *
 * ### Host setup
 *
 * The OSS library does not own the [WatchSessionEngine] / [PhoneRelay]
 * singletons — the host (e.g. the Wear OS app's `Application` subclass)
 * does. To enable background command handling, set [bindings] from the
 * host's `Application.onCreate`:
 *
 * ```kotlin
 * class MyWearApp : Application() {
 *   override fun onCreate() {
 *     super.onCreate()
 *     PhoneListenerService.bindings = object : PhoneListenerService.Bindings {
 *       override val engine = myEngineSingleton
 *       override val relay = myRelaySingleton
 *       override val appScope = myApplicationScope
 *     }
 *   }
 * }
 * ```
 *
 * If [bindings] is null and no Activity callback is set, background
 * commands are logged and dropped — safe default, no crashes.
 */
class PhoneListenerService : WearableListenerService() {

    /** Contract the host supplies so the service can route background commands. */
    interface Bindings {
        val engine: WatchSessionEngine
        val relay: PhoneRelay
        val appScope: CoroutineScope
    }

    companion object {
        private const val TAG = "PhoneListenerService"
        /**
         * Non-authoritative UI notification invoked (when set) on every command
         * so a foreground Activity can react. It does NOT execute the command
         * when [bindings] is installed (that is the durable path's job) and it
         * does NOT short-circuit routing — so a stale callback left on a dead
         * lifecycle scope after `onDestroy` cannot drop or duplicate commands.
         * Only used to EXECUTE a command when no [bindings] is installed.
         */
        var onCommandReceived: ((JSONObject) -> Unit)? = null
        /** Callback for preset data changes. Set by the Activity. */
        var onPresetsDataChanged: ((ByteArray) -> Unit)? = null
        /**
         * Host-supplied bindings — the AUTHORITATIVE command path. When null
         * (the default) and no [onCommandReceived] callback is registered,
         * commands are dropped with a warning instead of starting / stopping a
         * session against an unknown engine. The host sets this from its
         * `Application.onCreate` so commands survive Activity teardown.
         */
        var bindings: Bindings? = null
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // M1: the ENTIRE dispatch body is wrapped so malformed input
        // (non-JSON bytes, missing fields surfaced by SessionConfig.fromPhoneCommand,
        // etc.) can never crash the Wear binder thread. A bad message is logged
        // and dropped.
        try {
            if (messageEvent.path != PhoneRelay.COMMAND_PATH) return

            // C1: validate the SENDER before touching the command. The exported
            // service can be hit by ANY app/node on the device; only the paired
            // companion is allowed to start/stop sessions or ACK artifacts.
            // Reject any source node that isn't a currently-connected Wear node
            // (the paired phone). Unknown / spoofed senders are dropped before
            // any dispatch.
            if (!isTrustedSender(messageEvent.sourceNodeId)) {
                Log.w(TAG, "Dropping command from untrusted node ${messageEvent.sourceNodeId}")
                return
            }

            val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
            val command = json.optString("command")
            Log.d(TAG, "onMessageReceived: command=$command")

            // AUTHORITATIVE path: route through durable Bindings when installed.
            // This is checked FIRST and is never short-circuited by the Activity
            // callback, so commands are delivered even after Activity teardown.
            val b = bindings
            if (b != null) {
                dispatchToBindings(b, command, json)
                // Non-authoritative UI notification — let a live foreground
                // Activity react (e.g. navigate), but DON'T re-execute the
                // command here (Bindings already did) and DON'T short-circuit.
                notifyActivity(json)
                return
            }

            // No Bindings installed: fall back to the Activity callback as the
            // execution path so foreground-only hosts keep working.
            val callback = onCommandReceived
            if (callback != null) {
                callback.invoke(json)
                return
            }

            Log.w(TAG, "Dropping command $command: no Bindings and no onCommandReceived")
        } catch (t: Throwable) {
            // Never let a malformed message take down the binder thread.
            Log.w(TAG, "onMessageReceived: dropping malformed message: $t")
        }
    }

    /**
     * C1: true when [sourceNodeId] is a currently-connected Wear node (the
     * paired companion). The Wear OS node graph only contains paired/connected
     * devices, so a connected-node allowlist rejects arbitrary on-device
     * senders that target the exported service. Resolved synchronously (the
     * binder callback is already off the main thread); on any lookup failure we
     * fail CLOSED (reject) so an error can't open the door.
     */
    private fun isTrustedSender(sourceNodeId: String?): Boolean {
        if (sourceNodeId.isNullOrEmpty()) return false
        return try {
            val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
            nodes.any { it.id == sourceNodeId }
        } catch (t: Throwable) {
            Log.w(TAG, "isTrustedSender lookup failed (rejecting): $t")
            false
        }
    }

    /** Execute [command] against the durable host [b] (the authoritative path). */
    private fun dispatchToBindings(b: Bindings, command: String, json: JSONObject) {
        when (command) {
            "start_session" -> {
                val config = SessionConfig.fromPhoneCommand(json)
                Log.d(TAG, "Starting session from phone: ${config.sessionId}")
                b.engine.startSession(config)
            }
            "stop_session" -> b.engine.stopSession()
            "sync_presets" -> b.relay.updatePresetsFromMessage(json)
            "artifact_ack" -> {
                b.relay.handleAck(json)
                b.engine.acknowledgeArtifacts(
                    (0 until (json.optJSONArray("artifact_ids")?.length() ?: 0))
                        .map { json.getJSONArray("artifact_ids").getString(it) }
                )
            }
            "sync_response" -> b.appScope.launch { b.relay.handleSyncResponse(json) }
        }
    }

    /** Invoke the optional Activity UI notification, swallowing any failure
     *  (e.g. a stale lambda whose lifecycle scope is dead) so it can never
     *  break the authoritative path. */
    private fun notifyActivity(json: JSONObject) {
        val callback = onCommandReceived ?: return
        try {
            callback.invoke(json)
        } catch (t: Throwable) {
            Log.w(TAG, "onCommandReceived UI notification failed (ignored): $t")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == PhoneRelay.PRESETS_DATA_PATH
            ) {
                event.dataItem.data?.let { onPresetsDataChanged?.invoke(it) }
            }
        }
    }
}
