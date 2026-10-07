// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
@testable import akou

/// Stands in for the microphone: hands its samples to the recorder in 100 ms buffers, at the pace
/// a microphone would, so live text sees the timing of a real recording.
@MainActor
final class FileSampleSource: SampleSource {
    var onInterruption: ((_ began: Bool, _ shouldResume: Bool) -> Void)?
    private let samples: [Float]
    private let sink: @Sendable ([Float]) -> Void
    private var offset = 0
    private var task: Task<Void, Never>?
    /// True once every sample has gone to the recorder.
    private(set) var finished = false

    init(samples: [Float], sink: @escaping @Sendable ([Float]) -> Void) {
        self.samples = samples
        self.sink = sink
    }

    /// Raw 32-bit float, little-endian, 16 kHz mono (`ffmpeg -f f32le -ar 16000 -ac 1`).
    static func samples(contentsOf url: URL) throws -> [Float] {
        let data = try Data(contentsOf: url)
        return data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    }

    func start() throws { run() }
    func pause() { task?.cancel() }
    func resume() throws { run() }
    func stop() { task?.cancel() }

    private func run() {
        task?.cancel()
        task = Task { [weak self] in
            while let self, !Task.isCancelled, self.offset < self.samples.count {
                let end = min(self.offset + 1600, self.samples.count)
                self.sink(Array(self.samples[self.offset..<end]))
                self.offset = end
                try? await Task.sleep(for: .milliseconds(100))
            }
            if let self, self.offset >= self.samples.count { self.finished = true }
        }
    }
}
