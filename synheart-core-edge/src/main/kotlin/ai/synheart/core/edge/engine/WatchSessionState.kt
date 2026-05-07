package ai.synheart.core.edge.engine

/** RFC §8.1 — Watch session state machine. */
enum class WatchSessionState {
    IDLE, STARTING, RUNNING, STOPPING, SYNCING, ERROR;

    /** Valid state transitions per RFC §8.1. */
    fun canTransitionTo(next: WatchSessionState): Boolean = when (this to next) {
        IDLE to STARTING,
        STARTING to RUNNING,
        STARTING to ERROR,
        RUNNING to STOPPING,
        RUNNING to SYNCING,
        RUNNING to ERROR,
        STOPPING to IDLE,
        STOPPING to SYNCING,
        SYNCING to IDLE,
        ERROR to IDLE -> true
        else -> false
    }
}
