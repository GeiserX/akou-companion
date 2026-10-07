// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Cuts 16 kHz mono audio, arriving in buffers of whatever length the microphone hands out, into
/// the exact 20 ms frames (`OpusEncoder.frameSamples`, 320 samples) that `OggOpusWriter.append(frame:)`
/// takes. The samples left over from one buffer start the next frame.
public struct FrameChunker: Sendable {
    public let frameSamples: Int
    private var carry: [Float] = []

    public init(frameSamples: Int = OpusEncoder.frameSamples) {
        precondition(frameSamples > 0)
        self.frameSamples = frameSamples
        carry.reserveCapacity(frameSamples)
    }

    /// The whole frames the carried samples and `samples` make, oldest first.
    public mutating func push(_ samples: [Float]) -> [[Float]] {
        var frames: [[Float]] = []
        var i = samples.startIndex
        if !carry.isEmpty {
            let take = min(frameSamples - carry.count, samples.count)
            carry += samples[i..<(i + take)]
            i += take
            guard carry.count == frameSamples else { return [] }
            frames.append(carry)
            carry.removeAll(keepingCapacity: true)
        }
        while samples.endIndex - i >= frameSamples {
            frames.append(Array(samples[i..<(i + frameSamples)]))
            i += frameSamples
        }
        carry += samples[i...]
        return frames
    }

    /// The last partial frame padded with zeros to a whole frame, or nil when nothing is carried.
    public mutating func flush() -> [Float]? {
        guard !carry.isEmpty else { return nil }
        let last = carry + [Float](repeating: 0, count: frameSamples - carry.count)
        carry.removeAll(keepingCapacity: true)
        return last
    }

    /// Samples waiting for the next frame.
    public var carried: Int { carry.count }
}
