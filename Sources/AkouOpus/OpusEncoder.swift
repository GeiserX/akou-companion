// SPDX-License-Identifier: GPL-3.0-or-later
import COpusShim
import Copus
import Foundation

/// libopus set up the way akou-companion records: 16 kHz mono in, 20 ms frames, VBR at the given
/// bitrate (24 kbit/s by default), wideband at most, voice signal, no DTX and no in-band FEC.
public final class OpusEncoder {
    /// The rate every akou engine takes, so the server never resamples the phone's audio.
    public static let sampleRate: Int32 = 16000
    /// 20 ms at 16 kHz.
    public static let frameSamples = 320
    /// The same 20 ms in the 48 kHz units Ogg Opus granule positions use.
    public static let frameSamples48k: Int64 = 960

    public struct Failure: Error, Equatable {
        public let code: Int32
    }

    private let handle: OpaquePointer
    /// The encoder's lookahead in 16 kHz samples.
    public let lookahead: Int
    /// The bitrate libopus reports after configuration, in bits per second.
    public let bitrate: Int

    public init(bitrate: Int32 = 24000) throws {
        var err: Int32 = 0
        guard let h = opus_encoder_create(Self.sampleRate, 1, OPUS_APPLICATION_VOIP, &err), err == OPUS_OK else {
            throw Failure(code: err)
        }
        handle = h
        let raw = UnsafeMutableRawPointer(h)
        let rc = akou_opus_configure(raw, bitrate)
        guard rc == OPUS_OK else {
            opus_encoder_destroy(h)
            throw Failure(code: rc)
        }
        let la = akou_opus_lookahead(raw)
        let br = akou_opus_bitrate(raw)
        guard la >= 0, br > 0 else {
            opus_encoder_destroy(h)
            throw Failure(code: la < 0 ? la : br)
        }
        self.lookahead = Int(la)
        self.bitrate = Int(br)
    }

    deinit { opus_encoder_destroy(handle) }

    /// The Ogg Opus pre-skip: the lookahead in 48 kHz samples (RFC 7845 section 4.2).
    public var preSkip48k: UInt16 { UInt16(lookahead * 3) }

    /// Encodes exactly one 20 ms frame of 320 mono Float32 samples in [-1, 1] into one Opus packet.
    public func encode(_ frame: [Float]) throws -> Data {
        guard frame.count == Self.frameSamples else { throw Failure(code: OPUS_BAD_ARG) }
        var out = [UInt8](repeating: 0, count: 1500)
        let n = frame.withUnsafeBufferPointer { input in
            out.withUnsafeMutableBufferPointer { output in
                opus_encode_float(handle, input.baseAddress!, Int32(Self.frameSamples), output.baseAddress!, Int32(output.count))
            }
        }
        guard n > 0 else { throw Failure(code: n) }
        return Data(out[0..<Int(n)])
    }
}
