// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.UploadQueue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The queue's notBefore as WorkManager's initial delay: the queue's backoff is the schedule. */
class UploadScheduleTests {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = Instant.ofEpochSecond(1_800_000_000)

    private fun queue() = UploadQueue(
        UploadQueue.FileStore(File(tmp.root, "queue.json")),
        File(tmp.root, "Recordings").apply { mkdirs() },
        now = { clock },
        keepLocalCopy = { true },
    )

    private fun item(state: UploadQueue.State, notBefore: Instant? = null) =
        UploadQueue.Item(JobsClient.Submission(recordingId = "r"), "r.opus", state, notBefore = notBefore)

    /**
     * Every failure in a row, as the queue backs off: the delay WorkManager gets is the queue's
     * wait, 5 s doubling to 15 min, with the first one raised to WorkManager's 10 s.
     */
    @Test
    fun theInitialDelayFollowsTheQueuesBackoff() {
        val q = queue()
        q.enqueue(JobsClient.Submission(recordingId = "r"), "r.opus")
        assertEquals(UploadSchedule.When.Now, UploadSchedule.first(q.item("r")!!, clock))
        val delays = mutableListOf<Long>()
        repeat(10) {
            q.claimDue()
            q.uploadEnded("r", UploadQueue.Answer.Unreachable("offline"))
            val w = UploadSchedule.next(q.item("r")!!, clock) as UploadSchedule.When.After
            delays += w.delay.seconds
            clock = q.item("r")!!.notBefore!!
        }
        assertEquals(listOf<Long>(10, 10, 20, 40, 80, 160, 320, 640, 900, 900), delays)
    }

    @Test
    fun retryAfterSetsTheDelay() {
        val q = queue()
        q.enqueue(JobsClient.Submission(recordingId = "r"), "r.opus")
        q.claimDue()
        q.uploadEnded("r", UploadQueue.Answer.Http(429, okhttp3.Headers.headersOf("Retry-After", "120"), "{}".encodeToByteArray()))
        assertEquals(UploadSchedule.When.After(Duration.ofSeconds(120)), UploadSchedule.next(q.item("r")!!, clock))
        assertEquals(UploadSchedule.When.After(Duration.ofSeconds(120)), UploadSchedule.first(q.item("r")!!, clock))
    }

    @Test
    fun aWaitThatEndedIsDueNowFromOutsideAndNeedsNoNextRun() {
        val past = clock.minusSeconds(1)
        assertEquals(UploadSchedule.When.Now, UploadSchedule.first(item(UploadQueue.State.Pending, past), clock))
        assertEquals(UploadSchedule.When.Now, UploadSchedule.first(item(UploadQueue.State.Submitted("j")), clock))
        // Still due after a run: no server was ready for it, so it waits for the settings, never loops.
        assertNull(UploadSchedule.next(item(UploadQueue.State.Pending, past), clock))
        assertNull(UploadSchedule.next(item(UploadQueue.State.Pending), clock))
    }

    @Test
    fun aSubmittedRecordingWaitingForItsReadBackComesBackAfterItsWait() {
        val at = clock.plusSeconds(40)
        assertEquals(UploadSchedule.When.After(Duration.ofSeconds(40)), UploadSchedule.next(item(UploadQueue.State.Submitted("j"), at), clock))
    }

    @Test
    fun finishedParkedAndUploadingRecordingsGetNoWork() {
        val later = clock.plusSeconds(60)
        for (state in listOf(UploadQueue.State.Done("j", true), UploadQueue.State.Parked(404, "not_found"), UploadQueue.State.Uploading)) {
            assertNull(UploadSchedule.first(item(state, later), clock), "$state")
            assertNull(UploadSchedule.next(item(state, later), clock), "$state")
        }
    }
}
