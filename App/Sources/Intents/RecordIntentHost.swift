// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation

/// The app side of the record intents: the intents run here, in the app process. `App.init` sets
/// `recorder` to `RecordingController.shared` before any intent can run; the decisions live in
/// `RecordIntentGate`, which AkouKit's tests cover.
@MainActor
enum RecordIntentHost {
    static var recorder: (any RecordIntentRecorder)?

    static func start(title: String?) async throws {
        guard let recorder else { throw RecordIntentError.recorderUnavailable }
        do {
            try await RecordIntentGate.start(recorder, title: title, onStatus: RecordingStatus.set(recording:))
        } catch RecordIntentGateError.resumeFailed {
            throw RecordIntentError.resumeFailed
        }
    }

    static func stop() async throws {
        guard let recorder else { throw RecordIntentError.recorderUnavailable }
        await RecordIntentGate.stop(recorder, onStatus: RecordingStatus.set(recording:))
    }
}
