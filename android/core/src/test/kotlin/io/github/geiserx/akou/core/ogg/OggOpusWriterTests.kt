// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Port of the OggOpusWriterTests class in Tests/AkouKitTests/OggTests.swift, with
 * [FixtureOpusEncoder] in place of libopus. testEncoderSettings and the 1500 to 6000 audio bytes
 * check of testWriterProducesAWellFormedStream need the real encoder; they are in `opus`.
 */
class OggOpusWriterTests {
    companion object {
        /** The frame size the server accepts for one binary message (PROTOCOL.md, read as 64000 bytes). */
        const val MAX_FRAME_BYTES = 64_000

        fun sine(frame: Int): FloatArray = FloatArray(OpusEncoder.FRAME_SAMPLES) { i ->
            val s = (frame * OpusEncoder.FRAME_SAMPLES + i).toDouble()
            (0.3 * sin(2 * PI * 440 * s / 16000)).toFloat()
        }

        /** 1 s of audio through the writer: what the phone appends to its file. */
        fun writeOneSecond(serial: UInt = 0x1234_5678u, encoder: OpusEncoder = FixtureOpusEncoder()): Pair<ByteArray, List<ByteArray>> {
            val writer = OggOpusWriter(encoder, serial)
            val pages = ArrayList<ByteArray>()
            pages += writer.headerPages()
            for (n in 0 until 50) writer.append(sine(n))?.let { pages += it }
            pages += writer.finish()
            return pages.fold(ByteArray(0)) { acc, p -> acc + p } to pages
        }

        fun assertFitsOneFrame(page: ByteArray) {
            assertTrue(page.size <= MAX_FRAME_BYTES, "page of ${page.size} bytes is over the $MAX_FRAME_BYTES-byte frame limit")
        }

        /**
         * ffprobe from PATH or the usual install places. On CI (CI=true) a missing ffprobe fails the
         * test: a skip there would report green without the only independent decoder check.
         */
        fun locateFfprobe(
            searchDirs: List<String> = (System.getenv("PATH") ?: "").split(File.pathSeparator) +
                listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"),
            onCI: Boolean = System.getenv("CI") == "true",
        ): String {
            val found = searchDirs.filter { it.isNotEmpty() }.map { File(it, "ffprobe") }.firstOrNull { it.canExecute() }
            if (found != null) return found.path
            if (onCI) throw AssertionError("ffprobe not found and CI=true: the parity test must run on CI")
            Assume.assumeTrue("ffprobe not installed", false)
            error("unreachable")
        }
    }

    @Test
    fun testWriterProducesAWellFormedStream() {
        val (file, _) = writeOneSecond()
        val pages = OggPage.readAll(file)
        // OpusHead, OpusTags, 5 pages of 10 packets, then the end-of-stream page holding the one
        // frame of silence that flushes the encoder's lookahead.
        assertEquals(8, pages.size)
        assertEquals(setOf(0x1234_5678u), pages.map { it.serial }.toSet())
        assertEquals((0u until 8u).toList(), pages.map { it.sequence })
        assertEquals(OggPage.Flags.BEGIN_OF_STREAM, pages[0].flags)
        assertContentEquals(OpusHeaders.head(preSkip = 312, inputSampleRate = 16000), pages[0].packets.single())
        assertTrue(pages[1].packets[0].copyOfRange(0, 8).contentEquals("OpusTags".toByteArray(Charsets.US_ASCII)))
        assertEquals(listOf(9600L, 19200L, 28800L, 38400L, 48000L), pages.subList(2, 7).map { it.granulePosition })
        assertEquals(listOf(10, 10, 10, 10, 10), pages.subList(2, 7).map { it.packets.size })
        assertEquals(OggPage.Flags.END_OF_STREAM, pages[7].flags)
        assertEquals(1, pages[7].packets.size)
        // Trimmed to the input: pre-skip plus exactly one second.
        assertEquals(312L + 48000L, pages[7].granulePosition)
    }

    /** Not in Swift: with ffmpeg's own packets and serial 0, every page but OpusTags is ffmpeg's. */
    @Test
    fun testWriterRebuildsTheFfmpegFileExceptTheTagsPage() {
        val reference = OggPage.readAll(Fixtures.ffmpegFile())
        val (file, _) = writeOneSecond(serial = 0u)
        val ours = OggPage.readAll(file)
        assertEquals(reference.size, ours.size)
        for (i in ours.indices) {
            if (i == 1) continue // ffmpeg writes its own vendor and an encoder comment
            assertContentEquals(reference[i].encoded(), ours[i].encoded(), "page $i")
        }
        assertContentEquals(OpusHeaders.tags("akou-companion"), ours[1].packets.single())
    }

    @Test
    fun testHeaderPagesAreTheSameBytesEveryCall() {
        val w = OggOpusWriter(FixtureOpusEncoder(), serial = 9u)
        val first = w.headerPages()
        val page = assertNotNull((0 until 10).firstNotNullOfOrNull { w.append(sine(it)) })
        // A reconnect asks again: same two pages, and the stream's next page keeps sequence 3.
        val again = w.headerPages()
        assertEquals(first.size, again.size)
        first.zip(again).forEach { (a, b) -> assertContentEquals(a, b) }
        assertEquals(2u, OggPage.read(page).page.sequence)
        val next = assertNotNull((10 until 20).firstNotNullOfOrNull { w.append(sine(it)) })
        assertEquals(3u, OggPage.read(next).page.sequence)
        // Not in Swift: a caller changing the returned bytes does not change the cache.
        again[0][0] = 0
        assertContentEquals(first[0], w.headerPages()[0])
    }

