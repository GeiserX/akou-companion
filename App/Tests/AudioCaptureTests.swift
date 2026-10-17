// SPDX-License-Identifier: GPL-3.0-or-later
import AVFoundation
import XCTest
@testable import akou

/// The microphone tap runs on AVFoundation's audio thread, never the main actor.
final class AudioCaptureTests: XCTestCase {
    /// A block that inherited the main actor trapped on the first buffer (seen on every real
    /// recording); here it is called from a background queue as AVFoundation calls it.
    func testTheTapConvertsABufferOffTheMainThread() throws {
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 2, interleaved: false))
        let converter = try XCTUnwrap(AVAudioConverter(from: format, to: AudioCapture.target))
        converter.downmix = true
        let got = expectation(description: "samples reach the sink")
        let tap = AudioCapture.tap(converter) { samples in
            XCTAssertFalse(Thread.isMainThread)
            // 100 ms at 16 kHz, less the converter's first-buffer latency.
            XCTAssertTrue((1...1632).contains(samples.count), "\(samples.count) samples")
            got.fulfill()
        }
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4800))
        buffer.frameLength = 4800
        DispatchQueue.global().async { tap(buffer, AVAudioTime(sampleTime: 0, atRate: 48000)) }
        wait(for: [got], timeout: 5)
    }
}
