// SPDX-License-Identifier: GPL-3.0-or-later
import AkouOpus
import Foundation
import XCTest

final class OggTests: XCTestCase {
    /// An Ogg Opus file made by ffmpeg (its own Ogg muxer and CRC), 1 s of a 440 Hz sine at 16 kHz,
    /// libopus 24 kbit/s, 20 ms frames, 200 ms pages, bit-exact flags so the serial is 0.
    func ffmpegFile() throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "ffmpeg-1s-16k", withExtension: "opus", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }

    func testCRCCheckValue() {
        // CRC-32 with polynomial 0x04C11DB7, init 0, no reflection, no final XOR, over "123456789".
        XCTAssertEqual(OggCRC.checksum(Array("123456789".utf8)), 0x89A1_897F)
        XCTAssertEqual(OggCRC.checksum([UInt8]()), 0)
    }

    func testOpusHeadPageMatchesFfmpegByteForByte() throws {
        let file = try ffmpegFile()
        let head = OpusHeaders.head(preSkip: 312, inputSampleRate: 16000)
        let ours = try OggPage(flags: .beginOfStream, granulePosition: 0, serial: 0, sequence: 0, packets: [head]).encoded()
        XCTAssertEqual(ours.count, 47)
        XCTAssertEqual(ours, file.prefix(47))
    }

    func testEveryFfmpegPageReadsAndReencodesByteExact() throws {
        let file = try ffmpegFile()
        let pages = try OggPage.readAll(file)
        XCTAssertGreaterThan(pages.count, 5)
        XCTAssertEqual(pages.first?.flags, .beginOfStream)
        XCTAssertTrue(pages.last?.flags.contains(.endOfStream) ?? false)
        XCTAssertEqual(pages.map(\.sequence), (0..<UInt32(pages.count)).map { $0 })
        // 200 ms pages of 20 ms packets: 10 packets, granule steps of 9600 at 48 kHz.
        XCTAssertEqual(pages[2].packets.count, 10)
        XCTAssertEqual(pages[2].granulePosition, 9600)
        var rebuilt = Data()
        for p in pages { rebuilt.append(try p.encoded()) }
        XCTAssertEqual(rebuilt, file)
    }

    func testAFlippedByteFailsTheChecksum() throws {
        var file = try ffmpegFile()
        let (_, first) = try OggPage.read(file)
        let (_, second) = try OggPage.read(file, at: first)
        let audio = first + second
        XCTAssertNoThrow(try OggPage.read(file, at: audio))
        file[audio + 40] ^= 0x01
        XCTAssertThrowsError(try OggPage.read(file, at: audio)) { error in
            guard case OggPage.Error.badChecksum = error else { return XCTFail("got \(error)") }
        }
    }

    func testReadingFromASliceThatDoesNotStartAtZero() throws {
        let file = try ffmpegFile()
        let (_, first) = try OggPage.read(file)
        let slice = file[first...]
        XCTAssertNotEqual(slice.startIndex, 0)
        let (page, _) = try OggPage.read(slice)
        XCTAssertEqual(page.sequence, 1)
        XCTAssertTrue(page.packets[0].starts(with: Data("OpusTags".utf8)))
    }

    func testNotAnOggPage() {
        XCTAssertThrowsError(try OggPage.read(Data(repeating: 0x41, count: 64))) { error in
            XCTAssertEqual(error as? OggPage.Error, .notAnOggPage)
        }
    }

    func testLacingOfLongPackets() throws {
        let packets = [Data(repeating: 1, count: 255), Data(repeating: 2, count: 600), Data()]
        let page = OggPage(granulePosition: 1, serial: 7, sequence: 3, packets: packets)
        let encoded = try page.encoded()
        // 255 -> [255, 0]; 600 -> [255, 255, 90]; empty -> [0]
        XCTAssertEqual(Array(encoded[26..<33]), [6, 255, 0, 255, 255, 90, 0])
        XCTAssertEqual(try OggPage.read(encoded).page, page)
    }
}

final class OggOpusWriterTests: XCTestCase {
    static func sine(frame n: Int) -> [Float] {
        (0..<OpusEncoder.frameSamples).map { i in
            let s = Double(n * OpusEncoder.frameSamples + i)
            return Float(0.3 * sin(2 * Double.pi * 440 * s / 16000))
        }
    }

    /// 1 s of audio through the writer: what the phone appends to its file.
    static func writeOneSecond(serial: UInt32 = 0x1234_5678) throws -> (file: Data, encoder: OpusEncoder) {
        let encoder = try OpusEncoder()
        var writer = OggOpusWriter(encoder: encoder, serial: serial)
        var file = Data()
        for p in try writer.headerPages() { file.append(p) }
        for n in 0..<50 {
            if let page = try writer.append(frame: sine(frame: n)) { file.append(page) }
        }
        file.append(try writer.finish())
        return (file, encoder)
    }

