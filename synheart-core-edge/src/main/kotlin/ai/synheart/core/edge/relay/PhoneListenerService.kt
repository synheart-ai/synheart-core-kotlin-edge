package ai.synheart.core.edge.relay

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
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
 * Commands are handled either via [onCommandReceived] (when the host
 * Activity has set it for foreground delivery) or routed to a host-
 * supplied [Bindings] so sessions can start when the app has never been
 * opened or was killed.
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
        /** Callback for received commands. Set by the Activity when in foreground. */
        var onCommandReceived: ((JSONObject) -> Unit)? = null
        /** Callback for preset data changes. Set by the Activity. */
        var onPresetsDataChanged: ((ByteArray) -> Unit)? = null
        /**
         * Host-supplied bindings for background command delivery. When null
         * (the default) and no [onCommandReceived] callback is registered,
         * background commands are dropped with a warning instead of
         * starting / stopping a session against an unknown engine. The host
         * sets this from its `Application.onCreate`.
         */
        var bindings: Bindings? = null
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PhoneRelay.COMMAND_PATH) return
        val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
        val command = json.optString("command")
        Log.d(TAG, "onMessageReceived: command=$command")

        // Prefer Activity callback when set (app in foreground)
        val callback = onCommandReceived
        if (callback != null) {
            callback.invoke(json)
            return
        }

        // Background path — requires the host to have installed Bindings.
        val b = bindings
        if (b == null) {
            Log.w(TAG, "Dropping background command $command: no Bindings installed")
            return
        }

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