    @Test
    fun testAppendBeforeHeadersAndAfterFinishAreRefused() {
        val w = OggOpusWriter(FixtureOpusEncoder())
        assertFailsWith<OggOpusWriter.Failure.HeadersNotWritten> { w.append(sine(0)) }
        assertFailsWith<OggOpusWriter.Failure.HeadersNotWritten> { w.finish() }
        w.headerPages()
        w.finish()
        assertFailsWith<OggOpusWriter.Failure.AlreadyFinished> { w.append(sine(0)) }
        assertFailsWith<OggOpusWriter.Failure.AlreadyFinished> { w.finish() }
    }

    @Test
    fun testNoWrittenPageExceedsTheFrameLimit() {
        writeOneSecond().second.forEach(::assertFitsOneFrame)
        // The worst the real encoder can hand out is its 1500-byte output buffer per packet.
        val largest = object : OpusEncoder {
            override val lookahead = 104
            override fun encode(frame: FloatArray) = ByteArray(1500)
        }
        writeOneSecond(encoder = largest).second.forEach(::assertFitsOneFrame)
        // Positive control: the largest page Ogg can carry (255 segments of 255) is over the limit.
        val biggest = OggPage(granulePosition = 0, serial = 0u, sequence = 0u, packets = List(255) { ByteArray(254) } ).encoded()
        assertEquals(27 + 255 + 255 * 254, biggest.size)
        assertFailsWith<AssertionError> { assertFitsOneFrame(biggest) }
    }

    /**
     * The Swift test of the same name: PROTOCOL.md caps a frame at 64 KB and each page goes out as
     * one frame. Ogg's own page limit is not that guarantee (see testNoWrittenPageExceedsTheFrameLimit:
     * the largest page Ogg can carry is over 64000 bytes). The writer's budget is about 650 bytes a
     * page, so 30 s of audio must stay under
     * 2 KB a page; a change to packets per page that grows the pages shows up here. The fixture's
     * packets are replayed in a loop (they are real 24 kbit/s libopus packets).
     */
    @Test
    fun testNoPageIsLargerThanAWebSocketFrame() {
        val limit = 2 * 1024
        val fixture = FixtureOpusEncoder.fixturePackets()
        val writer = OggOpusWriter(FixtureOpusEncoder(List(50 * 30 + 1) { fixture[it % fixture.size] }), 7u)
        val pages = ArrayList<ByteArray>()
        pages += writer.headerPages()
        for (n in 0 until 50 * 30) writer.append(sine(n))?.let { pages += it } // 30 seconds
        pages += writer.finish()
        assertEquals(2 + 150 + 1, pages.size)
        pages.forEachIndexed { i, page -> assertTrue(page.size <= limit, "page $i is ${page.size} bytes") }
        assertEquals(pages.size, OggPage.readAll(pages.fold(ByteArray(0)) { acc, p -> acc + p }).size)
    }

    /** An independent decoder reads the writer's file: ffprobe's demuxer and libopus. */
    @Test
    fun testFfprobeReadsTheWritersFile() {
        val ffprobe = locateFfprobe()
        val (file, _) = writeOneSecond()
        val ours = probe(ffprobe, file)
        assertTrue(ours.contains("codec_name=opus"), ours)
        assertTrue(ours.contains("channels=1"), ours)
        // ffprobe reports the last granule over 48 kHz. One second through our writer must end
        // exactly where one second through ffmpeg's own encoder and muxer ends (the fixture).
        val reference = probe(ffprobe, Fixtures.ffmpegFile())
        assertNotNull(duration(ours), ours)
        assertEquals(duration(reference), duration(ours))
        // The fake encoder replays the fixture, so the duration above matches by construction. Pin
        // the granule it comes from as well: pre-skip plus one 960-sample packet per input frame.
        // A real encoder's lookahead is checked on the device, in `opus`.
        assertEquals(312L + 50L * 960L, OggPage.readAll(file).last().granulePosition)
    }

    /** Positive control for the CI rule: with no ffprobe anywhere and CI=true, the test fails, never skips. */
    @Test
    fun testMissingFfprobeFailsOnCI() {
        val nowhere = listOf(File(System.getProperty("java.io.tmpdir"), "akou-no-ffprobe-here").path)
        assertFailsWith<AssertionError> { locateFfprobe(nowhere, onCI = true) }
        assertFailsWith<org.junit.AssumptionViolatedException> { locateFfprobe(nowhere, onCI = false) }
    }

    private fun duration(text: String): Double? =
        text.lineSequence().firstOrNull { it.startsWith("duration=") }?.removePrefix("duration=")?.toDoubleOrNull()

    private fun probe(ffprobe: String, file: ByteArray): String {
        val f = File.createTempFile("akou-writer-", ".opus")
        try {
            f.writeBytes(file)
            val p = ProcessBuilder(
                ffprobe, "-v", "error", "-show_entries", "stream=codec_name,channels:format=duration",
                "-of", "default=nw=1", f.path,
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), "ffprobe did not finish")
            assertEquals(0, p.exitValue(), out)
            return out
        } finally {
            f.delete()
        }
    }
}
