// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A stand-in for akou's job routes as a `URLProtocol`, so a `URLSession` built with it talks to
/// this instead of the network. It keeps akou's idempotency rule (src/main/api/routes/jobs.ts):
/// a repeated `Idempotency-Key` with the same options answers 200 with the first job, with other
/// options 422 `idempotency_conflict`; `keep_audio` and `metadata` are not compared. Each answer
/// carries the fields of akou's `jobView`.
final class FakeAkouHTTP: URLProtocol, @unchecked Sendable {
    /// What the next submit does, in order; when the list is empty a submit is answered normally.
    enum Script {
        /// Make the job, then drop the connection before the answer: the client never hears of it.
        case acceptThenDrop
        /// 429 `queue_full` with `Retry-After`.
        case queueFull(retryAfter: Int)
        /// Drop the connection without making a job.
        case drop
    }

    struct Seen {
        var method: String
        var path: String
        var query: String?
        var headers: [String: String]
        var fields: [String: String]
        var fileName: String?
        var fileBytes: Data?
    }

    struct StoredJob {
        var id: String
        var key: String
        var options: [String: String]
        var keepAudio: Bool
        var metadata: String?
        var title: String?
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var _scripts: [Script] = []
    nonisolated(unsafe) private static var _seen: [Seen] = []
    nonisolated(unsafe) private static var _jobs: [StoredJob] = []
    /// Simulates a server that does not keep uploads: every job answers `keep_audio: false`.
    nonisolated(unsafe) private static var _ignoreKeepAudio = false
    static let key = "ak_test"

    static func reset() {
        lock.withLock { _scripts = []; _seen = []; _jobs = []; _ignoreKeepAudio = false }
    }
    static func script(_ s: Script...) { lock.withLock { _scripts.append(contentsOf: s) } }
    static func ignoreKeepAudio() { lock.withLock { _ignoreKeepAudio = true } }
    static var seen: [Seen] { lock.withLock { _seen } }
    static var jobs: [StoredJob] { lock.withLock { _jobs } }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeAkouHTTP.self]
        c.urlCache = nil
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let url = request.url!
        let method = request.httpMethod ?? "GET"
        var headers: [String: String] = [:]
        for (k, v) in request.allHTTPHeaderFields ?? [:] { headers[k.lowercased()] = v }
        let body = Self.body(of: request)
        var seen = Seen(method: method, path: url.path, query: url.query, headers: headers, fields: [:])
        if let type = headers["content-type"], type.hasPrefix("multipart/form-data"),
           let boundary = type.components(separatedBy: "boundary=").last {
            let parts = Self.parts(body, boundary: boundary)
            for p in parts {
                if p.fileName != nil {
                    seen.fileName = p.fileName
                    seen.fileBytes = p.value
                } else {
                    seen.fields[p.name] = String(decoding: p.value, as: UTF8.self)
                }
            }
        }
        Self.lock.withLock { Self._seen.append(seen) }

