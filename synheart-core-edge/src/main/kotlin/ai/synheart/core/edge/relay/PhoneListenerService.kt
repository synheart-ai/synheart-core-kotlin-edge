package ai.synheart.core.edge.relay

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import ai.synheart.core.edge.SynheartWatchApp
import ai.synheart.core.edge.models.SessionConfig
import kotlinx.coroutines.launch

/**
 * Background service that receives commands from the phone app
 * even when the watch app is not in the foreground.
 * Also handles DataClient changes for preset sync.
 *
 * Commands are handled either via [onCommandReceived] (when MainActivity has set it)
 * or directly using the Application's shared engine/relay so sessions can start
 * when the app has never been opened or was killed.
 */
class PhoneListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneListenerService"
        /** Callback for received commands. Set by the Activity when in foreground. */
        var onCommandReceived: ((JSONObject) -> Unit)? = null
        /** Callback for preset data changes. Set by the Activity. */
        var onPresetsDataChanged: ((ByteArray) -> Unit)? = null
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

        // Otherwise handle here so sessions work when app is not open
        val app = application as? SynheartWatchApp ?: return
        val engine = app.engine
        val relay = app.relay

        when (command) {
            "start_session" -> {
                val config = SessionConfig.fromPhoneCommand(json)
                Log.d(TAG, "Starting session from phone: ${config.sessionId}")
                engine.startSession(config)
            }
            "stop_session" -> engine.stopSession()
            "sync_presets" -> relay.updatePresetsFromMessage(json)
            "artifact_ack" -> {
                relay.handleAck(json)
                engine.acknowledgeArtifacts(
                    (0 until (json.optJSONArray("artifact_ids")?.length() ?: 0))
                        .map { json.getJSONArray("artifact_ids").getString(it) }
                )
            }
            "sync_response" -> app.appScope.launch { relay.handleSyncResponse(json) }
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
