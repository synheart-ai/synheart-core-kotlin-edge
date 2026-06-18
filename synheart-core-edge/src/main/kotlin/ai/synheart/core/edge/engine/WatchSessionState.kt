// SPDX-License-Identifier: Apache-2.0
// Copyright (c) Synheart AI Inc. and contributors.

package ai.synheart.core.edge.engine

/**
 * Watch session state machine.
 *
 * `PAUSED` is a host-driven transient state for breath / interrupt / call /
 * any other UX moment the user wants to suspend the session without ending
 * it. Engine loops self-exit on the next tick when state != RUNNING; resume
 * restarts the loops and adjusts `startedAtMs` so elapsed-time accounting
 * skips the paused interval.
 */
enum class WatchSessionState {
    IDLE, STARTING, RUNNING, PAUSED, STOPPING, SYNCING, ERROR;

    /** Valid state transitions, including the host-driven pause/resume path. */
    fun canTransitionTo(next: WatchSessionState): Boolean = when (this to next) {
        IDLE to STARTING,
        STARTING to RUNNING,
        STARTING to ERROR,
        RUNNING to PAUSED,
        RUNNING to STOPPING,
        RUNNING to SYNCING,
        RUNNING to ERROR,
        PAUSED to RUNNING,
        PAUSED to STOPPING,
        STOPPING to IDLE,
        STOPPING to SYNCING,
        SYNCING to IDLE,
        ERROR to IDLE -> true
        else -> false
    }
}
