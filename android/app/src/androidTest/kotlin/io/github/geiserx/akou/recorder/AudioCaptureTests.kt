// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.geiserx.akou.core.ogg.OggPage
import io.github.geiserx.akou.opus.LibopusEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real microphone path: AudioRecord on its own thread, into RecordingFile with libopus. On an
 * emulator the microphone may give silence, so this asserts that frames flow, pages are written and
 * the file is a valid Ogg Opus stream, never anything about the audio itself.
 */
@RunWith(AndroidJUnit4::class)
class AudioCaptureTests {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantMicrophone() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun theMicrophoneFillsAValidOggOpusFile(): Unit = runBlocking {
        val file = File(context.filesDir, "capture-test.opus").apply { delete() }
        val rf = RecordingFile(file, LibopusEncoder())
        var calls = 0
        var callThread: Thread? = null
        val capture = AudioCapture { samples ->
            calls += 1
            callThread = Thread.currentThread()
            rf.append(samples)
        }
        capture.start()
        Thread.sleep(2_000)
        capture.stop()
        val delivered = capture.deliveredSamples
        val finish = rf.finish()

        assertNull(finish.error)
        assertTrue(capture.captureRate == 16000 || capture.captureRate == 48000, "rate ${capture.captureRate}")
        // About two seconds of 16 kHz samples, with room for a slow start.
        assertTrue(delivered in 8_000..40_000, "delivered $delivered samples")
        assertTrue(calls > 0)
        assertTrue(callThread != null && callThread !== android.os.Looper.getMainLooper().thread, "the sink runs off the main thread")

        val pages = OggPage.readAll(file.readBytes())
        // Two header pages, at least one 200 ms audio page, and the end of stream.
        assertTrue(pages.size >= 4, "${pages.size} pages")
        assertEquals(OggPage.Flags.BEGIN_OF_STREAM, pages.first().flags)
        assertEquals(OggPage.Flags.END_OF_STREAM, pages.last().flags)
        assertEquals((0 until pages.size).map { it.toUInt() }, pages.map { it.sequence })
        assertEquals(1, pages.map { it.serial }.toSet().size)
        // The file's seconds are the delivered samples, rounded up to the padded last 20 ms frame.
        val samplesSeconds = delivered / 16000.0
        assertTrue(abs(finish.seconds - samplesSeconds) < 0.02 + 1e-9, "file ${finish.seconds} s, delivered $samplesSeconds s")
        assertEquals(312L + Math.round(finish.seconds * 48000), pages.last().granulePosition)
        file.delete()
    }
}
