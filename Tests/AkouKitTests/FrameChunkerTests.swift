// SPDX-License-Identifier: GPL-3.0-or-later
import AkouOpus
import Foundation
import XCTest

final class FrameChunkerTests: XCTestCase {
    /// A ramp, so a sample out of place or lost shows as a wrong value.
    private func ramp(_ range: Range<Int>) -> [Float] { range.map { Float($0) } }

    func testOddBufferSizesComeOutAsExactFramesInOrder() {
        var chunker = FrameChunker()
        let sizes = [1, 319, 320, 321, 7, 1000, 13, 639, 2]
        var input: [Float] = []
        var frames: [[Float]] = []
        var at = 0
        for n in sizes {
            let buffer = ramp(at..<(at + n))
            at += n
            input += buffer
            frames += chunker.push(buffer)
        }
        XCTAssertEqual(at, 2622)
        XCTAssertEqual(frames.count, 2622 / 320)
        XCTAssertTrue(frames.allSatisfy { $0.count == OpusEncoder.frameSamples })
        XCTAssertEqual(Array(frames.joined()), Array(input.prefix(frames.count * 320)))
        XCTAssertEqual(chunker.carried, 2622 % 320)

        let last = chunker.flush()
        XCTAssertEqual(last?.count, 320)
        XCTAssertEqual(Array(last?.prefix(62) ?? []), Array(input.suffix(62)))
        XCTAssertEqual(Array(last?.dropFirst(62) ?? []), [Float](repeating: 0, count: 258))
        XCTAssertNil(chunker.flush(), "flush hands out the remainder once")
        XCTAssertEqual(chunker.carried, 0)
    }

    func testABufferOfManyFramesAndAnEmptyOne() {
        var chunker = FrameChunker()
        XCTAssertEqual(chunker.push([]), [])
        let frames = chunker.push(ramp(0..<960))
        XCTAssertEqual(frames, [ramp(0..<320), ramp(320..<640), ramp(640..<960)])
        XCTAssertNil(chunker.flush(), "nothing carried, nothing to pad")
    }

    func testFramesFeedTheWriter() throws {
        var chunker = FrameChunker()
        var writer = OggOpusWriter(encoder: try OpusEncoder(), serial: 7)
        _ = try writer.headerPages()
        var pages = 0
        // 1 s in 100 ms buffers of 1600 samples: 50 frames, 5 pages of 10.
        for i in 0..<10 {
            for frame in chunker.push(ramp((i * 1600)..<((i + 1) * 1600)).map { sin($0 / 10) * 0.2 }) {
                if try writer.append(frame: frame) != nil { pages += 1 }
            }
        }
        XCTAssertEqual(pages, 5)
        XCTAssertEqual(writer.seconds, 1.0, accuracy: 1e-9)
    }
}
