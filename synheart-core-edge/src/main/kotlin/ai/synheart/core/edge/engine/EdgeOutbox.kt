package ai.synheart.core.edge.engine

import ai.synheart.core.edge.models.HsiArtifactEnvelope
import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * RFC §3.5 + §7.1 — Durable artifact outbox.
 * File-based persistence. Survives restart. Deletes on ACK.
 */
class EdgeOutbox(context: Context) {
    private val dir: File = File(context.filesDir, "edge_outbox").apply { mkdirs() }

    /** Persist an artifact envelope to disk. */
    fun enqueue(envelope: HsiArtifactEnvelope) {
        val file = File(dir, "${envelope.artifactId}.json")
        file.writeText(envelope.toJson().toString())
    }

    /** Acknowledge receipt — delete from outbox. */
    fun ack(artifactId: String) {
        File(dir, "$artifactId.json").delete()
    }

    /** Acknowledge multiple artifacts. */
    fun ackBatch(artifactIds: List<String>) {
        artifactIds.forEach { ack(it) }
    }

    /** Load all pending (un-ACKed) envelopes. */
    fun pending(): List<HsiArtifactEnvelope> {
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    HsiArtifactEnvelope.fromJson(JSONObject(file.readText()))
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedBy { it.seq }
            ?: emptyList()
    }

    /** Number of pending artifacts. */
    val pendingCount: Int
        get() = dir.listFiles { f -> f.extension == "json" }?.size ?: 0

    /** Clear all artifacts. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
