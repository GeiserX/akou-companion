// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// The recordings waiting to reach the server, kept on disk so they survive an app restart.
///
/// Each recording moves `pending` -> `uploading` -> `submitted(jobID)` -> `done`. It is keyed by its
/// recording id, which is also the upload's `Idempotency-Key`, so however many times an upload is
/// retried the server makes one job and every retry gets that job back. The queue does no I/O of
/// its own beyond its state file and the deletion of a recording it has confirmed on the server:
/// a transport (the foreground `drain`, or the app's background `URLSession`) runs the requests
/// and reports each answer here.
public actor UploadQueue {
    public struct Item: Codable, Sendable, Equatable {
        public var submission: JobsClient.Submission
        /// The recording's file name inside the queue's audio directory. A name, not a path: an
        /// app's container moves when the app is updated.
        public var fileName: String
        public var state: State
        /// Failed attempts in a row, for the backoff.
        public var attempts: Int
        /// Not tried again before this time (a backoff or a server's `Retry-After`).
        public var notBefore: Date?
        /// The last failure, for the recordings list.
        public var lastError: String?

        public var recordingID: String { submission.recordingID }
    }

    public enum State: Codable, Sendable, Equatable {
        case pending
        case uploading
        /// The server answered the upload with this job.
        case submitted(jobID: String)
        /// The server's job was read back; `keptOnServer` is its `keep_audio`.
        case done(jobID: String, keptOnServer: Bool)
        /// Stopped until `retryParked()`: an answer that a retry would only repeat (a 4xx other
        /// than 408 and 429, `idempotency_conflict`, a missing local file).
        case parked(status: Int?, code: String?)
    }

    /// How a request ended, as a transport reports it.
    public enum Answer: Sendable, Equatable {
        case http(status: Int, headers: [String: String], body: Data)
        /// No answer: no network, a dropped connection, a timeout.
        case unreachable(String)
    }

    /// The first retry waits this long, doubling per failure up to `maxBackoff`.
    public static let firstBackoff: TimeInterval = 5
    public static let maxBackoff: TimeInterval = 15 * 60

    public nonisolated let audioDirectory: URL
    private let stateFile: URL
    private let now: @Sendable () -> Date
    private let keepLocalCopy: @Sendable () -> Bool
    private var byID: [String: Item]
    private var order: [String]

    /// Loads the queue from `directory` (created if missing). `keepLocalCopy` is read each time a
    /// recording is confirmed, so a change in the settings applies to the next one.
    public init(
        directory: URL,
        audioDirectory: URL,
        now: @escaping @Sendable () -> Date = { Date() },
        keepLocalCopy: @escaping @Sendable () -> Bool
    ) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        self.audioDirectory = audioDirectory
        stateFile = directory.appending(path: "uploads.json")
        self.now = now
        self.keepLocalCopy = keepLocalCopy
        var items: [Item] = []
        if let data = try? Data(contentsOf: stateFile) {
            items = try JSONDecoder().decode([Item].self, from: data)
        }
        byID = Dictionary(items.map { ($0.recordingID, $0) }, uniquingKeysWith: { a, _ in a })
        order = items.map(\.recordingID)
    }

    // MARK: - Reading

    public var items: [Item] { order.compactMap { byID[$0] } }

    public func item(_ recordingID: String) -> Item? { byID[recordingID] }

    public nonisolated func audioURL(_ item: Item) -> URL { audioDirectory.appending(path: item.fileName) }

    /// When the earliest waiting item is due, for a timer; nil when nothing waits on a time.
    public func nextWake() -> Date? {
        items.filter { if case .pending = $0.state { true } else if case .submitted = $0.state { true } else { false } }
            .compactMap(\.notBefore).min()
    }

    // MARK: - Transitions

    /// Adds a finished recording. Adding the same recording again changes nothing.
    @discardableResult
    public func enqueue(_ submission: JobsClient.Submission, fileName: String) throws -> Item {
        if let existing = byID[submission.recordingID] { return existing }
        let item = Item(submission: submission, fileName: fileName, state: .pending, attempts: 0, notBefore: nil, lastError: nil)
        byID[item.recordingID] = item
        order.append(item.recordingID)
        try save()
        return item
    }

    /// The pending items whose time has come, now marked `uploading`. The caller uploads each one
    /// and reports with `uploadEnded`.
    public func claimDue() throws -> [Item] {
        let t = now()
        var claimed: [Item] = []
        for id in order {
            guard var item = byID[id], item.state == .pending, (item.notBefore ?? .distantPast) <= t else { continue }
            item.state = .uploading
            byID[id] = item
            claimed.append(item)
        }
        if !claimed.isEmpty { try save() }
        return claimed
    }

    /// The upload of `recordingID` ended with `answer`.
    public func uploadEnded(_ recordingID: String, _ answer: Answer) throws {
        guard var item = byID[recordingID] else { return }
        if item.state == .pending, case let .http(status, _, _) = answer, (200..<300).contains(status) {
            // A transfer given up on after a restart answered after all: its job is the truth.
            item.state = .uploading
        }
        guard item.state == .uploading else { return }
        switch answer {
        case let .http(status, headers, body) where (200..<300).contains(status):
            if let submitted = try? JobsClient.submitted(status: status, headers: headers, body: body) {
                item.state = .submitted(jobID: submitted.job.id)
                item.attempts = 0
                item.notBefore = nil
                item.lastError = nil
            } else {
                // A 2xx that is not akou's job (a captive portal, a proxy page): try again later.
                retryLater(&item, after: nil, error: "HTTP \(status) without a job")
                item.state = .pending
            }
        case let .http(status, headers, body):
            let failure = Self.failure(status: status, headers: headers, body: body)
            if Self.isRetryable(status) {
                item.state = .pending
                retryLater(&item, after: failure.retryAfter, error: failure.text)
            } else {
                item.state = .parked(status: status, code: failure.code)
                item.lastError = failure.text
                item.notBefore = nil
            }
        case let .unreachable(why):
            item.state = .pending
            retryLater(&item, after: nil, error: why)
        }
        byID[recordingID] = item
        try save()
    }

    /// The upload could not start: the recording's file is gone, or its body could not be written.
    public func uploadCouldNotStart(_ recordingID: String, _ why: String) throws {
        guard var item = byID[recordingID], item.state == .uploading else { return }
        item.state = .parked(status: nil, code: "local_file")
        item.lastError = why
        byID[recordingID] = item
        try save()
    }

    /// The submitted items whose time has come: the caller reads each job back with
    /// `GET /v1/jobs/{id}` and reports with `confirmEnded`.
    public func dueForConfirm() -> [(recordingID: String, jobID: String)] {
        let t = now()
        return items.compactMap { item in
            guard case let .submitted(jobID) = item.state, (item.notBefore ?? .distantPast) <= t else { return nil }
            return (item.recordingID, jobID)
        }
    }

    /// The read-back of a submitted job ended with `answer`. On a job, the item is done, and the
    /// local file is deleted when the server says it keeps the audio and the settings do not ask
    /// for a copy on the phone. Anything else keeps the file.
    public func confirmEnded(_ recordingID: String, _ answer: Answer) throws {
        guard var item = byID[recordingID], case let .submitted(jobID) = item.state else { return }
        switch answer {
        case let .http(status, _, body) where (200..<300).contains(status):
            guard let job = try? JSONDecoder().decode(Job.self, from: body), job.id == jobID else {
                retryLater(&item, after: nil, error: "HTTP \(status) without the job")
                break
            }
            if job.keepAudio && !keepLocalCopy() {
                try? FileManager.default.removeItem(at: audioURL(item))
            }
            item.state = .done(jobID: jobID, keptOnServer: job.keepAudio)
            item.attempts = 0
            item.notBefore = nil
            item.lastError = nil
        case let .http(status, headers, body):
            let failure = Self.failure(status: status, headers: headers, body: body)
            if Self.isRetryable(status) {
                retryLater(&item, after: failure.retryAfter, error: failure.text)
            } else {
                // 404 or 410: the job went before it was read back (deleted elsewhere, or expired).
                // The phone's file may be the only copy left, so it stays, and a person decides.
                item.state = .parked(status: status, code: failure.code)
                item.lastError = failure.text
                item.notBefore = nil
            }
        case let .unreachable(why):
            retryLater(&item, after: nil, error: why)
        }
        byID[recordingID] = item
        try save()
    }

    /// After a restart: items left `uploading` that no live transfer carries go back to `pending`.
    /// The background session reports its live tasks; a foreground-only caller passes none.
    public func requeueInterrupted(except live: Set<String> = []) throws {
        var changed = false
        for (id, var item) in byID where item.state == .uploading && !live.contains(id) {
            item.state = .pending
            byID[id] = item
            changed = true
        }
        if changed { try save() }
    }

    /// Parked items go back to `pending`, for example after the key or the URL changed. A retry
    /// sends the same `Idempotency-Key`, so a recording the server already has gets its first job.
    public func retryParked() throws {
        var changed = false
        for (id, var item) in byID {
            guard case .parked = item.state else { continue }
            item.state = .pending
            item.attempts = 0
            item.notBefore = nil
            byID[id] = item
            changed = true
        }
        if changed { try save() }
    }

    // MARK: - Helpers

    /// 408, 429 and 5xx (and no status at all) are worth repeating; any other 4xx is not.
    static func isRetryable(_ status: Int) -> Bool {
        status == 408 || status == 429 || status >= 500 || status < 400
    }

    private func retryLater(_ item: inout Item, after retryAfter: TimeInterval?, error: String) {
        item.attempts += 1
        let backoff = min(Self.maxBackoff, Self.firstBackoff * pow(2, Double(item.attempts - 1)))
        item.notBefore = now().addingTimeInterval(retryAfter ?? backoff)
        item.lastError = error
    }

    private static func failure(status: Int, headers: [String: String], body: Data) -> (code: String?, retryAfter: TimeInterval?, text: String) {
        let parsed = try? JSONDecoder().decode(AkouErrorBody.self, from: body)
        let retryAfter = JobsClient.retryAfter(headers) ?? parsed?.retryAfterS.map(TimeInterval.init)
        let text = [("HTTP \(status)"), parsed?.error, parsed?.message].compactMap { $0 }.joined(separator: ": ")
        return (parsed?.error, retryAfter, text)
    }

    private func save() throws {
        let data = try JSONEncoder().encode(items)
        #if os(iOS) || os(watchOS)
        // Readable after the first unlock, so a background upload can update it while the phone is locked.
        try data.write(to: stateFile, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: stateFile, options: .atomic)
        #endif
    }
}

