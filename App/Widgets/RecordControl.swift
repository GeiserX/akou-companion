// SPDX-License-Identifier: GPL-3.0-or-later
import AppIntents
import SwiftUI
import WidgetKit

/// One control that starts and stops a recording, for Control Center, the Lock Screen and the
/// Action button. Its value comes from `RecordingStatus`, which the app keeps current.
struct RecordControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: RecordingStatus.controlKind, provider: Provider()) { isRecording in
            ControlWidgetToggle("Record", isOn: isRecording, action: ToggleRecordingIntent()) { on in
                Label(on ? "Recording" : "Record", systemImage: on ? "stop.circle.fill" : "record.circle")
            }
            .tint(.red)
        }
        .displayName("Record with akou")
        .description("Starts an akou recording, or stops the one that is running.")
    }

    struct Provider: ControlValueProvider {
        var previewValue: Bool { false }

        func currentValue() async throws -> Bool {
            RecordingStatus.isRecording
        }
    }
}
