// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// What the record intents need from the recorder. The app's `RecordingController` conforms.
@MainActor
public protocol RecordIntentRecorder: AnyObject {
    /// True from start until the file is finished, paused included: a paused recording still holds its file.
    var isRecording: Bool { get }
    var isPaused: Bool { get }
    /// Starts recording and the Live Activity. `workspace` nil is the key's default workspace.
    func start(workspace: String?, title: String?) async throws
    /// Continues a paused recording in the same file.
    func resume()
    /// Finishes the file; the app then uploads it.
    func stopRecording() async
}

/// The decisions behind the Action button, the record control, Siri and Shortcuts, kept apart from
/// the intents so `swift test` covers them. `onStatus` gets whether a recording is running once the
/// call is over, failed or not, so the control never shows a state the recorder is not in.
@MainActor
public enum RecordIntentGate {
    /// Resumes a paused recording, starts one when none is running, and does nothing otherwise.
    public static func start(_ recorder: any RecordIntentRecorder, title: String?, onStatus: (Bool) -> Void) async throws {
        defer { onStatus(recorder.isRecording) }
        if recorder.isPaused {
            recorder.resume()
        } else if !recorder.isRecording {
            try await recorder.start(workspace: nil, title: title)
        }
    }

    /// Stops the running or paused recording, and does nothing when there is none.
    public static func stop(_ recorder: any RecordIntentRecorder, onStatus: (Bool) -> Void) async {
        defer { onStatus(recorder.isRecording) }
        if recorder.isRecording {
            await recorder.stopRecording()
        }
    }
}
