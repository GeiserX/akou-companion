// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of Tests/AkouKitTests/OggTests.swift. */
class OggTests {
    @Test
    fun testCRCCheckValue() {
        // CRC-32 with polynomial 0x04C11DB7, init 0, no reflection, no final XOR, over "123456789".
        assertEquals(0x89A1897Fu, OggCRC.checksum("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0u, OggCRC.checksum(ByteArray(0)))
        // Not in the Swift test: a byte at or above 0x80 is negative in Kotlin and must still index the table.
        assertEquals(0x690CE0EEu, OggCRC.checksum(byteArrayOf(0x80.toByte())))
    }
}
