// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// The demo's akou server, inside the app: a `URLProtocol` every `AkouSession` carries, which
/// answers the requests to `baseURL` and nothing else. The real clients (`ServerProbe`,
/// `JobsClient`, `AuthorizedAudioLoader`) talk to it as to any server, and no request to it ever
/// leaves the phone: `.invalid` is a name no resolver answers, and this protocol takes the request
/// before the system would try.
///
/// It answers the routes the phone reads for a key that only uploads (`scopes: ["jobs"]`): the
/// server and the key, the list of jobs, a job, its JSON result, its audio with byte ranges, rename
/// and delete. Live text has no socket here: the app uses `DemoLiveText` for this server instead.
/// What it holds is in memory, put there by the app (`set`, `add`).
public final class DemoServer: URLProtocol, @unchecked Sendable {
    public static let baseURL = URL(string: "https://demo.akou.invalid")!
    public static let key = "ak_demo"
    /// The live engine the demo names, from `DemoLiveText`.
    public static let engine = "demo"

    /// One recording on the demo server: a done job with its audio file and its transcript.
    public struct Recording: Sendable, Equatable {
        public var id: String
        public var title: String
        public var workspace: String?
        public var createdAt: Date
        /// The Ogg Opus file the job's audio route answers with.
        public var audio: URL
        /// `GET /v1/jobs/{id}/result?format=json`, as akou writes it.
        public var result: Data

        public init(id: String, title: String, workspace: String?, createdAt: Date, audio: URL, result: Data) {
            self.id = id
            self.title = title
            self.workspace = workspace
            self.createdAt = createdAt
            self.audio = audio
            self.result = result
        }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var _recordings: [Recording] = []

    /// Every recording, newest first.
    public static var recordings: [Recording] { lock.withLock { _recordings } }

    /// Replaces every recording.
    public static func set(_ recordings: [Recording]) {
        lock.withLock { _recordings = recordings.sorted { $0.createdAt > $1.createdAt } }
    }

    /// Adds a recording, or replaces the one with its id.
    public static func add(_ recording: Recording) {
        lock.withLock {
            _recordings.removeAll { $0.id == recording.id }
            _recordings.append(recording)
            _recordings.sort { $0.createdAt > $1.createdAt }
        }
    }

    /// True for a request this protocol answers: one to the demo's host.
    public static func handles(_ url: URL?) -> Bool {
        url?.host(percentEncoded: false)?.lowercased() == baseURL.host(percentEncoded: false)
    }

    /// A transcript in akou's result format, one segment per sentence, from timed words.
    public static func result(words: [(w: String, s: Double, e: Double)], language: String = "en") -> Data {
        var segments: [ResultSegment] = []
        var open: [(w: String, s: Double, e: Double)] = []
        func close() {
            guard let first = open.first, let last = open.last else { return }
            segments.append(ResultSegment(s: first.s, e: last.e, text: open.map(\.w).joined(separator: " ")))
            open = []
        }
        for word in words {
            open.append(word)
            if let end = word.w.last, ".?!".contains(end) { close() }
        }
        close()
        let body = ResultBody(
            text: segments.map(\.text).joined(separator: " "),
            segments: segments,
            words: words.map { ResultWord(w: $0.w, s: $0.s, e: $0.e, c: 1) },
            language: language,
            durationS: words.last?.e ?? 0
        )
        return (try? JSONEncoder().encode(body)) ?? Data("{}".utf8)
    }

    // MARK: URLProtocol

    override public class func canInit(with request: URLRequest) -> Bool { handles(request.url) }
    override public class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override public func stopLoading() {}

