// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import io.github.geiserx.akou.core.ogg.OggPage
import io.github.geiserx.akou.opus.LibopusEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pause and resume through the real microphone, libopus and the recorder: one valid Ogg Opus
 * stream, with the granules running on across the pause, whose length is the recorded time without
 * the paused time. The file goes to the host through TestStorage, where `:app:ffprobePausedStream`
 * has ffprobe read it and compare its duration with the one this test writes beside it.
 */
@RunWith(AndroidJUnit4::class)
class PauseResumeFileTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aPausedRecordingIsOneContinuousStreamOfTheRecordedTime(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val dir = File(context.filesDir, "pause-test").apply { deleteRecursively(); mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val recorder = Recorder(
            scope = scope,
            directory = dir,
            hasPermission = { true },
            makeOutput = { f -> RecordingFile(f, LibopusEncoder()) },
            makeSource = { sink -> AudioCapture(context, sink) },
        )
        withContext(Dispatchers.Main) { recorder.start(null, null) }
        Thread.sleep(2_000)
        withContext(Dispatchers.Main) { recorder.pause() }
        val atPause = recorder.deliveredSamples
        Thread.sleep(1_500)
        val duringPause = recorder.deliveredSamples - atPause
        Log.i(TAG, "delivered before the pause ${atPause / 16000.0} s of 2 s")
        assertTrue(withContext(Dispatchers.Main) { recorder.resume() })
        Thread.sleep(2_000)
        // The recorded time as the chronometer shows it: now minus the base shifted by the pause.
        val (finished, chronometer) = withContext(Dispatchers.Main) {
            val base = (recorder.state.value as Recorder.State.Recording).startedAt
            val shown = (System.currentTimeMillis() - base) / 1000.0
            recorder.stop() to shown
        }
        val f = assertNotNull(finished)
        scope.cancel()

        assertEquals(0L, duringPause, "nothing is captured while paused")
        val bytes = f.file.readBytes()
        val pages = OggPage.readAll(bytes)
        assertEquals(OggPage.Flags.BEGIN_OF_STREAM, pages.first().flags)
        assertEquals(OggPage.Flags.END_OF_STREAM, pages.last().flags)
        assertEquals((0 until pages.size).map { it.toUInt() }, pages.map { it.sequence })
        assertEquals(1, pages.map { it.serial }.toSet().size)
        // Every audio page but the last carries ten 20 ms packets: 9600 more granules each, with no
        // jump where the pause was.
        val audio = pages.drop(2)
        val steps = audio.zipWithNext { a, b -> b.granulePosition - a.granulePosition }.dropLast(1)
        assertTrue(steps.all { it == 9600L }, "granule steps $steps")
        assertEquals(9600L, audio.first().granulePosition)

        val delivered = f.deliveredSamples / 16000.0
        val granuleSeconds = (pages.last().granulePosition - 312) / 48000.0
        Log.i(TAG, "file ${f.seconds} s, granules $granuleSeconds s, delivered $delivered s, chronometer $chronometer s")
        assertTrue(abs(f.seconds - delivered) < 0.02 + 1e-9, "file ${f.seconds} s, delivered $delivered s")
        assertEquals(f.seconds, granuleSeconds, 1e-9)
        // The recorded time is what the microphone delivered (checked above, and by ffprobe on the
        // host); the paused time added none of it. Against the chronometer (about 4 s of recording)
        // the file is never longer, so no paused time is in it; it can be shorter, because the
        // emulator's microphone runs slower than real time on a loaded host.
        assertTrue(f.seconds <= chronometer + 0.02, "file ${f.seconds} s, chronometer $chronometer s")

        val storage = TestStorage()
        storage.openOutputFile(STREAM_NAME).use { it.write(bytes) }
        // ffprobe on the host must read the same length as the file's own seconds.
        storage.openOutputFile("$STREAM_NAME.seconds").use { it.write(f.seconds.toString().toByteArray()) }
        dir.deleteRecursively()
    }

    private companion object {
        const val TAG = "PauseResumeFileTests"
        const val STREAM_NAME = "paused-recording.opus"
    }
}
