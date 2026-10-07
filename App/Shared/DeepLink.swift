// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The app's links, opened by the Live Activity and the recent-recordings widget:
/// `akou-companion://record` (the running recording) and `akou-companion://recording/<job id>`.
/// The app handles them in `.onOpenURL { DeepLink($0) }`.
enum DeepLink: Equatable, Sendable {
    static let scheme = "akou-companion"

    case record
    case recording(jobId: String)

    init?(_ url: URL) {
        guard url.scheme == Self.scheme else { return nil }
        switch url.host() {
        case "record":
            self = .record
        case "recording":
            guard let id = url.pathComponents.dropFirst().first, !id.isEmpty else { return nil }
            self = .recording(jobId: id)
        default:
            return nil
        }
    }

    var url: URL {
        var c = URLComponents()
        c.scheme = Self.scheme
        switch self {
        case .record:
            c.host = "record"
        case let .recording(jobId):
            c.host = "recording"
            c.path = "/" + jobId
        }
        return c.url!
    }
}
