// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.ServerBook
import io.github.geiserx.akou.core.client.ServerEntry
import io.github.geiserx.akou.core.client.UploadQueue
import io.github.geiserx.akou.settings.Settings
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real UploadWorker under WorkManager's test driver, against a fake akou on the phone itself:
 * a recording queued with no network waits; when the network comes, it uploads, backs off on a
 * 503 with the queue's own wait as the next run's initial delay, uploads again with the same
 * Idempotency-Key, is read back, and only then is the phone's copy deleted.
 */
@RunWith(AndroidJUnit4::class)
class UploadWorkTests {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = Settings(context)
    private val serverId = UUID.randomUUID()
    private val seen: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private var posts = 0
    private lateinit var server: MockWebServer
    private var savedBook: ServerBook? = null
    private var savedKeep = false

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.DEBUG).build(),
        )
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when (request.method) {
                    // The first upload meets a busy server; the second makes the job.
                    "POST" -> if (++posts == 1) {
                        MockResponse.Builder().code(503).body("""{"error":"busy"}""").build()
                    } else {
                        MockResponse.Builder().code(202).body("""{"id":"job-w","status":"queued","keep_audio":true}""").build()
                    }
                    else -> MockResponse.Builder().code(200).body("""{"id":"job-w","status":"done","keep_audio":true}""").build()
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        runBlocking {
            savedBook = settings.book()
            savedKeep = settings.keepLocalCopy()
            settings.save(ServerBook().upsert(ServerEntry(id = serverId, name = "test", url = "http://127.0.0.1:${server.port}")))
            settings.setKeepLocalCopy(false)
        }
        assertTrue(settings.keys.save("ak_work_test", serverId.toString()))
    }

    /** The recording the test queued in the installed app's own queue. */
    private var queuedId: String? = null

    @After
    fun tearDown() {
        queuedId?.let { id ->
            WorkManager.getInstance(context).cancelUniqueWork(Uploads.workName(id))
            Uploads.get(context).run {
                queue.item(id)?.let { queue.audioFile(it).delete() }
                queue.remove(id)
                refresh()
            }
        }
        server.close()
        settings.keys.delete(serverId.toString())
        runBlocking {
            settings.save(savedBook ?: ServerBook())
            settings.setKeepLocalCopy(savedKeep)
        }
    }

    private fun infos(id: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(Uploads.workName(id)).get()

    /** Waits until the recording's work has nothing running (CoroutineWorker runs off the test executor). */
    private fun settle(id: String) {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            if (infos(id).none { it.state == WorkInfo.State.RUNNING }) {
                Thread.sleep(200)
                if (infos(id).none { it.state == WorkInfo.State.RUNNING }) return
            }
            Thread.sleep(100)
        }
        throw AssertionError("the upload work did not finish: ${infos(id)}")
    }

    @Test
    fun aRecordingMadeOfflineUploadsWhenTheNetworkComesOnceWithTheSameKeyThenItsCopyIsDeleted() {
        val uploads = Uploads.get(context)
        val id = UUID.randomUUID().toString().also { queuedId = it }
        val file = File(uploads.queue.audioDirectory, "$id.opus").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        uploads.queue.enqueue(JobsClient.Submission(recordingId = id, language = "auto"), file.name, serverId)
        uploads.schedule(id)
        uploads.schedule(id) // a second trigger keeps the work already there
        val driver = assertNotNull(WorkManagerTestInitHelper.getTestDriver(context))

        // No network: the work waits and nothing reaches the server.
        val first = infos(id).single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        Thread.sleep(1_000)
        assertEquals(0, seen.size)

        // The network comes: one upload, a 503, and the next run waits the queue's 5 s, raised to 10 s.
        driver.setAllConstraintsMet(first.id)
        settle(id)
        assertEquals(1, posts)
        val waiting = uploads.queue.item(id)!!
        assertEquals(UploadQueue.State.Pending, waiting.state)
        assertEquals(1, waiting.attempts)
        val next = infos(id).single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(UploadSchedule.MIN_DELAY.toMillis(), next.initialDelayMillis)
        assertTrue(file.exists())

        // The wait ends (the driver skips WorkManager's delay, not the queue's earliest time): the
        // same key again, then the read-back, then the copy goes.
        val wait = Duration.between(Instant.now(), waiting.notBefore!!).toMillis()
        if (wait > 0) Thread.sleep(wait + 100)
        driver.setInitialDelayMet(next.id)
        driver.setAllConstraintsMet(next.id)
        settle(id)
        val sentPosts = seen.filter { it.method == "POST" }
        assertEquals(listOf(id, id), sentPosts.map { it.headers["Idempotency-Key"] })
        assertEquals(1, seen.count { it.method == "GET" && it.url.encodedPath == "/v1/jobs/job-w" })
        assertEquals(UploadQueue.State.Done("job-w", keptOnServer = true), uploads.queue.item(id)!!.state)
        assertFalse(file.exists(), "the copy is deleted after the read-back, with the setting off")

        // Done: no more work for it.
        assertTrue(infos(id).all { it.state.isFinished }, "${infos(id)}")
        assertEquals(3, seen.size)
    }
}
