// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The parts of akou's `GET /v1/server` answer the phone reads. Every other field is ignored, so a
/// newer server never breaks an older app.
public struct ServerInfo: Decodable, Sendable, Equatable {
    public var name: String
    public var version: String?
    /// `server` on a server, `app` on a desktop app (which a phone cannot reach).
    public var mode: String?
    public var capabilities: Capabilities
    /// The live door in server mode: the streaming models on disk a `hello` may name. Null in the
    /// desktop app and absent on servers older than the route.
    public var live: Live?
    /// `server.retain_days`: the days a job and its result stay, counted from its creation. A job
    /// sent with `keep_audio=true` is exempt. Absent on older servers.
    public var retainDays: Int?

    public struct Live: Decodable, Sendable, Equatable {
        public var engines: [String]
    }

    enum CodingKeys: String, CodingKey {
        case name, version, mode, capabilities, live
        case retainDays = "retain_days"
    }

    public struct Capabilities: Decodable, Sendable, Equatable {
        public var jobs: Bool?
        /// True when `GET /v1/live` exists and a live engine is on disk. Absent on servers older than the route.
        public var live: Bool?
    }

    /// Whether this server can show live text.
    public var supportsLive: Bool { capabilities.live == true }

    /// The streaming models a phone may pin as its live model; empty when the server lists none.
    public var liveEngines: [String] { live?.engines ?? [] }
}

/// akou's `GET /v1/keys/me` answer.
public struct KeyInfo: Decodable, Sendable, Equatable {
    public var id: String
    public var name: String?
    public var scopes: [String]
}
