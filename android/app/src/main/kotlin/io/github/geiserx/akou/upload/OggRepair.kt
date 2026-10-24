// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import io.github.geiserx.akou.core.ogg.OggCRC
import io.github.geiserx.akou.core.ogg.OggPage
import java.io.File
import java.io.RandomAccessFile

/**
 * Makes a recording whose writer died (the process was killed mid-recording) a whole Ogg Opus
 * stream again: the torn page at the end is cut off, and the last whole page gets the
 * end-of-stream flag and the pre-skip in its granule, as the writer's own last page carries
 * (audio pages count packets x 960 with no pre-skip, the end page preSkip + packets x 960), with
 * its CRC recomputed. Nothing else in the file changes.
 */
object OggRepair {
    sealed interface Result {
        /** A whole stream already (its last page ends the stream): nothing changed. */
        data object Intact : Result

        /** [droppedBytes] of a torn page were cut off; [endMarked] when the end-of-stream flag was set. */
        data class Repaired(val droppedBytes: Long, val endMarked: Boolean) : Result

        /** One of ours with no whole audio page: there is nothing in it to send. */
        data object NoAudio : Result

        /** Not an Ogg Opus stream: left exactly as it is. */
        data object NotARecording : Result
    }

    private const val FLAGS_OFFSET = 5
    private const val GRANULE_OFFSET = 6
    private const val PRE_SKIP_OFFSET = 10 // in OpusHead, RFC 7845 section 5.1
    private const val CRC_OFFSET = 22
    private val CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)

    fun repair(file: File): Result {
        val data = file.readBytes()
        val starts = mutableListOf<Int>()
        val pages = mutableListOf<OggPage>()
        var end = 0
        while (end < data.size) {
            val read = try {
                OggPage.read(data, end)
            } catch (_: OggPage.Error) {
                break // a torn or damaged page: the stream ends at the last whole one
            }
            // Another stream after ours is not ours to send.
            if (pages.isNotEmpty() && read.page.serial != pages[0].serial) break
            starts += end
            pages += read.page
            end += read.length
        }
        val head = pages.firstOrNull()?.packets?.singleOrNull()
        if (head == null || !head.startsWith(OPUS_HEAD)) {
            // The writer puts OpusHead first, at once; an empty file or a torn first page is ours, cut short.
            val tornStart = data.size < CAPTURE.size && CAPTURE.copyOf(data.size).contentEquals(data)
            return if (pages.isEmpty() && (tornStart || data.startsWith(CAPTURE))) Result.NoAudio else Result.NotARecording
        }
        val tags = pages.getOrNull(1)?.packets?.singleOrNull()
        if (tags == null || !tags.startsWith(OPUS_TAGS) || pages.size < 3) return Result.NoAudio

        val dropped = (data.size - end).toLong()
        val lastStart = starts.last()
        val markEnd = OggPage.Flags.END_OF_STREAM !in pages.last().flags
        if (dropped == 0L && !markEnd) return Result.Intact
        RandomAccessFile(file, "rw").use { raf ->
            if (dropped > 0) raf.setLength(end.toLong())
            if (markEnd) {
                val page = data.copyOfRange(lastStart, end)
                page[FLAGS_OFFSET] = (page[FLAGS_OFFSET].toInt() or OggPage.Flags.END_OF_STREAM.bits).toByte()
                val granule = pages.last().granulePosition
                if (granule >= 0) {
                    val preSkip = (head[PRE_SKIP_OFFSET].toInt() and 0xFF) or ((head[PRE_SKIP_OFFSET + 1].toInt() and 0xFF) shl 8)
                    val ended = granule + preSkip
                    for (i in 0 until 8) page[GRANULE_OFFSET + i] = (ended ushr (8 * i)).toByte()
                }
                for (i in CRC_OFFSET until CRC_OFFSET + 4) page[i] = 0
                val crc = OggCRC.checksum(page).toInt()
                for (i in 0 until 4) page[CRC_OFFSET + i] = (crc ushr (8 * i)).toByte()
                raf.seek(lastStart.toLong())
                raf.write(page, 0, OggPage.HEADER_LENGTH)
            }
            raf.fd.sync()
        }
        return Result.Repaired(dropped, markEnd)
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
