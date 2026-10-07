// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// Every file-job call the phone makes to akou: the upload of a recording, its state and
/// transcript, the list, rename, delete and the kept audio.
public struct JobsClient: Sendable {
    /// What one recording is uploaded with. Persisted in the upload queue, so a retry after an app
    /// restart sends exactly the same options and gets the first job back.
    public struct Submission: Codable, Sendable, Equatable {
        /// The recording's id: the `Idempotency-Key` and `metadata.recording_id`.
        public var recordingID: String
        public var title: String?
        /// `auto` or a BCP 47 code; nil leaves it to the server.
        public var language: String?
        /// A recognizer id; nil leaves it to `preset` and the server.
        public var model: String?
        /// `lite`, `fast`, `best`, `fusion` or `auto`; nil leaves it to the server.
        public var preset: String?
        public var workspace: String?
        /// Always true from the app: the phone deletes its copy only when the server keeps one.
        public var keepAudio: Bool

        public init(
            recordingID: String, title: String? = nil, language: String? = nil, model: String? = nil,
            preset: String? = nil, workspace: String? = nil, keepAudio: Bool = true
        ) {
            self.recordingID = recordingID
            self.title = title
            self.language = language
            self.model = model
            self.preset = preset
            self.workspace = workspace
            self.keepAudio = keepAudio
        }

        var metadata: CompanionMetadata { CompanionMetadata(recordingID: recordingID, workspace: workspace) }
    }

    public enum Failure: Error, Equatable {
        /// An answer other than 2xx, with akou's error body when it sent one and `Retry-After` in seconds.
        case status(Int, body: AkouErrorBody?, retryAfter: TimeInterval?)
        /// A 2xx answer that is not akou's JSON.
        case notAkou
    }

    /// A submit's answer: the job, and whether it already existed (200 for a repeated key) or is new (202).
    public struct Submitted: Sendable, Equatable {
        public var job: Job
        public var existing: Bool
    }

    public let baseURL: URL
    private let key: String
    private let session: URLSession

    public init(baseURL: URL, key: String, session: URLSession = AkouSession.shared) {
        self.baseURL = baseURL
        self.key = key
        self.session = session
    }

    // MARK: - Submit

    /// Writes the multipart body of `POST /v1/jobs` to `bodyFile` and returns the request to upload
    /// it with. The request has no body of its own: pass `bodyFile` to `uploadTask(with:fromFile:)`.
    public func submitRequest(_ s: Submission, audio: URL, bodyFile: URL) throws -> URLRequest {
        var req = try request("POST", "/v1/jobs")
        var fields: [(name: String, value: String)] = []
        if let t = s.title, !t.isEmpty { fields.append(("title", t)) }
        if let l = s.language, !l.isEmpty { fields.append(("language", l)) }
        if let m = s.model, !m.isEmpty { fields.append(("model", m)) }
        if let p = s.preset, !p.isEmpty { fields.append(("preset", p)) }
        fields.append(("keep_audio", s.keepAudio ? "true" : "false"))
        let meta = try JSONEncoder().encode(s.metadata)
        fields.append(("metadata", String(decoding: meta, as: UTF8.self)))
        let boundary = try Multipart.write(
            fields: fields,
            file: .init(name: "file", fileName: "\(s.recordingID).opus", contentType: "audio/ogg", url: audio),
            to: bodyFile
        )
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.setValue(s.recordingID, forHTTPHeaderField: "Idempotency-Key")
        return req
    }

    /// Reads a submit's answer, wherever it came from (this client or a background session).
    public static func submitted(status: Int, headers: [AnyHashable: Any], body: Data) throws -> Submitted {
        try check(status: status, headers: headers, body: body)
        return Submitted(job: try decode(Job.self, body), existing: status == 200)
    }

    /// Uploads a recording in the foreground. The app's own uploads go through the background
    /// session in `BackgroundUploader`; this is for tests and command-line use.
    public func submit(_ s: Submission, audio: URL, bodyFile: URL) async throws -> Submitted {
        let req = try submitRequest(s, audio: audio, bodyFile: bodyFile)
        let (body, response) = try await session.upload(for: req, fromFile: bodyFile)
        let http = response as? HTTPURLResponse
        return try Self.submitted(status: http?.statusCode ?? 0, headers: http?.allHeaderFields ?? [:], body: body)
    }

    // MARK: - Reading and changing jobs

