// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// One Ogg page (RFC 3533). akou-companion writes one page per 200 ms of audio, and every page is
/// both appended to the recording file and sent as one binary WebSocket message, byte for byte.
///
/// Only whole packets are supported: a page written here never ends in a packet that continues on
/// the next page, which holds for Opus packets of a few hundred bytes.
public struct OggPage: Equatable, Sendable {
    public struct Flags: OptionSet, Sendable, Equatable {
        public let rawValue: UInt8
        public init(rawValue: UInt8) { self.rawValue = rawValue }
        /// The first packet continues one from the previous page (never written here).
        public static let continued = Flags(rawValue: 0x01)
        /// Beginning of stream: the first page of a logical stream.
        public static let beginOfStream = Flags(rawValue: 0x02)
        /// End of stream: the last page of a logical stream.
        public static let endOfStream = Flags(rawValue: 0x04)
    }

    public enum Error: Swift.Error, Equatable {
        case notAnOggPage
        case truncated
        case unsupportedVersion(UInt8)
        case badChecksum(expected: UInt32, actual: UInt32)
        case continuedPacket
        case tooManySegments(Int)
    }

    /// Fixed header length before the segment table.
    public static let headerLength = 27

    public var flags: Flags
    /// The granule position: for Ogg Opus, the 48 kHz sample count at the end of the last packet.
    public var granulePosition: Int64
    public var serial: UInt32
    public var sequence: UInt32
    public var packets: [Data]

    public init(flags: Flags = [], granulePosition: Int64, serial: UInt32, sequence: UInt32, packets: [Data]) {
        self.flags = flags
        self.granulePosition = granulePosition
        self.serial = serial
        self.sequence = sequence
        self.packets = packets
    }

    /// The lacing values for whole packets: a run of 255 per full 255 bytes, then the remainder,
    /// which is 0 when the length is a multiple of 255.
    static func lacing(for packets: [Data]) -> [UInt8] {
        var out: [UInt8] = []
        for p in packets {
            var n = p.count
            while n >= 255 {
                out.append(255)
                n -= 255
            }
            out.append(UInt8(n))
        }
        return out
    }

    /// The page as it goes into the file and onto the socket, checksum included.
    public func encoded() throws -> Data {
        let segments = Self.lacing(for: packets)
        guard segments.count <= 255 else { throw Error.tooManySegments(segments.count) }
        var d = Data(capacity: Self.headerLength + segments.count + packets.reduce(0) { $0 + $1.count })
        d.append(contentsOf: Array("OggS".utf8))
        d.append(0) // stream structure version
        d.append(flags.rawValue)
        d.appendLE(UInt64(bitPattern: granulePosition))
        d.appendLE(serial)
        d.appendLE(sequence)
        d.appendLE(UInt32(0)) // checksum, filled below
        d.append(UInt8(segments.count))
        d.append(contentsOf: segments)
        for p in packets { d.append(p) }
        let crc = OggCRC.checksum(d)
        d.withUnsafeMutableBytes { raw in
            raw.storeBytes(of: crc.littleEndian, toByteOffset: 22, as: UInt32.self)
        }
        return d
    }

    /// Reads one page starting at `offset` and checks its CRC. Returns the page and its length.
    public static func read(_ data: Data, at offset: Int = 0) throws -> (page: OggPage, length: Int) {
        let base = data.startIndex + offset
        guard data.count - offset >= headerLength else { throw Error.truncated }
        guard data[base..<base + 4].elementsEqual("OggS".utf8) else { throw Error.notAnOggPage }
        let version = data[base + 4]
        guard version == 0 else { throw Error.unsupportedVersion(version) }
        let flags = Flags(rawValue: data[base + 5])
        guard !flags.contains(.continued) else { throw Error.continuedPacket }
        let granule = Int64(bitPattern: data.readLE(UInt64.self, at: base + 6))
        let serial = data.readLE(UInt32.self, at: base + 14)
        let sequence = data.readLE(UInt32.self, at: base + 18)
        let stored = data.readLE(UInt32.self, at: base + 22)
        let nsegs = Int(data[base + 26])
        guard data.count - offset >= headerLength + nsegs else { throw Error.truncated }
        let table = Array(data[(base + headerLength)..<(base + headerLength + nsegs)])
        let bodyLength = table.reduce(0) { $0 + Int($1) }
        let length = headerLength + nsegs + bodyLength
        guard data.count - offset >= length else { throw Error.truncated }
        if let last = table.last, last == 255 { throw Error.continuedPacket }

        var zeroed = Data(data[base..<(base + length)])
        let crcField = zeroed.startIndex + 22
        zeroed.replaceSubrange(crcField..<(crcField + 4), with: [0, 0, 0, 0])
        let actual = OggCRC.checksum(zeroed)
        guard actual == stored else { throw Error.badChecksum(expected: stored, actual: actual) }

        var packets: [Data] = []
        var cursor = base + headerLength + nsegs
        var current = 0
        for v in table {
            current += Int(v)
            if v < 255 {
                packets.append(Data(data[cursor..<(cursor + current)]))
                cursor += current
                current = 0
            }
        }
        let page = OggPage(flags: flags, granulePosition: granule, serial: serial, sequence: sequence, packets: packets)
        return (page, length)
    }

    /// Reads every page of a whole Ogg file.
    public static func readAll(_ data: Data) throws -> [OggPage] {
        var pages: [OggPage] = []
        var offset = 0
        while offset < data.count {
            let (page, length) = try read(data, at: offset)
            pages.append(page)
            offset += length
        }
        return pages
    }
}

extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }

    func readLE<T: FixedWidthInteger>(_: T.Type, at index: Index) -> T {
        var v: T = 0
        for i in 0..<MemoryLayout<T>.size {
            v |= T(self[index + i]) << (8 * i)
        }
        return v
    }
}
