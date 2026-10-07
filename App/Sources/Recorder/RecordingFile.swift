// SPDX-License-Identifier: GPL-3.0-or-later
import AkouOpus
import Foundation

/// The recording on disk. Microphone samples go in, are cut into 20 ms frames, encoded, and every
/// 200 ms Ogg Opus page is appended to the file and handed to `pages` for the live session. All of
/// it runs on one serial queue, off the main thread and off the audio thread.
final class RecordingFile: @unchecked Sendable {
    struct Page: Sendable {
        let data: Data
        /// Where the page ends in the recording, in seconds.
        let end: Double
    }

    let url: URL
    /// The OpusHead and OpusTags pages, the same bytes every live session starts with.
    let headerPages: [Data]
    let pages: AsyncStream<Page>

    private let continuation: AsyncStream<Page>.Continuation
    private let queue = DispatchQueue(label: "akou.recording-file", qos: .userInitiated)
    private let handle: FileHandle
    private var chunker = FrameChunker()
    private var writer: OggOpusWriter
    private var failure: String?
    private var finished = false

    /// Creates the file with protection `completeUntilFirstUserAuthentication`: the default
    /// `complete` class refuses writes while the phone is locked, which would end a locked recording.
    init(url: URL) throws {
        let created = FileManager.default.createFile(
            atPath: url.path, contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        guard created else { throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: url.path]) }
        do {
            let handle = try FileHandle(forWritingTo: url)
            var writer = OggOpusWriter(encoder: try OpusEncoder())
            let headers = try writer.headerPages()
            for page in headers { try handle.write(contentsOf: page) }
            self.handle = handle
            self.writer = writer
            self.headerPages = headers
        } catch {
            try? FileManager.default.removeItem(at: url)
            throw error
        }
        self.url = url
        (pages, continuation) = AsyncStream.makeStream(of: Page.self, bufferingPolicy: .unbounded)
    }

    /// 16 kHz mono samples from the microphone, any count.
    func append(_ samples: [Float]) {
        queue.async { self.encode(samples) }
    }

    /// Pads and writes the last frame, closes the file and ends `pages`. Returns the seconds of
    /// audio in the file, and the first write error if there was one.
    func finish() async -> (seconds: Double, error: String?) {
        await withCheckedContinuation { cont in
            queue.async {
                if !self.finished {
                    self.finished = true
                    do {
                        if self.failure == nil, let last = self.chunker.flush(), let page = try self.writer.append(frame: last) {
                            try self.write(page)
                        }
                        if self.failure == nil { try self.write(try self.writer.finish()) }
                    } catch {
                        self.failure = self.failure ?? "\(error)"
                    }
                    try? self.handle.close()
                    self.continuation.finish()
                }
                cont.resume(returning: (self.writer.seconds, self.failure))
            }
        }
    }

    private func encode(_ samples: [Float]) {
        guard !finished, failure == nil else { return }
        do {
            for frame in chunker.push(samples) {
                if let page = try writer.append(frame: frame) { try write(page) }
            }
        } catch {
            failure = "\(error)"
        }
    }

    private func write(_ page: Data) throws {
        try handle.write(contentsOf: page)
        continuation.yield(Page(data: page, end: writer.seconds))
    }
}
