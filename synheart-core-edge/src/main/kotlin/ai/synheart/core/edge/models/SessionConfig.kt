// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.models

import org.json.JSONObject

data class ComputeProfile(
    val windowSec: Int = 60,
    val emitIntervalSec: Int = 5,
    /**
     * How the edge runtime's HSI should be reported relative to a paired
     * phone. Forwarded to the native runtime as `compute_profile.edge_mode`
     * in the FFI config JSON, where it controls the `session_role` stamped on
     * `meta.synheart.compute`. Default [EdgeMode.CANONICAL] reports the watch
     * HSI as product-of-record. See docs/EDGE-WIRE-CONTRACT.md for the
     * compute-provenance contract.
     */
    val edgeMode: EdgeMode = EdgeMode.CANONICAL,
) {
    companion object {
        fun fromJson(json: JSONObject): ComputeProfile = ComputeProfile(
            windowSec = json.optInt("window_sec", 60),
            emitIntervalSec = json.optInt("emit_interval_sec", 5),
            edgeMode = json.optString("edge_mode")
                .takeIf { it.isNotEmpty() }
                ?.let { EdgeMode.fromWire(it) }
                ?: EdgeMode.CANONICAL,
        )
    }
}

/**
 * How the watch's edge HSI should be reported relative to a paired phone.
 * The wire form is sent to the native runtime as `compute_profile.edge_mode`;
 * see docs/EDGE-WIRE-CONTRACT.md for the compute-provenance contract.
 */
enum class EdgeMode {
    /** Edge runtime does not start. Watch streams raw samples to phone. */
    OFF,
    /** Edge runtime computes HSI; raw samples also stream to phone. Phone HSI
     *  is canonical; edge envelopes carry `session_role: shadow`. */
    SHADOW,
    /** Edge HSI is product-of-record. Raw samples are not streamed. */
    CANONICAL;

    /** Wire form for the native runtime JSON (snake_case lowercased name). */
    fun toWire(): String = name.lowercase()

    companion object {
        /** Parse the wire form used in the FFI config JSON + pairing advert. */
        fun fromWire(s: String): EdgeMode? = when (s.lowercase()) {
            "off" -> OFF
            "shadow" -> SHADOW
            "canonical" -> CANONICAL
            else -> null
        }
    }
}

data class SessionConfig(
    val sessionId: String,
    val mode: String,
    val durationSec: Int,
    val profile: ComputeProfile = ComputeProfile(),
    val windowLabel: String? = null,
    val origin: SessionOrigin = SessionOrigin.PHONE,
    val kind: SessionKind = SessionKind.FOCUS,
) {
    /** Delivery mode derived from origin. */
    val deliveryMode: DeliveryMode
        get() = if (origin == SessionOrigin.PHONE) DeliveryMode.REALTIME else DeliveryMode.PASSIVE_SYNC

    companion object {
        /** Parse from phone command (Mode A — phone-initiated). */
        fun fromPhoneCommand(json: JSONObject): SessionConfig {
            val mode = json.getString("mode")
            val profile = if (json.has("profile")) {
                ComputeProfile.fromJson(json.getJSONObject("profile"))
            } else {
                ComputeProfile()
            }
            val kindStr = json.optString("kind", "FOCUS").uppercase()
            return SessionConfig(
                sessionId = json.getString("session_id"),
                mode = mode,
                durationSec = json.getInt("duration_sec"),
                profile = profile,
                windowLabel = if (json.has("window_label")) json.getString("window_label") else null,
                origin = SessionOrigin.PHONE,
                kind = try { SessionKind.valueOf(kindStr) } catch (_: Exception) { SessionKind.FOCUS },
            )
        }
    }
}
