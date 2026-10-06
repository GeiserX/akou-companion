// SPDX-License-Identifier: GPL-3.0-or-later
import ActivityKit
import Foundation

/// What the Live Activity shows while a recording runs. Shared by the app, which starts and updates
/// it, and the widget extension, which draws it. The elapsed time is drawn from `startedAt`, so the
/// activity is updated only when the state or the last line changes.
struct RecordingAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var startedAt: Date
        var paused: Bool
        /// The last closed line of the live transcript, empty until there is one.
        var lastLine: String
    }

    var title: String
}
