// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

/**
 * The CRC-32 an Ogg page header carries (RFC 3533 section 6): polynomial 0x04C11DB7,
 * initial value 0, no bit reflection and no final XOR. It is not the zlib CRC-32, so
 * `java.util.zip.CRC32` gives the wrong value and the server refuses the page.
 */
object OggCRC {
    private const val POLY = 0x04C1_1DB7

    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) {
            r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor POLY else r shl 1
        }
        r
    }

    fun checksum(bytes: ByteArray, seed: UInt = 0u): UInt {
        var crc = seed.toInt()
        for (b in bytes) {
            crc = (crc shl 8) xor table[((crc ushr 24) xor b.toInt()) and 0xFF]
        }
        return crc.toUInt()
    }
}
