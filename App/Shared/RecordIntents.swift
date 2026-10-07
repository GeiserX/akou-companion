// SPDX-License-Identifier: GPL-3.0-or-later
import AppIntents
import Foundation
import WidgetKit

// The record intents behind the Action button, the Control Center and Lock Screen control, Siri and
// Shortcuts. They are compiled into the app and the widget extension, because a control only offers
// an intent that both targets contain; `perform()` runs in the app process, where the recorder lives:
// a `LiveActivityIntent` launches the app process without opening the app, and an
// `AudioRecordingIntent` must start a Live Activity, which only the app can do. Each target brings its
// own `RecordIntentHost`: the app's drives the recorder, the extension's refuses.

/// Starts a recording. Recording keeps going with the screen locked, under a Live Activity.
struct StartRecordingIntent: AudioRecordingIntent, LiveActivityIntent {
    static var title: LocalizedStringResource { "Start recording" }
    static var description: IntentDescription {
        IntentDescription("Starts an akou recording on this iPhone. It keeps going with the screen locked and shows in a Live Activity.")
    }

    @Parameter(title: "Title")
    var recordingTitle: String?

    init() {}

    init(title: String?) {
        recordingTitle = title
    }

    func perform() async throws -> some IntentResult {
        try await RecordIntentHost.start(title: recordingTitle)
        return .result()
    }
}

/// Stops the running recording; the app then uploads it for the final transcript.
struct StopRecordingIntent: AudioRecordingIntent, LiveActivityIntent {
    static var title: LocalizedStringResource { "Stop recording" }
    static var description: IntentDescription {
        IntentDescription("Stops the akou recording and sends it to your akou server for the final transcript.")
    }

    init() {}

    func perform() async throws -> some IntentResult {
        try await RecordIntentHost.stop()
        return .result()
    }
}

/// The record control's action: the system sets `value` to the state the person asked for.
struct ToggleRecordingIntent: SetValueIntent, AudioRecordingIntent, LiveActivityIntent {
    static var title: LocalizedStringResource { "Record" }
    static var description: IntentDescription {
        IntentDescription("Starts an akou recording, or stops the one that is running.")
    }

    @Parameter(title: "Recording")
    var value: Bool

    init() {}

    func perform() async throws -> some IntentResult {
        if value {
            try await RecordIntentHost.start(title: nil)
        } else {
            try await RecordIntentHost.stop()
        }
        return .result()
    }
}

enum RecordIntentError: Error, CustomLocalizedStringResourceConvertible {
    /// The recorder has not registered with the intents yet.
    case recorderUnavailable
    /// The widget extension was asked to record; only the app process can.
    case notInApp
    /// A paused recording could not continue: the microphone did not come back.
    case resumeFailed

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .recorderUnavailable: "akou cannot record right now. Open akou and try again."
        case .notInApp: "Recording starts in the akou app. Open akou and try again."
        case .resumeFailed: "The microphone did not come back, so the recording is still paused. Open akou and try again."
        }
    }
}

/// Whether a recording is running, as the record control shows it. The control's value is read in
/// the widget extension, which cannot see the app's recorder, so the app writes the state to the
/// App Group's defaults whenever it changes and asks the system to redraw the control.
enum RecordingStatus {
    static let controlKind = "io.github.geiserx.akou-companion.record"
    private static let key = "recording"

    static var isRecording: Bool {
        AppGroup.defaults?.bool(forKey: key) ?? false
    }

    /// Called by the app on every start, stop and end of a recording, however it happened.
    static func set(recording: Bool) {
        AppGroup.defaults?.set(recording, forKey: key)
        ControlCenter.shared.reloadControls(ofKind: controlKind)
    }
}
