// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation
import WidgetKit

/// The App Group the app and the widget extension share. Registered for both App IDs in the
/// developer account; without it (a build with no signing) the container is nil and the widgets
/// show their empty state.
enum AppGroup {
    static let id = "group.io.github.geiserx.akou-companion"

    static var defaults: UserDefaults? { UserDefaults(suiteName: id) }

    static var container: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)
    }
}

/// One recording as the widget and the "last recording" intent show it.
struct RecentRecording: Codable, Hashable, Identifiable, Sendable {
    var jobId: String
    var title: String
    var workspace: String?
    var createdAt: Date
    /// Seconds of audio, when known.
    var duration: TimeInterval?
    /// The akou job's status: queued, running, done, failed.
    var status: String
    /// The opening sentences of the final transcript (`TranscriptDigest`), empty until it arrives.
    var opening: String

    var id: String { jobId }
}

/// The last few recordings, kept in the App Group container. The app writes it after each refresh
/// of the recordings list and after a transcript arrives; the widget and the intent only read it,
/// so no key and no network request ever runs in the widget extension.
struct RecentSnapshot: Codable, Sendable, Equatable {
    static let limit = 5
    static let openingLimit = 600
    static let widgetKind = "io.github.geiserx.akou-companion.recent"
    private static let fileName = "recent-recordings.json"

    var updatedAt: Date
    /// Newest first, at most `limit`.
    var recordings: [RecentRecording]

    /// Keeps the `limit` newest recordings and cuts each opening to `openingLimit` characters.
    init(recordings: [RecentRecording], updatedAt: Date = .now) {
        self.updatedAt = updatedAt
        self.recordings = recordings
            .sorted { $0.createdAt > $1.createdAt }
            .prefix(Self.limit)
            .map { r in
                var r = r
                r.opening = TranscriptDigest.opening(text: r.opening, limit: Self.openingLimit)
                return r
            }
    }

    static var fileURL: URL? { AppGroup.container?.appendingPathComponent(fileName) }

    private static func coder() -> (JSONEncoder, JSONDecoder) {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return (e, d)
    }

    /// The saved snapshot, or nil when there is none or no App Group container.
    static func read() -> RecentSnapshot? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? coder().1.decode(RecentSnapshot.self, from: data)
    }

    /// Saves the snapshot and redraws the recent-recordings widget. App only.
    func write() throws {
        guard let url = Self.fileURL else { return }
        let data = try Self.coder().0.encode(self)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        WidgetCenter.shared.reloadTimelines(ofKind: Self.widgetKind)
    }
}