    override public func startLoading() {
        guard let url = request.url else { return answer(400, error: "bad_request") }
        guard request.value(forHTTPHeaderField: "Authorization") == "Bearer \(Self.key)" else {
            return answer(401, error: "unauthorized", message: "a valid bearer token is required")
        }
        let method = request.httpMethod ?? "GET"
        // A job route names its id third: `v1/jobs/<id>` and `v1/jobs/<id>/<what>` read as `v1/jobs/:id...`.
        var parts = url.path(percentEncoded: false).split(separator: "/").map(String.init)
        var id = ""
        if parts.count >= 3, parts[0] == "v1", parts[1] == "jobs" {
            id = parts[2]
            parts[2] = ":id"
        }
        switch (method, parts.joined(separator: "/")) {
        case ("GET", "v1/server"):
            answer(200, Self.json(ServerBody()))
        case ("GET", "v1/keys/me"):
            answer(200, Self.json(KeyBody()))
        case ("GET", "v1/jobs"):
            answer(200, Self.json(JobListBody(jobs: Self.recordings.map(JobBody.init), cursor: nil)))
        case ("GET", "v1/jobs/:id"):
            guard let r = Self.recording(id) else { return notFound() }
            answer(200, Self.json(JobBody(r)))
        case ("GET", "v1/jobs/:id/result"):
            guard let r = Self.recording(id) else { return notFound() }
            answer(200, r.result)
        case ("GET", "v1/jobs/:id/audio"):
            guard let r = Self.recording(id) else { return notFound() }
            audio(r.audio)
        case ("PATCH", "v1/jobs/:id"):
            let title = (try? JSONDecoder().decode([String: String].self, from: Self.body(of: request)))?["title"]
            guard let title, !title.isEmpty else { return answer(400, error: "bad_field", message: "title is required") }
            let renamed: Recording? = Self.lock.withLock {
                guard let i = Self._recordings.firstIndex(where: { $0.id == id }) else { return nil }
                Self._recordings[i].title = title
                return Self._recordings[i]
            }
            guard let renamed else { return notFound() }
            answer(200, Self.json(JobBody(renamed)))
        case ("DELETE", "v1/jobs/:id"):
            let removed: Bool = Self.lock.withLock {
                let before = Self._recordings.count
                Self._recordings.removeAll { $0.id == id }
                return Self._recordings.count < before
            }
            removed ? answer(200, Data(#"{"deleted":true}"#.utf8)) : notFound()
        default:
            answer(404, error: "not_found", message: "the demo server has no such route")
        }
    }

    private static func recording(_ id: String) -> Recording? {
        lock.withLock { _recordings.first { $0.id == id } }
    }

    /// The file, or the bytes of its `Range` with 206 and `Content-Range`, as akou answers.
    private func audio(_ file: URL) {
        guard let data = try? Data(contentsOf: file) else { return answer(410, error: "gone") }
        let total = data.count
        var headers = ["Content-Type": "audio/ogg", "Accept-Ranges": "bytes"]
        if let range = request.value(forHTTPHeaderField: "Range"), let (from, to) = Self.range(range, total: total) {
            headers["Content-Range"] = "bytes \(from)-\(to)/\(total)"
            headers["Content-Length"] = String(to - from + 1)
            return send(206, data.subdata(in: from..<(to + 1)), headers)
        }
        headers["Content-Length"] = String(total)
        send(200, data, headers)
    }

    /// `bytes=a-b`, `bytes=a-` or `bytes=-n`, clamped to the file; nil when it is not one of those.
    static func range(_ header: String, total: Int) -> (Int, Int)? {
        guard total > 0, header.hasPrefix("bytes=") else { return nil }
        let spec = header.dropFirst("bytes=".count).split(separator: ",").first.map(String.init) ?? ""
        let ends = spec.split(separator: "-", omittingEmptySubsequences: false).map { Int($0.trimmingCharacters(in: .whitespaces)) }
        guard ends.count == 2 else { return nil }
        switch (ends[0], ends[1]) {
        case let (a?, b?) where a <= b && a < total: return (a, min(b, total - 1))
        case let (a?, nil) where a < total: return (a, total - 1)
        case let (nil, n?) where n > 0: return (max(0, total - n), total - 1)
        default: return nil
        }
    }

    private func notFound() { answer(404, error: "not_found", message: "no such job") }

    private func answer(_ status: Int, error: String, message: String? = nil) {
        answer(status, Self.json(["error": error, "message": message ?? error]))
    }

    private func answer(_ status: Int, _ body: Data) {
        send(status, body, ["Content-Type": "application/json", "Cache-Control": "no-store"])
    }

    private func send(_ status: Int, _ body: Data, _ headers: [String: String]) {
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    private static func json(_ value: some Encodable) -> Data {
        (try? JSONEncoder().encode(value)) ?? Data("{}".utf8)
    }

    /// The request body: `httpBody`, or the stream a session hands a protocol instead.
    private static func body(of request: URLRequest) -> Data {
        if let b = request.httpBody { return b }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var out = Data()
        var buf = [UInt8](repeating: 0, count: 16 * 1024)
        while stream.hasBytesAvailable {
            let n = stream.read(&buf, maxLength: buf.count)
            if n <= 0 { break }
            out.append(buf, count: n)
        }
        return out
    }

    // MARK: The answers' shapes, as akou writes them

    private struct ServerBody: Encodable {
        var name = "akou"
        var version = "demo"
        var mode = "server"
        var capabilities = ["jobs": true, "live": true]
        var live = ["engines": [DemoServer.engine]]
    }

    private struct KeyBody: Encodable {
        var id = "key_demo"
        var name = "demo"
        var scopes = ["jobs"]
    }

    private struct JobListBody: Encodable {
        var jobs: [JobBody]
        var cursor: Int?

        func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(jobs, forKey: .jobs)
            try c.encode(cursor, forKey: .cursor)
        }

        enum CodingKeys: String, CodingKey { case jobs, cursor }
    }

    private struct JobBody: Encodable {
        var id: String
        var title: String
        var status = "done"
        var createdAt: String
        var finishedAt: String
        var preset = "fast"
        var model = DemoServer.engine
        var language = "en"
        var keepAudio = true
        var metadata: CompanionMetadata

        init(_ r: Recording) {
            let iso = ISO8601DateFormatter()
            iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            id = r.id
            title = r.title
            createdAt = iso.string(from: r.createdAt)
            finishedAt = createdAt
            metadata = CompanionMetadata(recordingID: r.id, workspace: r.workspace)
        }

        enum CodingKeys: String, CodingKey {
            case id, title, status, preset, model, language, metadata
            case createdAt = "created_at"
            case finishedAt = "finished_at"
            case keepAudio = "keep_audio"
        }
    }

    private struct ResultBody: Encodable {
        var text: String
        var segments: [ResultSegment]
        var words: [ResultWord]
        var language: String
        var durationS: Double

        enum CodingKeys: String, CodingKey {
            case text, segments, words, language
            case durationS = "duration_s"
        }
    }

    private struct ResultSegment: Encodable {
        var s: Double
        var e: Double
        var text: String
    }

    private struct ResultWord: Encodable {
        var w: String
        var s: Double
        var e: Double
        var c: Double
    }
}
