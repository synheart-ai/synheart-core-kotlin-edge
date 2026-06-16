// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Encrypt-at-rest boundary for durable on-disk artifacts.
 *
 * The outbox / session-manager write JSON whose ON-THE-WIRE shape is fixed by
 * the edge wire contract. This cipher wraps ONLY the at-rest file bytes — the
 * JSON the relay sends and the JSON [ai.synheart.core.edge.models.HsiArtifactEnvelope.toJson]
 * produces are unchanged. Read/write are symmetric: whatever [write] persists,
 * [read] returns verbatim as the original UTF-8 string.
 */
interface ArtifactCipher {
    /** Persist [contents] to [file], replacing any prior content. */
    fun write(file: File, contents: String)

    /** Read [file] back as the original UTF-8 string. */
    fun read(file: File): String

    companion object {
        /**
         * Production cipher: a Jetpack Security [EncryptedFile] backed by an
         * Android-Keystore master key, scoped to [dir].
         *
         * ### Constraint
         * `EncryptedFile` requires the hardware/software Keystore, which is
         * unavailable in JVM unit tests (no Android runtime) and can be absent
         * on a mis-provisioned device. When the keystore can't be initialised we
         * fall back to [PlaintextCipher] so the outbox still functions (a
         * non-encrypting fallback is strictly better than dropping durable
         * artifacts) — instrumentation/manual verification on-device confirms
         * the encrypted path. The disk layout and JSON shape are identical
         * either way, so the fallback never changes the wire contract.
         *
         * `EncryptedFile` also refuses to open a path that already exists with
         * different (e.g. plaintext) content, so [EncryptedFileCipher.write]
         * deletes the target first — callers already write to a fresh `.tmp`
         * before the atomic move, so this never races a reader.
         */
        fun default(context: Context, dir: File): ArtifactCipher = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedFileCipher(context.applicationContext, masterKey)
        } catch (t: Throwable) {
            android.util.Log.w(
                "ArtifactCipher",
                "EncryptedFile unavailable (${t.message}); falling back to plaintext at rest",
            )
            PlaintextCipher
        }
    }
}

/** No-op cipher: writes/reads plaintext UTF-8. Used in unit tests and as the
 *  keystore-unavailable fallback. */
object PlaintextCipher : ArtifactCipher {
    override fun write(file: File, contents: String) = file.writeText(contents)
    override fun read(file: File): String = file.readText()
}

/**
 * [ArtifactCipher] backed by Jetpack Security [EncryptedFile] (AES-256-GCM via
 * a Keystore master key). Each file gets its own [EncryptedFile] wrapper keyed
 * by the same master key.
 */
internal class EncryptedFileCipher(
    private val context: Context,
    private val masterKey: MasterKey,
) : ArtifactCipher {

    private fun encryptedFile(file: File): EncryptedFile = EncryptedFile.Builder(
        context,
        file,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    override fun write(file: File, contents: String) {
        // EncryptedFile.openFileOutput throws if the file already exists, so
        // remove any stale target first. Callers write to a fresh temp path
        // before atomically moving into place, so this delete never races a
        // concurrent reader of the live artifact.
        if (file.exists()) file.delete()
        encryptedFile(file).openFileOutput().use { out ->
            out.write(contents.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    override fun read(file: File): String =
        encryptedFile(file).openFileInput().use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
}
