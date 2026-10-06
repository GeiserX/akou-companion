// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

// The control messages of akou's `GET /v1/live` WebSocket (docs/PROTOCOL.md). Text frames carry
// one JSON object with a `type`; binary frames carry audio and are not modelled here.

/// How the binary frames are encoded.
public enum LiveCodec: String, Codable, Sendable, Equatable {
    /// One Ogg Opus page per binary frame, OpusHead and OpusTags pages first. What the phone sends.
    case oggOpus = "ogg-opus"
    /// Raw 16 kHz 16-bit little-endian mono samples. For test clients and measurements.
    case pcm16
}

/// The first message on a new socket.
public struct LiveHello: Codable, Sendable, Equatable {
    public var v: Int
    public var codec: LiveCodec
    /// `auto` or a BCP 47 code; bounds which live engine `auto` picks.
    public var language: String
    /// `auto` or a live engine id the server reported in `GET /v1/server`.
    public var model: String

    public init(v: Int = 1, codec: LiveCodec = .oggOpus, language: String = "auto", model: String = "auto") {
        self.v = v
        self.codec = codec
        self.language = language
        self.model = model
    }
}

/// Messages the phone sends as text frames.
public enum LiveClientMessage: Sendable, Equatable {
    case hello(LiveHello)
    /// The recording stopped: the server sends the last words, then `closed`, and closes.
    case stop
}

/// The server's answer once the live stream is open.
public struct LiveReady: Codable, Sendable, Equatable {
    /// The live engine that serves this session, for example `nemotron-3.5-560`.
    public var engine: String
    public var lang: String?
    /// The engine's chunk in milliseconds: how far behind the audio a word can come.
    public var tierMs: Int?
    /// How long the engine took to load for this session, in milliseconds.
    public var loadMs: Int?

    public init(engine: String, lang: String? = nil, tierMs: Int? = nil, loadMs: Int? = nil) {
        self.engine = engine
        self.lang = lang
        self.tierMs = tierMs
        self.loadMs = loadMs
    }

    enum CodingKeys: String, CodingKey {
        case engine, lang
        case tierMs = "tier_ms"
        case loadMs = "load_ms"
    }
}

/// One recognised token. A token whose text starts with a space starts a new word.
public struct LiveToken: Codable, Sendable, Equatable {
    public var text: String
    /// Seconds into the recording (the file's timeline), computed by the server from the pages' granules.
    public var t: Double
    public var conf: Double?

    public init(text: String, t: Double, conf: Double? = nil) {
        self.text = text
        self.t = t
        self.conf = conf
    }
}

/// Append-only tokens: the server never takes one back. The end of a session is `closed`, not a
/// flag on the last `words`.
public struct LiveWords: Codable, Sendable, Equatable {
    public var tokens: [LiveToken]

    public init(tokens: [LiveToken]) {
        self.tokens = tokens
    }
}

/// An error the server reports before it closes the socket with the matching close code.
public struct LiveError: Codable, Sendable, Equatable {
    public var code: String
    public var message: String?

    public init(code: String, message: String? = nil) {
        self.code = code
        self.message = message
    }
}

/// Messages the server sends as text frames.
public enum LiveServerMessage: Sendable, Equatable {
    case ready(LiveReady)
    case words(LiveWords)
    /// The session ended after `stop`; the server closes the socket next.
    case closed
    case error(LiveError)
    /// A message type this client does not know yet; ignored rather than treated as an error.
    case unknown(type: String)
}

/// WebSocket close codes the live route uses besides the standard ones.
public enum LiveCloseCode: Int, Sendable, Equatable, CaseIterable {
    /// A message or frame the server refused: `bad_message` (malformed or out-of-order control
    /// message), `bad_page` (not a valid Ogg page, another stream's serial, or a gap in page
    /// sequence numbers), `too_fast` (more than 30 s of audio waiting for the engine),
    /// `unknown_model` or `unsupported_language`. The `error` frame says which.
    case badPage = 4400
    /// The key was revoked while the socket was open.
    case keyRevoked = 4401
    /// Another open session uses a different live engine; the server loads one at a time.
    case engineBusy = 4409
    /// The live engine stopped while the session ran.
    case streamLost = 4500
    /// No live engine is on disk, or live text is off on this server.
    case noLiveEngine = 4503
}

// MARK: - JSON

private struct TypeTag: Codable {
    var type: String
}

extension LiveClientMessage: Codable {
    public init(from decoder: Decoder) throws {
        let tag = try TypeTag(from: decoder)
        switch tag.type {
        case "hello": self = .hello(try LiveHello(from: decoder))
        case "stop": self = .stop
        default:
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "unknown client message type \(tag.type)"))
        }
    }

    public func encode(to encoder: Encoder) throws {
        switch self {
        case let .hello(h):
            try TypeTag(type: "hello").encode(to: encoder)
            try h.encode(to: encoder)
        case .stop:
            try TypeTag(type: "stop").encode(to: encoder)
        }
    }
}

extension LiveServerMessage: Codable {
    public init(from decoder: Decoder) throws {
        let tag = try TypeTag(from: decoder)
        switch tag.type {
        case "ready": self = .ready(try LiveReady(from: decoder))
        case "words": self = .words(try LiveWords(from: decoder))
        case "closed": self = .closed
        case "error": self = .error(try LiveError(from: decoder))
        default: self = .unknown(type: tag.type)
        }
    }

    public func encode(to encoder: Encoder) throws {
        switch self {
        case let .ready(r):
            try TypeTag(type: "ready").encode(to: encoder)
            try r.encode(to: encoder)
        case let .words(w):
            try TypeTag(type: "words").encode(to: encoder)
            try w.encode(to: encoder)
        case .closed:
            try TypeTag(type: "closed").encode(to: encoder)
        case let .error(e):
            try TypeTag(type: "error").encode(to: encoder)
            try e.encode(to: encoder)
        case let .unknown(type):
            try TypeTag(type: type).encode(to: encoder)
        }
    }
}

public extension LiveClientMessage {
    /// The text frame for this message.
    func json() throws -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        return String(decoding: try enc.encode(self), as: UTF8.self)
    }
}

public extension LiveServerMessage {
    /// Parses one text frame from the server.
    static func parse(_ text: String) throws -> LiveServerMessage {
        try JSONDecoder().decode(LiveServerMessage.self, from: Data(text.utf8))
    }
}
