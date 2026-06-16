// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.engine

import ai.synheart.core.edge.models.HsiArtifactEnvelope
import ai.synheart.core.edge.security.ArtifactCipher
import ai.synheart.core.edge.security.PlaintextCipher
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Durable artifact outbox: file-based persistence that survives restart and
 * deletes each artifact on ACK. One file per artifact, written atomically.
 *
 * The on-disk directory name is namespaced so two SDK-based apps on the same
 * device don't collide. [dirName] defaults to the canonical value (unchanged);
 * a white-label fork or multi-tenant host can pass a distinct one.
 *
 * ### Concurrency
 * `enqueue` / `ack` / `ackBatch` / `pending` / `pendingCount` / `clear` are
 * called from at least three threads: the frame coroutine, the Wear binder
 * thread (PhoneListenerService → acknowledgeArtifacts), and the retry
 * coroutine (PhoneRelay.retryPendingArtifacts). To avoid racing the filesystem
 * (e.g. a delete interleaving a rename, or two writers stomping the same temp
 * file), EVERY mutation AND read is confined to one single-thread serial
 * executor. Public methods block on the executor so call-site semantics are
 * unchanged (still synchronous, return values intact).
 *
 * ### Encryption at rest
 * Artifacts are written through an injectable [ArtifactCipher] so the on-disk
 * bytes can be encrypted (the production [EdgeOutbox] constructor wires a
 * Jetpack Security `EncryptedFile`-backed cipher). The on-the-wire / JSON
 * shape produced by [HsiArtifactEnvelope.toJson] is unchanged — only the
 * at-rest file bytes differ.
 *
 * ### Retention
 * [sweepExpired] drops un-ACKed artifacts older than [retentionMs] so a
 * permanently-unreachable phone can't grow the outbox without bound.
 */
class EdgeOutbox internal constructor(
    private val dir: File,
    private val cipher: ArtifactCipher = PlaintextCipher,
    /** Max age (ms) an un-ACKed artifact is retained before [sweepExpired]
     *  drops it. Defaults to [DEFAULT_RETENTION_MS]. */
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
) {

    constructor(
        context: Context,
        dirName: String = DEFAULT_DIR_NAME,
        retentionMs: Long = DEFAULT_RETENTION_MS,
    ) : this(
        File(context.filesDir, dirName),
        ArtifactCipher.default(context, File(context.filesDir, dirName)),
        retentionMs,
    )

    /**
     * Single serial executor that ALL outbox filesystem mutations and reads run
     * on, so the frame coroutine, the Wear binder thread, and the retry
     * coroutine can never interleave a delete with a rename.
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "synheart-outbox").apply { isDaemon = true }
    }

    init {
        run { dir.mkdirs() }
    }

    /** Run [block] on the serial executor and block for its result. Failures
     *  surface to the caller exactly as a direct call would have. */
    private fun <T> onIo(block: () -> T): T =
        io.submit(Callable { block() }).get()

    companion object {
        /** Default subdirectory (under `filesDir`) for the durable outbox. */
        const val DEFAULT_DIR_NAME = "edge_outbox"
        /** Default retention bound: 7 days. Un-ACKed artifacts older than this
         *  are dropped by [sweepExpired]. */
        const val DEFAULT_RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        /**
         * Artifact ids are interpolated into a filesystem path, so they MUST be
         * a flat, dot/slash-free token. Legit ids are `hsi_<12hex>_<seq>`; this
         * charset (alnum, underscore, hyphen) covers them while rejecting any
         * `.`, `/`, `\`, or null byte that could escape [dir].
         */
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]+$")

        /** True when [id] is safe to interpolate into an outbox path. */
        internal fun isSafeArtifactId(id: String): Boolean = SAFE_ID.matches(id)
    }

    /**
     * Persist an artifact envelope to disk atomically: write to a temp file,
     * then atomically rename it into place so a crash mid-write can never
     * leave a half-written / corrupt artifact at the final path. The on-disk
     * JSON format is unchanged (modulo the at-rest [cipher] wrapping).
     *
     * Rejects an artifact whose id is not a safe path token.
     */
    fun enqueue(envelope: HsiArtifactEnvelope) = onIo {
        val id = envelope.artifactId
        if (!isSafeArtifactId(id)) {
            logWarn("enqueue: rejecting unsafe artifact_id='$id'")
            return@onIo
        }
        val file = File(dir, "$id.json")
        val tmp = File(dir, "$id.json.tmp")
        cipher.write(tmp, envelope.toJson().toString())
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Filesystem doesn't support atomic move. NEVER delete the
            // destination first — that would leave a window where the artifact
            // is absent if the rename then fails. Instead try a (possibly
            // non-atomic) replacing move; only if even that is unsupported do we
            // fall back to a copy-then-swap that keeps the old file until the
            // replacement is durably written.
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // Last resort: the replacement bytes are already durable in tmp;
                // copy over the destination (REPLACE_EXISTING), then drop tmp.
                // The destination is only touched once the source is known good.
                try {
                    Files.copy(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Acknowledge receipt — delete from outbox. Skips unsafe ids. */
    fun ack(artifactId: String) = onIo { ackLocked(artifactId) }

    /** Acknowledge multiple artifacts. */
    fun ackBatch(artifactIds: List<String>) = onIo {
        artifactIds.forEach { ackLocked(it) }
    }

    /** Delete one artifact file. MUST run on [io]. */
    private fun ackLocked(artifactId: String) {
        if (!isSafeArtifactId(artifactId)) {
            logWarn("ack: rejecting unsafe artifact_id='$artifactId'")
            return
        }
        File(dir, "$artifactId.json").delete()
    }

    /** Load all pending (un-ACKed) envelopes. */
    fun pending(): List<HsiArtifactEnvelope> = onIo {
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    HsiArtifactEnvelope.fromJson(JSONObject(cipher.read(file)))
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedBy { it.seq }
            ?: emptyList()
    }

    /** Number of pending artifacts. */
    val pendingCount: Int
        get() = onIo { dir.listFiles { f -> f.extension == "json" }?.size ?: 0 }

    /**
     * Drop un-ACKed artifacts whose file is older than [retentionMs].
     * Returns the number of artifacts dropped. Uses last-modified time, which
     * tracks the atomic-move into place. Safe to call on any thread.
     */
    fun sweepExpired(nowMs: Long = System.currentTimeMillis()): Int = onIo {
        var dropped = 0
        dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            if (nowMs - file.lastModified() > retentionMs) {
                if (file.delete()) dropped++
            }
        }
        dropped
    }

    /** Clear all artifacts. */
    fun clear() = onIo {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    /** WARN-level log, tolerating the unit-test android.jar Log stub that throws. */
    private fun logWarn(message: String) {
        try {
            android.util.Log.w("EdgeOutbox", message)
        } catch (_: Throwable) {
        }
    }
}
