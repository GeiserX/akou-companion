// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import io.github.geiserx.akou.core.client.UploadQueue
import java.time.Duration
import java.time.Instant

/**
 * When WorkManager runs a recording's upload work, from the queue's own state. The queue's
 * backoff is the schedule (5 s doubling to 15 min, or the server's Retry-After); WorkManager's own
 * backoff is never used. [UploadQueue.Item.notBefore] is the earliest time of the next try, so the
 * work is enqueued with an initial delay of at least that long, and Doze may run it later.
 */
object UploadSchedule {
    /** The shortest initial delay we ask for; a wait shorter than this is rounded up (an earliest time stays earliest). */
    val MIN_DELAY: Duration = Duration.ofSeconds(10)

    sealed interface When {
        /** Due now: expedited, so a phone with a network uploads at once. */
        data object Now : When

        /** Not before [delay] from now. */
        data class After(val delay: Duration) : When
    }

    /**
     * For a trigger from outside the work (a recording stopped, the start-up sweep, a changed
     * setting): every item that still has a request to make gets work, due now or after its wait.
     */
    fun first(item: UploadQueue.Item, now: Instant): When? {
        if (!hasWork(item)) return null
        return waitFor(item, now) ?: When.Now
    }

    /**
     * After the work's own pass: only a wait the queue set brings it back. An item still due after
     * a pass found no server ready for it (no URL or no key yet); it waits for the settings to
     * change instead of running again at once.
     */
    fun next(item: UploadQueue.Item, now: Instant): When? = if (hasWork(item)) waitFor(item, now) else null

    private fun hasWork(item: UploadQueue.Item) =
        item.state == UploadQueue.State.Pending || item.state is UploadQueue.State.Submitted

    private fun waitFor(item: UploadQueue.Item, now: Instant): When.After? {
        val at = item.notBefore?.takeIf { it.isAfter(now) } ?: return null
        val wait = Duration.between(now, at)
        return When.After(if (wait < MIN_DELAY) MIN_DELAY else wait)
    }
}
