// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One Ogg page (RFC 3533). akou-companion writes one page per 200 ms of audio, and every page is
 * both appended to the recording file and sent as one binary WebSocket message, byte for byte.
 *
 * Only whole packets are supported: a page written here never ends in a packet that continues on
 * the next page, which holds for Opus packets of a few hundred bytes.
 */
class OggPage(
    val flags: Flags = Flags.NONE,
    /** The granule position: for Ogg Opus, the 48 kHz sample count at the end of the last packet. */
    val granulePosition: Long,
    val serial: UInt,
    val sequence: UInt,
    val packets: List<ByteArray>,
) {
    @JvmInline
    value class Flags(val bits: Int) {
        operator fun contains(other: Flags): Boolean = bits and other.bits == other.bits
        operator fun plus(other: Flags): Flags = Flags(bits or other.bits)
        override fun toString(): String = "Flags(0x%02x)".format(bits)

        companion object {
            val NONE = Flags(0)
            /** The first packet continues one from the previous page (never written here). */
            val CONTINUED = Flags(0x01)
            /** Beginning of stream: the first page of a logical stream. */
            val BEGIN_OF_STREAM = Flags(0x02)
            /** End of stream: the last page of a logical stream. */
            val END_OF_STREAM = Flags(0x04)
        }
    }

    sealed class Error(message: String) : Exception(message) {
        class NotAnOggPage : Error("not an Ogg page")
        class Truncated : Error("truncated Ogg page")
        class UnsupportedVersion(val version: Int) : Error("unsupported Ogg version $version")
        class BadChecksum(val expected: UInt, val actual: UInt) :
            Error("bad Ogg checksum: stored 0x%08x, computed 0x%08x".format(expected.toInt(), actual.toInt()))
        class ContinuedPacket : Error("continued packets are not supported")
        class TooManySegments(val count: Int) : Error("$count segments, at most 255 fit a page")
    }

    /** A page read from a buffer and how many bytes it took. */
    data class Read(val page: OggPage, val length: Int)

    /** The page as it goes into the file and onto the socket, checksum included. */
    fun encoded(): ByteArray {
        val segments = lacing(packets)
        if (segments.size > 255) throw Error.TooManySegments(segments.size)
        val size = HEADER_LENGTH + segments.size + packets.sumOf { it.size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(CAPTURE)
        b.put(0) // stream structure version
        b.put(flags.bits.toByte())
        b.putLong(granulePosition)
        b.putInt(serial.toInt())
        b.putInt(sequence.toInt())
        b.putInt(0) // checksum, filled below
        b.put(segments.size.toByte())
        b.put(segments)
        for (p in packets) b.put(p)
        val out = b.array()
        b.putInt(CRC_OFFSET, OggCRC.checksum(out).toInt())
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is OggPage &&
            flags == other.flags &&
            granulePosition == other.granulePosition &&
            serial == other.serial &&
            sequence == other.sequence &&
            packets.size == other.packets.size &&
            packets.indices.all { packets[it].contentEquals(other.packets[it]) }

    override fun hashCode(): Int {
        var h = flags.bits
        h = 31 * h + granulePosition.hashCode()
        h = 31 * h + serial.hashCode()
        h = 31 * h + sequence.hashCode()
        for (p in packets) h = 31 * h + p.contentHashCode()
        return h
    }

    override fun toString(): String =
        "OggPage(flags=$flags, granule=$granulePosition, serial=$serial, sequence=$sequence, packets=${packets.map { it.size }})"

    companion object {
        /** Fixed header length before the segment table. */
        const val HEADER_LENGTH = 27
        private const val CRC_OFFSET = 22
        private val CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)

        /**
         * The lacing values for whole packets: a run of 255 per full 255 bytes, then the remainder,
         * which is 0 when the length is a multiple of 255.
         */
        internal fun lacing(packets: List<ByteArray>): ByteArray {
            val out = ArrayList<Byte>()
            for (p in packets) {
                var n = p.size
                while (n >= 255) {
                    out.add(255.toByte())
                    n -= 255
                }
                out.add(n.toByte())
            }
            return out.toByteArray()
        }

        /** Reads one page starting at [offset] and checks its CRC. */
        fun read(data: ByteArray, offset: Int = 0): Read {
            if (offset < 0 || data.size - offset < HEADER_LENGTH) throw Error.Truncated()
            for (i in 0 until 4) if (data[offset + i] != CAPTURE[i]) throw Error.NotAnOggPage()
            val version = data[offset + 4].toInt() and 0xFF
            if (version != 0) throw Error.UnsupportedVersion(version)
            val flags = Flags(data[offset + 5].toInt() and 0xFF)
            if (Flags.CONTINUED in flags) throw Error.ContinuedPacket()
            val le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val granule = le.getLong(offset + 6)
            val serial = le.getInt(offset + 14).toUInt()
            val sequence = le.getInt(offset + 18).toUInt()
            val stored = le.getInt(offset + CRC_OFFSET).toUInt()
            val nsegs = data[offset + 26].toInt() and 0xFF
            if (data.size - offset < HEADER_LENGTH + nsegs) throw Error.Truncated()
            val tableStart = offset + HEADER_LENGTH
            var bodyLength = 0
            for (i in 0 until nsegs) bodyLength += data[tableStart + i].toInt() and 0xFF
            val length = HEADER_LENGTH + nsegs + bodyLength
            if (data.size - offset < length) throw Error.Truncated()
            if (nsegs > 0 && data[tableStart + nsegs - 1].toInt() and 0xFF == 255) throw Error.ContinuedPacket()

            val zeroed = data.copyOfRange(offset, offset + length)
            for (i in CRC_OFFSET until CRC_OFFSET + 4) zeroed[i] = 0
            val actual = OggCRC.checksum(zeroed)
            if (actual != stored) throw Error.BadChecksum(expected = stored, actual = actual)

            val packets = ArrayList<ByteArray>()
            var cursor = tableStart + nsegs
            var current = 0
            for (i in 0 until nsegs) {
                val v = data[tableStart + i].toInt() and 0xFF
                current += v
                if (v < 255) {
                    packets.add(data.copyOfRange(cursor, cursor + current))
                    cursor += current
                    current = 0
                }
            }
            return Read(OggPage(flags, granule, serial, sequence, packets), length)
        }

        /** Reads every page of a whole Ogg file. */
        fun readAll(data: ByteArray): List<OggPage> {
            val pages = ArrayList<OggPage>()
            var offset = 0
            while (offset < data.size) {
                val (page, length) = read(data, offset)
                pages.add(page)
                offset += length
            }
            return pages
        }
    }
}