/// Runs the queue's requests. `URLSessionTransport` is the foreground one; the app's background
/// uploads go through `BackgroundUploader` instead, which reports to the same queue.
public protocol UploadTransport: Sendable {
    func upload(_ request: URLRequest, fromFile: URL) async -> UploadQueue.Answer
    func send(_ request: URLRequest) async -> UploadQueue.Answer
}

public struct URLSessionTransport: UploadTransport {
    let session: URLSession

    public init(session: URLSession = AkouSession.shared) { self.session = session }

    public func upload(_ request: URLRequest, fromFile: URL) async -> UploadQueue.Answer {
        await Self.answer { try await session.upload(for: request, fromFile: fromFile) }
    }

    public func send(_ request: URLRequest) async -> UploadQueue.Answer {
        await Self.answer { try await session.data(for: request) }
    }

    static func answer(_ run: () async throws -> (Data, URLResponse)) async -> UploadQueue.Answer {
        do {
            let (body, response) = try await run()
            guard let http = response as? HTTPURLResponse else { return .unreachable("not an HTTP answer") }
            return .http(status: http.statusCode, headers: headers(http), body: body)
        } catch {
            return .unreachable(error.localizedDescription)
        }
    }

    public static func headers(_ http: HTTPURLResponse) -> [String: String] {
        var out: [String: String] = [:]
        for (k, v) in http.allHeaderFields {
            if let k = k as? String, let v = v as? String { out[k.lowercased()] = v }
        }
        return out
    }
}

extension UploadQueue {
    /// One pass over the queue in the foreground: uploads every due item, then reads back every
    /// submitted one. Multipart bodies are written to `scratch` and deleted after each upload.
    public func drain(client: JobsClient, transport: UploadTransport, scratch: URL) async throws {
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        for item in try claimDue() {
            let body = scratch.appending(path: "\(item.recordingID).multipart")
            let request: URLRequest
            do {
                request = try client.submitRequest(item.submission, audio: audioURL(item), bodyFile: body)
            } catch {
                try? FileManager.default.removeItem(at: body)
                try uploadCouldNotStart(item.recordingID, "\(error)")
                continue
            }
            let answer = await transport.upload(request, fromFile: body)
            try? FileManager.default.removeItem(at: body)
            try uploadEnded(item.recordingID, answer)
        }
        for (recordingID, jobID) in dueForConfirm() {
            guard let request = try? client.jobRequest(jobID) else { continue }
            try confirmEnded(recordingID, await transport.send(request))
        }
    }
}