        guard headers["authorization"] == "Bearer \(Self.key)" else {
            return answer(401, #"{"error":"unauthorized","message":"a valid bearer token is required"}"#)
        }
        switch (method, url.path) {
        case ("POST", "/v1/jobs"): submit(seen)
        case let ("GET", p) where p.hasPrefix("/v1/jobs/"):
            let id = String(p.dropFirst("/v1/jobs/".count))
            guard let job = Self.lock.withLock({ Self._jobs.first { $0.id == id } }) else {
                return answer(404, #"{"error":"not_found","message":"no such job"}"#)
            }
            answer(200, view(job))
        default:
            answer(404, #"{"error":"not_found","message":"no such route"}"#)
        }
    }

    private func submit(_ seen: Seen) {
        let script: Script? = Self.lock.withLock { Self._scripts.isEmpty ? nil : Self._scripts.removeFirst() }
        if case let .queueFull(after) = script {
            return answer(429, #"{"error":"queue_full","message":"the queue is full","retry_after_s":\#(after)}"#, ["Retry-After": String(after)])
        }
        if case .drop = script { return drop() }
        guard seen.fileBytes != nil else {
            return answer(400, #"{"error":"bad_field","message":"file is required"}"#)
        }
        // The options a repeated key is compared on: the file and the decoding options.
        var options = seen.fields.filter { ["preset", "model", "language", "diarize"].contains($0.key) }
        options["file"] = String(seen.fileBytes!.hashValue)
        let idem = seen.headers["idempotency-key"]
        let (status, job): (Int, StoredJob?) = Self.lock.withLock {
            if let idem, let existing = Self._jobs.first(where: { $0.key == idem }) {
                return existing.options == options ? (200, existing) : (422, existing)
            }
            let job = StoredJob(
                id: "j\(Self._jobs.count + 1)", key: idem ?? UUID().uuidString, options: options,
                keepAudio: !Self._ignoreKeepAudio && seen.fields["keep_audio"] == "true",
                metadata: seen.fields["metadata"], title: seen.fields["title"]
            )
            Self._jobs.append(job)
            return (202, job)
        }
        if status == 422 {
            return answer(422, #"{"error":"idempotency_conflict","message":"this Idempotency-Key was used for a request with another file","id":"\#(job!.id)","fields":["file"]}"#)
        }
        if case .acceptThenDrop = script { return drop() }
        answer(status, view(job!))
    }

    private func view(_ j: StoredJob) -> String {
        let title = j.title.map { "\"\($0)\"" } ?? "null"
        let audio = j.keepAudio ? #","audio":"/v1/jobs/\#(j.id)/audio""# : ""
        return #"{"id":"\#(j.id)","title":\#(title),"status":"queued","key_id":"k1","created_at":"2026-10-07T10:00:00.000Z","started_at":null,"finished_at":null,"preset":"fast","model":"parakeet-tdt-0.6b-v3","model_source":"preset","priority":0,"interactive":false,"keep_audio":\#(j.keepAudio),"language":"auto","languages":[],"diarize":false,"metadata":\#(j.metadata ?? "null"),"links":{"self":"/v1/jobs/\#(j.id)","result":"/v1/jobs/\#(j.id)/result","events":"/v1/events"\#(audio)}}"#
    }

    private func answer(_ status: Int, _ body: String, _ extra: [String: String] = [:]) {
        var headers = ["Content-Type": "application/json", "Cache-Control": "no-store"]
        headers.merge(extra) { _, b in b }
        let resp = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    private func drop() {
        client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
    }

    /// The request body: `httpBody`, or the stream an upload task hands a protocol.
    static func body(of request: URLRequest) -> Data {
        if let b = request.httpBody { return b }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var out = Data()
        var buf = [UInt8](repeating: 0, count: 64 * 1024)
        while stream.hasBytesAvailable {
            let n = stream.read(&buf, maxLength: buf.count)
            if n <= 0 { break }
            out.append(buf, count: n)
        }
        return out
    }

    struct Part { var name: String; var fileName: String?; var value: Data }

    /// A small multipart reader: enough for the bodies `Multipart.write` makes.
    static func parts(_ body: Data, boundary: String) -> [Part] {
        let delimiter = Data("--\(boundary)".utf8)
        var out: [Part] = []
        var chunks: [Data] = []
        var rest = body[...]
        while let r = rest.range(of: delimiter) {
            chunks.append(Data(rest[rest.startIndex..<r.lowerBound]))
            rest = rest[r.upperBound...]
        }
        for chunk in chunks.dropFirst() {
            guard let sep = chunk.range(of: Data("\r\n\r\n".utf8)) else { continue }
            let head = String(decoding: chunk[chunk.startIndex..<sep.lowerBound], as: UTF8.self)
            var value = Data(chunk[sep.upperBound...])
            if value.suffix(2) == Data("\r\n".utf8) { value.removeLast(2) }
            func param(_ n: String) -> String? {
                guard let r = head.range(of: "\(n)=\"") else { return nil }
                let tail = head[r.upperBound...]
                return tail.firstIndex(of: "\"").map { String(tail[..<$0]) }
            }
            guard let name = param("name") else { continue }
            out.append(Part(name: name, fileName: param("filename"), value: value))
        }
        return out
    }
}
