// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Turns 20 ms frames into Ogg Opus pages: the two header pages first, then one page per
/// `packetsPerPage` packets (10, so 200 ms per page). Each returned page is appended to the
/// recording file and, while connected, sent as one binary WebSocket message.
///
/// Granule positions follow RFC 7845: the 48 kHz sample count at the end of the page's last packet,
/// pre-skip included; the last page's is trimmed to where the input ended. The server reads the
/// clock from them, so the phone sends no timestamps.
public struct OggOpusWriter {
    public static let defaultPacketsPerPage = 10

    public let serial: UInt32
    public let packetsPerPage: Int
    private let encoder: OpusEncoder
    private let vendor: String
    private var sequence: UInt32 = 0
    private var pending: [Data] = []
    private var packetsEncoded: Int64 = 0
    private var started = false
    private var finished = false

    public enum Failure: Error, Equatable {
        case headersNotWritten
        case alreadyFinished
    }

    public init(
        encoder: OpusEncoder,
        serial: UInt32 = UInt32.random(in: .min ... .max),
        packetsPerPage: Int = OggOpusWriter.defaultPacketsPerPage,
        vendor: String = "akou-companion"
    ) {
        precondition(packetsPerPage > 0 && packetsPerPage <= 255)
        self.encoder = encoder
        self.serial = serial
        self.packetsPerPage = packetsPerPage
        self.vendor = vendor
    }

    /// The OpusHead page (beginning of stream) and the OpusTags page, in that order.
    public mutating func headerPages() throws -> [Data] {
        guard !finished else { throw Failure.alreadyFinished }
        let head = OpusHeaders.head(preSkip: encoder.preSkip48k, inputSampleRate: UInt32(OpusEncoder.sampleRate))
        let tags = OpusHeaders.tags(vendor: vendor)
        let pages = [
            try page(flags: .beginOfStream, granule: 0, packets: [head]),
            try page(flags: [], granule: 0, packets: [tags]),
        ]
        started = true
        return pages
    }

    /// Encodes one 20 ms frame. Returns a finished page every `packetsPerPage` frames, nil otherwise.
    public mutating func append(frame: [Float]) throws -> Data? {
        guard started else { throw Failure.headersNotWritten }
        guard !finished else { throw Failure.alreadyFinished }
        pending.append(try encoder.encode(frame))
        packetsEncoded += 1
        guard pending.count == packetsPerPage else { return nil }
        return try flush(flags: [])
    }

    /// Writes the last page, marked end of stream, with whatever packets are still pending.
    ///
    /// The encoder holds back its lookahead, so one frame of silence goes in after the audio to push
    /// the last real samples out; the page's granule then ends the stream exactly where the input
    /// ended (pre-skip plus the input's samples), which trims that padding again (RFC 7845 4.4).
    public mutating func finish() throws -> Data {
        guard started else { throw Failure.headersNotWritten }
        guard !finished else { throw Failure.alreadyFinished }
        let inputPackets = packetsEncoded
        var granule: Int64 = 0
        if inputPackets > 0 {
            pending.append(try encoder.encode([Float](repeating: 0, count: OpusEncoder.frameSamples)))
            granule = Int64(encoder.preSkip48k) + inputPackets * OpusEncoder.frameSamples48k
        }
        let last = try page(flags: .endOfStream, granule: granule, packets: pending)
        pending.removeAll()
        finished = true
        return last
    }

    /// Seconds of audio encoded so far.
    public var seconds: Double { Double(packetsEncoded * OpusEncoder.frameSamples48k) / 48000 }

    private mutating func flush(flags: OggPage.Flags) throws -> Data {
        let granule = packetsEncoded * OpusEncoder.frameSamples48k
        let out = try page(flags: flags, granule: granule, packets: pending)
        pending.removeAll(keepingCapacity: true)
        return out
    }

    private mutating func page(flags: OggPage.Flags, granule: Int64, packets: [Data]) throws -> Data {
        let p = OggPage(flags: flags, granulePosition: granule, serial: serial, sequence: sequence, packets: packets)
        sequence += 1
        return try p.encoded()
    }
}
