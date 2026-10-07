// SPDX-License-Identifier: GPL-3.0-or-later
import AppIntents

/// The phrases Siri and Spotlight offer with no setup, and the actions Shortcuts and the Action
/// button list under akou.
struct AkouShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartRecordingIntent(),
            phrases: [
                "Start recording in \(.applicationName)",
                "Record with \(.applicationName)",
                "New \(.applicationName) recording",
            ],
            shortTitle: "Record",
            systemImageName: "record.circle"
        )
        AppShortcut(
            intent: StopRecordingIntent(),
            phrases: [
                "Stop recording in \(.applicationName)",
                "Stop the \(.applicationName) recording",
            ],
            shortTitle: "Stop recording",
            systemImageName: "stop.circle"
        )
        AppShortcut(
            intent: LastRecordingIntent(),
            phrases: [
                "What was my last \(.applicationName) recording",
                "Summarize my last \(.applicationName) recording",
                "Last \(.applicationName) recording",
            ],
            shortTitle: "Last recording",
            systemImageName: "text.bubble"
        )
    }
}
