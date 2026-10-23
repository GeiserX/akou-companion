// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.LiveTranscript
import io.github.geiserx.akou.core.client.ServerProbe
import io.github.geiserx.akou.core.client.UploadQueue
import io.github.geiserx.akou.core.protocol.Job
import io.github.geiserx.akou.opus.LibopusEncoder
import io.github.geiserx.akou.recorder.Recorder
import io.github.geiserx.akou.recorder.RecordingFile
import io.github.geiserx.akou.recorder.RecordingService
import io.github.geiserx.akou.recorder.SampleSource
import io.github.geiserx.akou.settings.ServerForm
import io.github.geiserx.akou.settings.Settings
import io.github.geiserx.akou.upload.Uploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One recording through a real akou server, as App/Tests/EndToEndTests.swift does: live words, a
 * 30 s airplane-mode gap that shows as a gap marker, then the upload through the app's own
 * settings, queue and WorkManager worker, read back until the job is done.
 *
 * Left out of `connectedDebugAndroidTest` (app/build.gradle.kts); run it with `adb shell am
 * instrument -w -e class io.github.geiserx.akou.EndToEndTests -e akouE2eUrl ... -e akouE2eKeyFile
 * ... -e akouE2ePcm ... io.github.geiserx.akou.test/androidx.test.runner.AndroidJUnitRunner`. It
 * skips unless given its three instrumentation arguments: `akouE2eUrl` (a server with live text),
 * `akouE2eKeyFile` (a device path holding a bare `ak_` key with the jobs scope) and `akouE2ePcm` (a
 * device path holding speech as 16 kHz mono float32 little-endian, played in a loop in real time).
 * The microphone is not used: the emulator's is silent.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun recordWithLiveTextThroughAGapThenUpload(): Unit = runBlocking {
        val url = args.getString("akouE2eUrl")
        val keyFile = args.getString("akouE2eKeyFile")
        val pcmFile = args.getString("akouE2ePcm")
        assumeTrue("set akouE2eUrl, akouE2eKeyFile and akouE2ePcm", url != null && keyFile != null && pcmFile != null)
        val key = File(keyFile!!).readText().trim()
        val pcm = File(pcmFile!!).readBytes().let { b ->
            FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        }

        // The settings as the Settings screen saves them, so the worker finds the server and key.
        val settings = Settings(context)
        val id = UUID.randomUUID()
        val saved = ServerForm.save(settings.book(), id, url!!, key, null)
        settings.save(saved.book.copy(recordTo = id))
        assertTrue(settings.keys.save(key, id.toString()))
        val info = ServerProbe(url, key).server()
        assertTrue(info.supportsLive, "the e2e server must have live text")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = arrayOfNulls<LoopedPcm>(1)
        val recorder = Recorder(
            scope = scope,
            directory = RecordingService.recordingsDirectory(context),
            hasPermission = { true },
            makeOutput = { f -> RecordingFile(f, LibopusEncoder()) },
            makeSource = { sink -> LoopedPcm(pcm, sink).also { source[0] = it } },
        )
        recorder.serverSupportsLive = true
        val started = System.currentTimeMillis()
        withContext(Dispatchers.Main) { recorder.start(settings.recordTarget(), id) }

        // Live words arrive.
        withTimeout(20_000) { while (recorder.transcript.value.let { it.items.isEmpty() && it.openLine == null }) delay(100) }
        val firstWords = (System.currentTimeMillis() - started) / 1000.0
        Log.i(TAG, "first live words after $firstWords s")

        // 30 s without network in the middle.
        delay(15_000)
        // Wi-Fi and data off as well, so the emulator has no network at all.
        val offAt = System.currentTimeMillis()
        for (c in listOf("cmd connectivity airplane-mode enable", "svc wifi disable", "svc data disable")) shell(c)
        delay(30_000)
        for (c in listOf("cmd connectivity airplane-mode disable", "svc wifi enable", "svc data enable")) shell(c)
        Log.i(TAG, "network off for ${(System.currentTimeMillis() - offAt) / 1000.0} s")
        withTimeout(60_000) { while (recorder.transcript.value.items.none { it is LiveTranscript.Gap }) delay(250) }
        delay(10_000)

        val finished = withContext(Dispatchers.Main) { recorder.stop() }!!
        scope.cancel()
        val gap = recorder.transcript.value.items.filterIsInstance<LiveTranscript.Gap>()
        Log.i(TAG, "file ${finished.seconds} s, delivered ${finished.deliveredSamples} samples, gaps $gap")
        assertTrue(gap.isNotEmpty(), "no gap marker")
        assertEquals(finished.deliveredSamples / 16000.0, finished.seconds, 0.02 + 1e-9)

        // The upload, through the app's queue and its WorkManager worker.
        val uploads = Uploads.get(context)
        uploads.enqueue(finished)
        val jobId = withTimeout(120_000) {
            var j: String? = null
            while (j == null) {
                when (val s = uploads.queue.item(finished.id.toString())?.state) {
                    is UploadQueue.State.Submitted -> j = s.jobId
                    is UploadQueue.State.Parked -> error("upload parked: ${uploads.queue.item(finished.id.toString())?.lastError}")
                    else -> delay(500)
                }
            }
            j
        }
        val jobs = JobsClient(url, key)
        val job = withTimeout(600_000) {
            var job: Job? = null
            while (job?.ended != true) {
                // A long poll on a connection the network cut left half dead fails once; poll again.
                job = try {
                    jobs.job(jobId, wait = 30)
                } catch (e: IOException) {
                    Log.i(TAG, "job poll failed: ${e.message}")
                    delay(2_000)
                    job
                }
            }
            job!!
        }
        assertEquals("done", job.status, job.error)
        val text = jobs.result(jobId).text
        Log.i(TAG, "job $jobId done, ${text.split(' ').size} words in the final transcript")
        assertTrue(text.isNotBlank())
        // Leave nothing behind on the server.
        runCatching { jobs.delete(jobId) }
        settings.keys.delete(id.toString())
    }

    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }

    /** Speech from a file, looped, delivered in real time in 100 ms buffers on a thread of its own. */
    private class LoopedPcm(private val pcm: FloatArray, private val sink: (FloatArray) -> Unit) : SampleSource {
        @Volatile private var running = false
        private var thread: Thread? = null
        @Volatile override var deliveredSamples = 0L

        override fun start() {
            running = true
            thread = Thread {
                var at = 0
                val begin = System.nanoTime()
                while (running) {
                    val chunk = FloatArray(1600) { pcm[(at + it) % pcm.size] }
                    at = (at + 1600) % pcm.size
                    sink(chunk)
                    deliveredSamples += 1600
                    val due = begin + deliveredSamples * 1_000_000_000L / 16000
                    val wait = (due - System.nanoTime()) / 1_000_000
                    if (wait > 0) Thread.sleep(wait)
                }
            }.apply { start() }
        }

        override fun stop() {
            running = false
            thread?.join()
        }
    }

    private companion object {
        const val TAG = "EndToEndTests"
    }
}