    /// `GET /v1/jobs/{id}?wait=`: `wait` (0 to 60 seconds) holds the answer until the job ends.
    public func job(_ id: String, wait: Int = 0) async throws -> Job {
        try await send(try jobRequest(id, wait: wait))
    }

    /// The request behind `job(_:wait:)`, for a caller with its own transport.
    public func jobRequest(_ id: String, wait: Int = 0) throws -> URLRequest {
        let w = max(0, min(60, wait))
        var req = try request("GET", "/v1/jobs/\(id)", query: w > 0 ? [URLQueryItem(name: "wait", value: String(w))] : [])
        req.timeoutInterval = max(req.timeoutInterval, TimeInterval(w) + 30)
        return req
    }

    /// `GET /v1/jobs/{id}/result?format=json`: 409 `not_done` until the job is done.
    public func result(_ id: String) async throws -> JobResult {
        try await send(try request("GET", "/v1/jobs/\(id)/result", query: [URLQueryItem(name: "format", value: "json")]))
    }

    /// `GET /v1/jobs`: the key's jobs, newest first. Pass the page's `cursor` back for the next one.
    public func list(status: String? = nil, query: String? = nil, cursor: Int? = nil, limit: Int? = nil) async throws -> JobPage {
        var items: [URLQueryItem] = []
        if let status { items.append(.init(name: "status", value: status)) }
        if let query { items.append(.init(name: "q", value: query)) }
        if let cursor { items.append(.init(name: "cursor", value: String(cursor))) }
        if let limit { items.append(.init(name: "limit", value: String(limit))) }
        return try await send(try request("GET", "/v1/jobs", query: items))
    }

    /// `PATCH /v1/jobs/{id}` `{title}`.
    public func rename(_ id: String, title: String) async throws -> Job {
        var req = try request("PATCH", "/v1/jobs/\(id)")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(["title": title])
        return try await send(req)
    }

    /// `DELETE /v1/jobs/{id}`: the job, its result and its kept audio.
    public func delete(_ id: String) async throws {
        let (body, response) = try await session.data(for: try request("DELETE", "/v1/jobs/\(id)"))
        let http = response as? HTTPURLResponse
        try Self.check(status: http?.statusCode ?? 0, headers: http?.allHeaderFields ?? [:], body: body)
    }

    /// `GET /v1/jobs/{id}/audio` with an optional byte range, for a player that streams it. The
    /// server answers 409 `not_kept` for a job sent without `keep_audio` and 410 `gone` when the
    /// kept file is no longer on disk.
    public func audioRequest(_ id: String, range: ClosedRange<Int64>? = nil) throws -> URLRequest {
        var req = try request("GET", "/v1/jobs/\(id)/audio", accept: "audio/*")
        if let range { req.setValue("bytes=\(range.lowerBound)-\(range.upperBound)", forHTTPHeaderField: "Range") }
        return req
    }

    // MARK: - Plumbing

    private func request(_ method: String, _ path: String, query: [URLQueryItem] = [], accept: String = "application/json") throws -> URLRequest {
        var url = try Endpoint.api(baseURL, path)
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

    private func send<T: Decodable>(_ req: URLRequest) async throws -> T {
        let (body, response) = try await session.data(for: req)
        let http = response as? HTTPURLResponse
        try Self.check(status: http?.statusCode ?? 0, headers: http?.allHeaderFields ?? [:], body: body)
        return try Self.decode(T.self, body)
    }

    static func check(status: Int, headers: [AnyHashable: Any], body: Data) throws {
        guard (200..<300).contains(status) else {
            let parsed = try? JSONDecoder().decode(AkouErrorBody.self, from: body)
            throw Failure.status(status, body: parsed, retryAfter: retryAfter(headers) ?? parsed?.retryAfterS.map(TimeInterval.init))
        }
    }

    static func decode<T: Decodable>(_ type: T.Type, _ body: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: body)
        } catch {
            throw Failure.notAkou
        }
    }

    /// `Retry-After` in seconds; akou sends seconds, never an HTTP date.
    static func retryAfter(_ headers: [AnyHashable: Any]) -> TimeInterval? {
        for (k, v) in headers where (k as? String)?.lowercased() == "retry-after" {
            if let s = v as? String, let n = TimeInterval(s.trimmingCharacters(in: .whitespaces)), n >= 0 { return n }
        }
        return nil
    }
}
