// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The two header packets every Ogg Opus stream starts with (RFC 7845 section 5).
public enum OpusHeaders {
    /// The identification header: version 1, mono or stereo, channel mapping family 0.
    /// `preSkip` is in 48 kHz samples; `inputSampleRate` is informational (16000 here).
    public static func head(channels: UInt8 = 1, preSkip: UInt16, inputSampleRate: UInt32, outputGain: Int16 = 0) -> Data {
        var d = Data("OpusHead".utf8)
        d.append(1) // version
        d.append(channels)
        d.appendLE(preSkip)
        d.appendLE(inputSampleRate)
        d.appendLE(UInt16(bitPattern: outputGain))
        d.append(0) // channel mapping family 0: mono or stereo, no table
        return d
    }

    /// The comment header: the vendor string and `key=value` comments.
    public static func tags(vendor: String, comments: [String] = []) -> Data {
        var d = Data("OpusTags".utf8)
        let v = Data(vendor.utf8)
        d.appendLE(UInt32(v.count))
        d.append(v)
        d.appendLE(UInt32(comments.count))
        for c in comments {
            let b = Data(c.utf8)
            d.appendLE(UInt32(b.count))
            d.append(b)
        }
        return d
    }
}
