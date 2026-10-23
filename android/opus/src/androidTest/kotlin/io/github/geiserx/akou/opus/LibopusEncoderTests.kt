// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.opus

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import io.github.geiserx.akou.core.ogg.OggOpusWriter
import io.github.geiserx.akou.core.ogg.OggPage
import io.github.geiserx.akou.core.ogg.OpusEncoder
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The libopus checks of OggTests.swift (testEncoderSettings, testWriterProducesAWellFormedStream),
 * on a device, plus the pre-skip of 312 the server trims by. The round trip also hands its file to
 * the host, where `:opus:ffprobeEncodedStream` has ffprobe read it as one second of mono Opus.
 */
@RunWith(AndroidJUnit4::class)
class LibopusEncoderTests {
    private fun sine(n: Int): FloatArray = FloatArray(OpusEncoder.FRAME_SAMPLES) { i ->
        val s = (n * OpusEncoder.FRAME_SAMPLES + i).toDouble()
        (0.3 * sin(2 * PI * 440 * s / 16000)).toFloat()
    }

    @Test
    fun encoderSettings() {
        LibopusEncoder().use { enc ->
            assertEquals(24000, enc.bitrate)
            assertTrue(enc.lookahead > 0, "lookahead ${enc.lookahead}")
            assertEquals(enc.lookahead * 3, enc.preSkip48k)
            // The value the server trims by, pinned: a libopus or settings change that moves it shows here.
            assertEquals(312, enc.preSkip48k)
            val e = assertFailsWith<LibopusEncoder.Failure> { enc.encode(FloatArray(319)) }
            assertEquals(-1, e.code)
            assertFailsWith<LibopusEncoder.Failure> { enc.encode(FloatArray(321)) }
        }
    }

    @Test
    fun oneSecondOfToneIsAbout24kbps() {
        LibopusEncoder().use { enc ->
            val packets = (0 until 50).map { enc.encode(sine(it)) }
            assertTrue(packets.all { it.isNotEmpty() && it.size <= LibopusEncoder.MAX_PACKET_BYTES })
            // About 24 kbit/s: 60 bytes per 20 ms packet, with room for VBR.
            val bytes = packets.sumOf { it.size }
            assertTrue(bytes in 1500..6000, "audio bytes $bytes")
        }
    }

    @Test
    fun writerRoundTripWithTheRealEncoder() {
        val file = LibopusEncoder().use { enc ->
            val writer = OggOpusWriter(enc, serial = 0x1234_5678u)
            val out = ArrayList<ByteArray>()
            out += writer.headerPages()
            for (n in 0 until 50) writer.append(sine(n))?.let { out += it }
            out += writer.finish()
            out.fold(ByteArray(0)) { acc, p -> acc + p }
        }
        val pages = OggPage.readAll(file)
        assertEquals(8, pages.size)
        assertEquals(listOf(9600L, 19200L, 28800L, 38400L, 48000L), pages.subList(2, 7).map { it.granulePosition })
        assertEquals(OggPage.Flags.END_OF_STREAM, pages[7].flags)
        assertEquals(312L + 48000L, pages[7].granulePosition)
        TestStorage().openOutputFile(STREAM_NAME).use { it.write(file) }
    }

    @Test
    fun closedEncoderRefusesToEncode() {
        val enc = LibopusEncoder()
        enc.encode(sine(0))
        enc.close()
        enc.close()
        assertFailsWith<IllegalStateException> { enc.encode(sine(1)) }
    }

    companion object {
        /** The name `:opus:ffprobeEncodedStream` looks for in the pulled test outputs. */
        const val STREAM_NAME = "one-second-440hz.opus"
    }
}