    func testEncoderSettings() throws {
        let enc = try OpusEncoder()
        XCTAssertEqual(enc.bitrate, 24000)
        XCTAssertGreaterThan(enc.lookahead, 0)
        XCTAssertEqual(Int(enc.preSkip48k), enc.lookahead * 3)
        XCTAssertThrowsError(try enc.encode([Float](repeating: 0, count: 319)))
    }

    func testWriterProducesAWellFormedStream() throws {
        let (file, encoder) = try Self.writeOneSecond()
        let pages = try OggPage.readAll(file)
        // OpusHead, OpusTags, 5 pages of 10 packets, then the end-of-stream page holding the one
        // frame of silence that flushes the encoder's lookahead.
        XCTAssertEqual(pages.count, 8)
        XCTAssertEqual(Set(pages.map(\.serial)), [0x1234_5678])
        XCTAssertEqual(pages.map(\.sequence), Array(0..<8))
        XCTAssertEqual(pages[0].flags, .beginOfStream)
        XCTAssertEqual(pages[0].packets, [OpusHeaders.head(preSkip: encoder.preSkip48k, inputSampleRate: 16000)])
        XCTAssertTrue(pages[1].packets[0].starts(with: Data("OpusTags".utf8)))
        XCTAssertEqual(pages[2...6].map(\.granulePosition), [9600, 19200, 28800, 38400, 48000])
        XCTAssertEqual(pages[2...6].map(\.packets.count), [10, 10, 10, 10, 10])
        XCTAssertEqual(pages[7].flags, .endOfStream)
        XCTAssertEqual(pages[7].packets.count, 1)
        // Trimmed to the input: pre-skip plus exactly one second.
        XCTAssertEqual(pages[7].granulePosition, Int64(encoder.preSkip48k) + 48000)
        // About 24 kbit/s: 60 bytes per 20 ms packet, with room for VBR.
        let audioBytes = pages[2...6].flatMap(\.packets).reduce(0) { $0 + $1.count }
        XCTAssertTrue((1500...6000).contains(audioBytes), "audio bytes \(audioBytes)")
    }

    func testHeaderPagesAreTheSameBytesEveryCall() throws {
        var w = OggOpusWriter(encoder: try OpusEncoder(), serial: 9)
        let first = try w.headerPages()
        let page = try XCTUnwrap((0..<10).compactMap { try? w.append(frame: Self.sine(frame: $0)) }.first)
        // A reconnect asks again: same two pages, and the stream's next page keeps sequence 3.
        XCTAssertEqual(try w.headerPages(), first)
        XCTAssertEqual(try OggPage.read(page).page.sequence, 2)
        let next = try XCTUnwrap((10..<20).compactMap { try? w.append(frame: Self.sine(frame: $0)) }.first)
        XCTAssertEqual(try OggPage.read(next).page.sequence, 3)
    }

    func testAppendBeforeHeadersAndAfterFinishAreRefused() throws {
        var w = OggOpusWriter(encoder: try OpusEncoder())
        XCTAssertThrowsError(try w.append(frame: Self.sine(frame: 0))) { XCTAssertEqual($0 as? OggOpusWriter.Failure, .headersNotWritten) }
        _ = try w.headerPages()
        _ = try w.finish()
        XCTAssertThrowsError(try w.append(frame: Self.sine(frame: 0))) { XCTAssertEqual($0 as? OggOpusWriter.Failure, .alreadyFinished) }
    }

    /// An independent decoder reads the writer's file: ffprobe's demuxer and libopus. Skipped where
    /// ffprobe is not installed.
    func testFfprobeReadsTheWritersFile() throws {
        let ffprobe = ["/opt/homebrew/bin/ffprobe", "/usr/local/bin/ffprobe", "/usr/bin/ffprobe"]
            .first { FileManager.default.isExecutableFile(atPath: $0) }
        guard let ffprobe else { throw XCTSkip("ffprobe not installed") }
        let (file, _) = try Self.writeOneSecond()
        let ours = try probe(ffprobe, file)
        XCTAssertTrue(ours.contains("codec_name=opus"), ours)
        XCTAssertTrue(ours.contains("channels=1"), ours)
        // ffprobe reports the last granule over 48 kHz. One second through our writer must end
        // exactly where one second through ffmpeg's own encoder and muxer ends (the fixture).
        let reference = try probe(ffprobe, try OggTests().ffmpegFile())
        XCTAssertEqual(duration(ours), duration(reference))
        XCTAssertNotNil(duration(ours))
    }

    private func duration(_ text: String) -> Double? {
        text.split(separator: "\n").first { $0.hasPrefix("duration=") }.flatMap { Double($0.dropFirst(9)) }
    }

    private func probe(_ ffprobe: String, _ file: Data) throws -> String {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("akou-writer-\(UUID().uuidString).opus")
        try file.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: ffprobe)
        p.arguments = ["-v", "error", "-show_entries", "stream=codec_name,channels:format=duration", "-of", "default=nw=1", url.path]
        let out = Pipe()
        p.standardOutput = out
        try p.run()
        p.waitUntilExit()
        XCTAssertEqual(p.terminationStatus, 0)
        return String(decoding: out.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
    }
}
