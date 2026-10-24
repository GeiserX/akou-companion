// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.LiveSession
import io.github.geiserx.akou.core.client.LiveText
import io.github.geiserx.akou.core.client.LiveTranscript
import io.github.geiserx.akou.core.client.ServerProbe
import io.github.geiserx.akou.core.client.UploadQueue
import io.github.geiserx.akou.core.protocol.Job
import io.github.geiserx.akou.opus.LibopusEncoder
import io.github.geiserx.akou.recorder.AudioCapture
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
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
 * With `-e akouE2eMic true` instead of `akouE2ePcm` it records from the real microphone through
 * [AudioCapture]; on the emulator that hears speech only when the host feeds it (the emulator's
 * gRPC `injectAudio`, docs/ANDROID.md section 4).
 *
 * Besides the assertions it logs the A1 measurements: each live token's delay behind the audio
 * against the engine's `tier_ms`, the encoder's bitrate, the file's pre-skip, its seconds against
 * the delivered samples, and the file's path for ffprobe.
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
        val mic = args.getString("akouE2eMic") == "true"
        assumeTrue(
            "set akouE2eUrl, akouE2eKeyFile and akouE2ePcm (or akouE2eMic true)",
            url != null && keyFile != null && (pcmFile != null || mic),
        )
        val key = File(keyFile!!).readText().trim()
        val pcm = if (mic) {
            null
        } else {
            File(pcmFile!!).readBytes().let { b ->
                FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            }
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
        var bitrate = 0
        var peak = 0f
        val delays = CopyOnWriteArrayList<Double>()
        var started = 0L
        val recorder = Recorder(
            scope = scope,
            directory = RecordingService.recordingsDirectory(context),
            hasPermission = { true },
            makeOutput = { f -> RecordingFile(f, LibopusEncoder().also { bitrate = it.bitrate }) },
            makeSource = { sink ->
                // The loudest sample, so a silenced or unfed microphone shows in the log.
                val loud: (FloatArray) -> Unit = { s -> for (x in s) if (abs(x) > peak) peak = abs(x); sink(s) }
                if (pcm == null) AudioCapture(loud) else LoopedPcm(pcm, loud)
            },
            makeLive = { t, headers, hello ->
                // How far behind the audio each token arrives: now minus the recording's start minus `t`.
                TimedLive(LiveSession(t.url, t.key, hello, headers)) { tokenT ->
                    delays += (System.currentTimeMillis() - started) / 1000.0 - tokenT
                }
            },
        )
        recorder.serverSupportsLive = true
        started = System.currentTimeMillis()
        withContext(Dispatchers.Main) { recorder.start(settings.recordTarget(), id) }
        // A host feeding the emulator's microphone waits for this line: injectAudio crashes the
        // emulator when no guest input is open.
        Log.i(TAG, "recording started")
        var engine: String? = null
        scope.launch {
            recorder.liveState.collect {
                Log.i(TAG, "live state $it at ${(System.currentTimeMillis() - started) / 1000.0} s")
                if (it is LiveSession.State.Live && engine == null) engine = it.engine
            }
        }

        // Live words arrive.
        withTimeout(30_000) {
            var tick = 0
            while (recorder.transcript.value.let { it.items.isEmpty() && it.openLine == null }) {
                delay(100)
                if (++tick % 50 == 0) Log.i(TAG, "no words yet at ${tick / 10} s, peak sample $peak")
            }
        }
        val firstWords = (System.currentTimeMillis() - started) / 1000.0
        Log.i(TAG, "first live words after $firstWords s, peak sample so far $peak")

        // 30 s without network in the middle.
        delay(15_000)
        // Wi-Fi and data off as well, so the emulator has no network at all.
        val offAt = System.currentTimeMillis()
        for (c in listOf("cmd connectivity airplane-mode enable", "svc wifi disable", "svc data disable")) shell(c)
        delay(30_000)
        for (c in listOf("cmd connectivity airplane-mode disable", "svc wifi enable", "svc data enable")) shell(c)
        val onAt = System.currentTimeMillis()
        // The cut in the recording's own seconds; the gap marker must cover it, not just exist.
        val cut = (offAt - started) / 1000.0..(onAt - started) / 1000.0
        Log.i(TAG, "network off for ${(onAt - offAt) / 1000.0} s, recording seconds $cut")
        fun coversCut(g: LiveTranscript.Gap) = g.from < cut.endInclusive && g.to > cut.start
        withTimeout(90_000) {
            while (recorder.transcript.value.items.none { it is LiveTranscript.Gap && coversCut(it) }) delay(250)
        }
        delay(10_000)

        Log.i(TAG, "recording stopping")
        val finished = withContext(Dispatchers.Main) { recorder.stop() }!!
        scope.cancel()
        val gap = recorder.transcript.value.items.filterIsInstance<LiveTranscript.Gap>()
        Log.i(TAG, "file ${finished.seconds} s, delivered ${finished.deliveredSamples} samples, gaps $gap")
        Log.i(TAG, "file path ${finished.file.absolutePath}")
        Log.i(TAG, "engine $engine, peak sample $peak")
        val sorted = delays.sorted()
        if (sorted.isNotEmpty()) {
            fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
            Log.i(TAG, "token delay s: n ${sorted.size} min ${sorted.first()} p50 ${pct(0.5)} p90 ${pct(0.9)} max ${sorted.last()}")
        }
        val preSkip = opusHeadPreSkip(finished.file)
        Log.i(TAG, "encoder bitrate $bitrate, file preSkip $preSkip")
        assertTrue(gap.any(::coversCut), "no gap marker over the network cut $cut: $gap")
        assertEquals(finished.deliveredSamples / 16000.0, finished.seconds, 0.02 + 1e-9)
        assertEquals(24000, bitrate, "encoder bitrate")
        assertEquals(312, preSkip, "OpusHead pre-skip")

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
        val result = jobs.result(jobId)
        val text = result.text
        // The final pass fills the gap: text the live view never showed, timed inside the cut. Words
        // carry times only on some engines; segments always do. A segment counts only when it lies
        // wholly inside the cut, so one that merely overlaps it, with its words outside, does not.
        val wordsInCut = result.words.count { w -> w.s != null && w.s!! >= cut.start && w.s!! <= cut.endInclusive }
        val segmentsInCut = result.segments.filter { it.s >= cut.start && it.e <= cut.endInclusive && it.text.isNotBlank() }
        Log.i(
            TAG,
            "job $jobId done, ${text.split(' ').size} words in the final transcript, ${result.segments.size} segments, " +
                "$wordsInCut timed words and ${segmentsInCut.size} segments inside the cut, duration ${result.durationS}",
        )
        assertTrue(text.isNotBlank())
        assertTrue(wordsInCut > 0 || segmentsInCut.isNotEmpty(), "the final transcript has nothing inside the cut $cut")
        // Leave nothing behind on the server.
        runCatching { jobs.delete(jobId) }
        settings.keys.delete(id.toString())
    }

    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }

    /** The pre-skip field of the file's OpusHead: bytes 10 and 11, little-endian, of the first packet. */
    private fun opusHeadPreSkip(file: File): Int {
        val head = file.inputStream().use { it.readNBytes(256) }
        val at = String(head, Charsets.ISO_8859_1).indexOf("OpusHead")
        assertTrue(at >= 0, "no OpusHead in ${file.name}")
        return (head[at + 10].toInt() and 0xff) or ((head[at + 11].toInt() and 0xff) shl 8)
    }

    /** A [LiveText] that reports each token's `t` the moment its `words` event arrives. */
    private class TimedLive(private val inner: LiveText, private val onToken: (Double) -> Unit) : LiveText {
        override val events: Flow<LiveSession.Event> = inner.events.onEach { e ->
            if (e is LiveSession.Event.Words) e.words.tokens.forEach { onToken(it.t) }
        }

        override suspend fun start() = inner.start()

        override suspend fun send(page: ByteArray, endsAt: Double) = inner.send(page, endsAt)

        override suspend fun stop() = inner.stop()
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
