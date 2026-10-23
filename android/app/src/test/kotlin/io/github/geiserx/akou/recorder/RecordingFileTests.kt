// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import io.github.geiserx.akou.core.ogg.OggPage
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingFileTests {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun headersAtOnceThenPagesThenEndOfStream() = runTest {
        val f = File(tmp.root, "a.opus")
        val enc = FakeEncoder()
        val rf = RecordingFile(f, enc)
        // The header pages are on disk before any audio.
        assertContentEquals(rf.headerPages[0] + rf.headerPages[1], f.readBytes())
        // Odd buffer sizes: no sample lost. 0.5 s plus 100 samples, the last frame padded.
        listOf(1000, 3000, 1, 4099).forEach { rf.append(FloatArray(it)) }
        val finish = rf.finish()
        assertNull(finish.error)
        // 8100 samples make 25 frames and a padded 26th (FrameChunker's rule): 0.52 s.
        assertEquals(0.52, finish.seconds, 1e-9)
        val pages = OggPage.readAll(f.readBytes())
        assertEquals(2 + 2 + 1, pages.size)
        assertEquals(OggPage.Flags.END_OF_STREAM, pages.last().flags)
        assertEquals(312L + 26 * 960, pages.last().granulePosition)
        val streamed = rf.pages.toList()
        assertEquals(listOf(0.2, 0.4, 0.52), streamed.map { it.end })
        assertContentEquals(pages.drop(2).flatMap { it.encoded().toList() }.toByteArray(), streamed.flatMap { it.data.toList() }.toByteArray())
        assertTrue(enc.frames == 27, "26 frames and the encoder's flushing frame, ${enc.frames}")
        // A second finish answers the same, late samples are dropped.
        rf.append(FloatArray(320))
        assertEquals(finish, rf.finish())
    }

    @Test
    fun anExistingFileIsNeverOverwritten() {
        val f = File(tmp.root, "b.opus").apply { writeText("x") }
        assertFailsWith<java.io.IOException> { RecordingFile(f, FakeEncoder()) }
        assertEquals("x", f.readText())
    }

    @Test
    fun anEncoderErrorStopsEncodingAndIsReportedAtFinish() = runTest {
        val f = File(tmp.root, "c.opus")
        val failing = object : io.github.geiserx.akou.core.ogg.OpusEncoder {
            var n = 0
            override val lookahead = 104
            override fun encode(frame: FloatArray): ByteArray {
                n += 1
                if (n == 12) throw IllegalStateException("boom")
                return byteArrayOf(1)
            }
        }
        val rf = RecordingFile(f, failing)
        repeat(20) { rf.append(FloatArray(320)) }
        val finish = rf.finish()
        assertTrue(finish.error!!.contains("boom"))
        // The first page (10 frames) is in the file; nothing is encoded after the error.
        assertEquals(3, OggPage.readAll(f.readBytes()).size)
        assertEquals(12, failing.n)
    }
}

class Downsampler48To16Tests {
    @Test
    fun keepsOneSampleInThreeWhateverTheBufferSizes() {
        val input = FloatArray(4800) { sin(2 * PI * 440 * it / 48000).toFloat() }
        val whole = Downsampler48To16().process(input)
        assertEquals(1600, whole.size)
        val d = Downsampler48To16()
        val pieces = listOf(1, 2, 7, 100, 1000, 3690).also { assertEquals(4800, it.sum()) }
        var at = 0
        val parts = pieces.flatMap { n -> d.process(input.copyOfRange(at, at + n)).toList().also { at += n } }
        assertContentEquals(whole.toList(), parts)
    }

    @Test
    fun passesSpeechAndStopsWhatWouldAlias() {
        fun gain(hz: Double): Double {
            val out = Downsampler48To16().process(FloatArray(48000) { sin(2 * PI * hz * it / 48000).toFloat() })
            val tail = out.copyOfRange(1000, out.size)
            return tail.maxOf { abs(it) }.toDouble()
        }
        assertEquals(1.0, Downsampler48To16().process(FloatArray(4800) { 1f }).last().toDouble(), 1e-4)
        assertTrue(gain(1000.0) > 0.95, "1 kHz passes")
        // 12 kHz would fold onto 4 kHz at 16 kHz.
        assertTrue(gain(12000.0) < 0.01, "12 kHz is stopped, gain ${gain(12000.0)}")
    }
}
