// SPDX-License-Identifier: GPL-3.0-or-later

/// The CRC-32 an Ogg page header carries (RFC 3533 section 6): polynomial 0x04C11DB7,
/// initial value 0, no bit reflection and no final XOR. It is not the zlib CRC-32.
public enum OggCRC {
    private static let table: [UInt32] = (0..<256).map { i in
        var r = UInt32(i) << 24
        for _ in 0..<8 {
            r = (r & 0x8000_0000) != 0 ? (r << 1) ^ 0x04C1_1DB7 : r << 1
        }
        return r
    }

    public static func checksum<S: Sequence>(_ bytes: S, seed: UInt32 = 0) -> UInt32 where S.Element == UInt8 {
        var crc = seed
        for b in bytes {
            crc = (crc << 8) ^ table[Int(((crc >> 24) ^ UInt32(b)) & 0xFF)]
        }
        return crc
    }
}
