package com.sieve.queue.core

/**
 * What survives between launches of the one-time "bring the old queue back paused" migration (see
 * `QueueManager.rehydrate`). Builds up to v1.0.3 saved the queue but never restored it on a normal launch, so the
 * first restore must not start that old work on its own: it comes back paused, and stays paused until the user acts.
 */
data class RestoreHold(
    /** The migration has run. From then on a restore follows the normal rule (in-flight work comes back QUEUED), except for [heldIds]. */
    val migrated: Boolean = false,
    /** Rows the migration brought back paused. Each stays paused on every launch until the user resumes or cancels it. */
    val heldIds: Set<String> = emptySet(),
    /** The user closed the "Restored N unfinished items" banner; the rows themselves stay held. */
    val bannerDismissed: Boolean = false,
) {
    /**
     * This hold without the rows that no longer wait: a held row is PAUSED, so one that was resumed, cancelled or
     * removed is not held any more. Returns `this` itself when nothing changed.
     */
    fun stillHeldIn(state: QueueState): RestoreHold {
        if (heldIds.isEmpty()) return this
        val paused = state.jobs.filter { it.status == DownloadStatus.PAUSED }.mapTo(HashSet()) { it.id }
        val kept = heldIds.filterTo(HashSet()) { it in paused }
        return if (kept.size == heldIds.size) this else copy(heldIds = kept)
    }

    companion object {
        /** No migration pending and nothing held: a restore behaves as the plain one. */
        val SETTLED = RestoreHold(migrated = true)
    }
}

/**
 * The persistence seam for [RestoreHold], so `:queue` stays Android-free (the app backs it with its DataStore).
 * [save] writes the whole value at once. No Room schema change: this is a few preferences, not queue rows.
 */
interface RestoreHoldStore {
    suspend fun load(): RestoreHold
    suspend fun save(hold: RestoreHold)
}

/**
 * The default store: lives and dies with the process. It starts [RestoreHold.SETTLED], so a manager built
 * without a persistent store (tests, harnesses) restores exactly as it always did.
 */
class InMemoryRestoreHoldStore(initial: RestoreHold = RestoreHold.SETTLED) : RestoreHoldStore {
    @Volatile private var hold = initial
    override suspend fun load(): RestoreHold = hold
    override suspend fun save(hold: RestoreHold) { this.hold = hold }
}
