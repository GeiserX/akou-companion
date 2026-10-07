// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The widget extension's side of the record intents. The system runs them in the app process; if
/// one ever lands here, it refuses rather than pretend to record.
enum RecordIntentHost {
    static func start(title: String?) async throws {
        throw RecordIntentError.notInApp
    }

    static func stop() async throws {
        throw RecordIntentError.notInApp
    }
}
