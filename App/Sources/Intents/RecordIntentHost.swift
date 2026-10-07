// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// What the record intents need from the recorder. The recorder (`RecordingController`) conforms
/// and sets `RecordIntentHost.recorder` when the app starts, before any intent can run.
@MainActor
protocol RecordingControlling: AnyObject {
    var isRecording: Bool { get }
    /// Starts recording and the Live Activity. `workspace` nil is the key's default workspace.
    func start(workspace: String?, title: String?) async throws
    func stop() async throws
}

/// The app side of the record intents: the intents run here, in the app process.
@MainActor
enum RecordIntentHost {
    static var recorder: (any RecordingControlling)?

    static func start(title: String?) async throws {
        guard let recorder else { throw RecordIntentError.recorderUnavailable }
        if !recorder.isRecording {
            try await recorder.start(workspace: nil, title: title)
        }
        RecordingStatus.set(recording: recorder.isRecording)
    }

    static func stop() async throws {
        guard let recorder else { throw RecordIntentError.recorderUnavailable }
        if recorder.isRecording {
            try await recorder.stop()
        }
        RecordingStatus.set(recording: recorder.isRecording)
    }
}
