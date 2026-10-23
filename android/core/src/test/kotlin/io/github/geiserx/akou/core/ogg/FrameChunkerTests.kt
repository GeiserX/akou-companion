// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of Tests/AkouKitTests/FrameChunkerTests.swift. */
class FrameChunkerTests {
    /** A ramp, so a sample out of place or lost shows as a wrong value. */
    private fun ramp(range: IntRange): FloatArray = FloatArray(range.count()) { (range.first + it).toFloat() }

    @Test
    fun testOddBufferSizesComeOutAsExactFramesInOrder() {
        val chunker = FrameChunker()
        val sizes = listOf(1, 319, 320, 321, 7, 1000, 13, 639, 2)
        var input = FloatArray(0)
        val frames = ArrayList<FloatArray>()
        var at = 0
        for (n in sizes) {
            val buffer = ramp(at until at + n)
            at += n
            input += buffer
            frames += chunker.push(buffer)
        }
        assertEquals(2622, at)
        assertEquals(2622 / 320, frames.size)
        assertTrue(frames.all { it.size == OpusEncoder.FRAME_SAMPLES })
        assertContentEquals(input.copyOfRange(0, frames.size * 320), frames.fold(FloatArray(0)) { acc, f -> acc + f })
        assertEquals(2622 % 320, chunker.carried)

        val last = assertNotNull(chunker.flush())
        assertEquals(320, last.size)
        assertContentEquals(input.copyOfRange(input.size - 62, input.size), last.copyOfRange(0, 62))
        assertContentEquals(FloatArray(258), last.copyOfRange(62, 320))
        assertNull(chunker.flush(), "flush hands out the remainder once")
        assertEquals(0, chunker.carried)
    }

    @Test
    fun testABufferOfManyFramesAndAnEmptyOne() {
        val chunker = FrameChunker()
        assertEquals(0, chunker.push(FloatArray(0)).size)
        val frames = chunker.push(ramp(0 until 960))
        assertEquals(3, frames.size)
        assertContentEquals(ramp(0 until 320), frames[0])
        assertContentEquals(ramp(320 until 640), frames[1])
        assertContentEquals(ramp(640 until 960), frames[2])
        assertNull(chunker.flush(), "nothing carried, nothing to pad")
    }

    @Test
    fun testFramesFeedTheWriter() {
        val chunker = FrameChunker()
        val writer = OggOpusWriter(FixtureOpusEncoder(), serial = 7u)
        writer.headerPages()
        var pages = 0
        // 1 s in 100 ms buffers of 1600 samples: 50 frames, 5 pages of 10.
        for (i in 0 until 10) {
            val buffer = ramp(i * 1600 until (i + 1) * 1600).map { sin(it / 10) * 0.2f }.toFloatArray()
            for (frame in chunker.push(buffer)) {
                if (writer.append(frame) != null) pages += 1
            }
        }
        assertEquals(5, pages)
        assertEquals(1.0, writer.seconds, 1e-9)
    }

    /** Not in Swift: a frame handed out is not changed by later pushes (the carry buffer is reused). */
    @Test
    fun testAFrameHandedOutIsNotOverwrittenByTheNextPush() {
        val chunker = FrameChunker()
        chunker.push(ramp(0 until 100))
        val first = chunker.push(ramp(100 until 400)).single()
        chunker.push(ramp(400 until 1000))
        assertContentEquals(ramp(0 until 320), first)
    }
}
