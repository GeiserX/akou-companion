// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// The reads of an akou's history: its calls (with transcript, summary, notes and audio), its
/// dictations, and every job. They need a key with the `read` or `admin` scope; calls and
/// dictations exist only on a desktop akou (`mode` `app` in `GET /v1/server`).
public struct HistoryClient: Sendable {
    public enum Failure: Error, Equatable {
        /// 401 or 403 on a history route: this key cannot see history.
        case cannotSeeHistory(status: Int)
        /// Any other answer outside 2xx, with akou's error code and message when the body carried them.
        case status(Int, code: String?, message: String?)
        /// A 2xx answer that is not akou's JSON.
        case notAkou
    }

    public let baseURL: URL
    private let key: String
    private let session: URLSession

    public init(baseURL: URL, key: String, session: URLSession = AkouSession.shared) {
        self.baseURL = baseURL
        self.key = key
        self.session = session
    }

    // MARK: - Calls

    /// `GET /v1/calls`: the calls on disk, newest first, metadata only.
    public func calls(limit: Int = 200) async throws -> [CallSummary] {
        let list: CallList = try await get("/v1/calls", [.init(name: "limit", value: String(limit))])
        return list.calls
    }

    /// `GET /v1/calls/{id}`.
    public func call(_ id: String) async throws -> CallDetail {
        try await get("/v1/calls/\(Self.segment(id))")
    }

    /// `GET /v1/calls/{id}/transcript`: every line, the final pass where it ran and the live lines
    /// elsewhere (`layer=best`), without waiting for a second pass (`review=skip`).
    public func transcript(_ id: String) async throws -> CallTranscript {
        try await get("/v1/calls/\(Self.segment(id))/transcript", [
            .init(name: "layer", value: "best"), .init(name: "format", value: "json"), .init(name: "review", value: "skip"),
        ])
    }

    /// `GET /v1/calls/{id}/enhanced`: the latest summary, or nil when the call has none.
    public func summary(_ id: String) async throws -> CallEnhanced.Notes? {
        let e: CallEnhanced = try await get("/v1/calls/\(Self.segment(id))/enhanced")
        return e.enhanced
    }

    /// `GET /v1/calls/{id}/notes`: the notepad lines.
    public func notes(_ id: String) async throws -> [CallNote] {
        let n: CallNotes = try await get("/v1/calls/\(Self.segment(id))/notes")
        return n.notes
    }

    /// The path of one part's audio, Ogg Opus, served with `Range`.
    public static func callAudioPath(_ id: String, part: Int) -> String { "/v1/calls/\(segment(id))/audio/\(part)" }

    // MARK: - Dictations

    /// `GET /v1/dictations`: newest first; pass the page's `nextCursor` back for the next one.
    public func dictations(cursor: String? = nil, limit: Int = 50) async throws -> DictationPage {
        var q: [URLQueryItem] = [.init(name: "limit", value: String(limit))]
        if let cursor { q.append(.init(name: "cursor", value: cursor)) }
        return try await get("/v1/dictations", q)
    }

    /// `GET /v1/dictations/{id}`.
    public func dictation(_ id: String) async throws -> Dictation {
        try await get("/v1/dictations/\(Self.segment(id))")
    }

    /// The path of a dictation's audio: Ogg Opus (a WAV for one kept before akou kept Opus), served
    /// whole, without `Range`; 404 `no_audio` when none was kept.
    public static func dictationAudioPath(_ id: String) -> String { "/v1/dictations/\(segment(id))/audio" }

    // MARK: - Jobs

    /// `GET /v1/jobs`: newest first; a `read` or `admin` key sees every key's jobs.
    public func jobs(cursor: Int? = nil, limit: Int = 50) async throws -> JobPage {
        var q: [URLQueryItem] = [.init(name: "limit", value: String(limit))]
        if let cursor { q.append(.init(name: "cursor", value: String(cursor))) }
        return try await get("/v1/jobs", q)
    }

    // MARK: - Audio

    /// A plain `GET` for an audio path on this server, with the key in the `Authorization` header
    /// and never in the URL. `AuthorizedAudioLoader` adds the `Range`.
    public func audioRequest(path: String) throws -> URLRequest {
        try Self.request(baseURL, key: key, "GET", path, accept: "audio/*")
    }

    // MARK: - Plumbing

    /// An id as one path segment. akou's ids never hold a `/`; one that did could add a segment, so
    /// it is dropped. `Endpoint` percent-encodes the rest (a `?` or `#` never starts a query).
    static func segment(_ id: String) -> String {
        let s = id.replacingOccurrences(of: "/", with: "")
        return s == "." || s == ".." || s.isEmpty ? "_" : s
    }

    static func request(_ base: URL, key: String, _ method: String, _ path: String, query: [URLQueryItem] = [], accept: String) throws -> URLRequest {
        var url = try Endpoint.api(base, path)
        if !query.isEmpty, var c = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            c.queryItems = query
            url = c.url ?? url
        }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        req.httpMethod = method
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        req.setValue(accept, forHTTPHeaderField: "Accept")
        return req
    }

    private func get<T: Decodable>(_ path: String, _ query: [URLQueryItem] = []) async throws -> T {
        let req = try Self.request(baseURL, key: key, "GET", path, query: query, accept: "application/json")
        let (body, response) = try await session.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            if status == 401 || status == 403 { throw Failure.cannotSeeHistory(status: status) }
            let parsed = try? JSONDecoder().decode(AkouErrorBody.self, from: body)
            throw Failure.status(status, code: parsed?.error, message: parsed?.message)
        }
        do {
            return try JSONDecoder().decode(T.self, from: body)
        } catch {
            throw Failure.notAkou
        }
    }
}
