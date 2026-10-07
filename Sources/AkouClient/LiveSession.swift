// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// Live text for one recording, across as many sockets as the network needs.
///
/// The recorder hands every page to `send(page:endsAt:)` and never waits on it: the file is the
/// recording, and this only decides what reaches the server (docs/PROTOCOL.md, "Reconnecting").
///
/// - Pages queue for the socket, at most `maxQueuedPages` (25 pages of 200 ms, 5 s of audio, far
///   under the server's 30 s `too_fast` limit). A page that finds the queue full means the socket
///   is behind: the session is dropped together with its queue, never flushed.
/// - After a drop (`behind`, `too_fast`, `stream_lost`, or the network) it waits 1, 2, 4, 8, then
///   30 s between attempts, and opens a new session: `hello`, the same two header pages, then the
///   pages made from then on. Pages made while no socket was open stay in the file only; the next
///   session's first page reports the hole as a `gap`.
/// - Refusals a retry cannot fix (`key_revoked`, `no_live_engine`, `engine_busy`, `unknown_model`,
///   `unsupported_language`, a refused upgrade) end live text for the recording: `off(reason)`
///   with the server's error code.
public actor LiveSession {
    public enum State: Sendable, Equatable {
        /// Opening a socket. Pages made meanwhile wait in the queue and go out once it is ready.
        case connecting
        /// The socket is open and `engine` makes the words.
        case live(engine: String)
        /// No socket, waiting to reopen; `reason` is the server's error code, `behind` or `network`.
        case paused(reason: String)
        /// Live text is over for this recording: `stopped`, or why it cannot go on.
        case off(reason: String)
    }

    public enum Event: Sendable, Equatable {
        case words(LiveWords)
        /// Live text has nothing between these two points of the recording, in seconds; the final
        /// transcript fills it. Sent when a session's first page goes out after a hole.
        case gap(from: Double, to: Double)
        case state(State)
    }

    public struct Policy: Sendable {
        /// The most pages waiting for the socket; one more drops the session.
        public var maxQueuedPages: Int
        /// The waits before each reopen; the last repeats.
        public var backoff: [Duration]
        /// How long `stop()` waits for the server's last words.
        public var stopTimeout: Duration
        public var pingInterval: Duration

        public init(
            maxQueuedPages: Int = 25,
            backoff: [Duration] = [1, 2, 4, 8, 30].map { .seconds($0) },
            stopTimeout: Duration = .seconds(10),
            pingInterval: Duration = .seconds(20)
        ) {
            precondition(maxQueuedPages > 0 && !backoff.isEmpty)
            self.maxQueuedPages = maxQueuedPages
            self.backoff = backoff
            self.stopTimeout = stopTimeout
            self.pingInterval = pingInterval
        }
    }

    /// Error codes worth a new session; every other refusal is final.
    static let retryable: Set<String> = ["too_fast", "stream_lost", "behind", "network"]
    /// A session that stayed open this long starts the backoff over.
    static let healthySession: Duration = .seconds(30)

    public nonisolated let events: AsyncStream<Event>
    private let continuation: AsyncStream<Event>.Continuation
    public private(set) var state: State = .off(reason: "idle")

    private let baseURL: URL
    private let key: String
    private let hello: LiveHello
    private let headerPages: [Data]
    private let policy: Policy
    private let urlSession: URLSession

    private struct Page {
        var data: Data
        var start: Double
        var end: Double
    }

    /// Bumped whenever a socket is given up, so late callbacks from an old one change nothing.
    private var generation = 0
    private var client: LiveClient?
    private var queue: [Page] = []
    private var drainingGeneration: Int?
    /// Where the newest page handed in ends: the recording's position.
    private var position: Double = 0
    /// Where the audio the server has received ends.
    private var covered: Double = 0
    private var sessionSentAudio = false
    private var lastError: String?
    private var attempt = 0
    private var liveSince: ContinuousClock.Instant?
    private var stopping = false
    private var stopSent = false
    private var stopWaiters: [CheckedContinuation<Void, Never>] = []
    private var timer: Task<Void, Never>?

    /// For tests: sessions dropped for falling behind, and the longest the queue got.
    var drops = 0
    var queueHighWater = 0

    public init(
        baseURL: URL,
        key: String,
        hello: LiveHello,
        headerPages: [Data],
        policy: Policy = Policy(),
        session: URLSession = AkouSession.shared
    ) {
        self.baseURL = baseURL
        self.key = key
        self.hello = hello
        self.headerPages = headerPages
        self.policy = policy
        self.urlSession = session
        (events, continuation) = AsyncStream.makeStream(of: Event.self, bufferingPolicy: .unbounded)
    }

    /// Opens the first session.
    public func start() {
        guard state == .off(reason: "idle") else { return }
        open()
    }

    /// Hands over the page just written to the file; `end` is where it ends in the recording, in
    /// seconds. Never waits for the network.
    public func send(page: Data, endsAt end: Double) {
        let p = Page(data: page, start: position, end: end)
        position = end
        guard !stopping else { return }
        switch state {
        case .connecting:
            queue.append(p)
            if queue.count > policy.maxQueuedPages { queue.removeFirst() }
        case .live:
            if queue.count >= policy.maxQueuedPages {
                drops += 1
                drop(reason: "behind")
                return
            }
            queue.append(p)
            drain()
        case .paused, .off:
            return
        }
        queueHighWater = max(queueHighWater, queue.count)
    }

    /// The recording ended: sends what is queued, then `stop`, and waits (at most `stopTimeout`)
    /// for the server's last words and `closed`.
    public func stop() async {
        guard !stopping else { return }
        if case .off = state { return }
        stopping = true
        guard case .live = state else { return end("stopped") }
        let gen = generation
        timer?.cancel()
        timer = Task { [policy] in
            try? await Task.sleep(for: policy.stopTimeout)
            self.stopTimedOut(gen)
        }
        drain()
        await withCheckedContinuation { stopWaiters.append($0) }
    }

    /// Ends live text at once, without waiting for the server.
    public func cancel() {
        if case .off = state { return }
        end("cancelled")
    }

    // MARK: - Sessions

    private func open() {
        generation += 1
        let gen = generation
        lastError = nil
        sessionSentAudio = false
        set(.connecting)
        Task { await self.connect(gen) }
    }

    private func connect(_ gen: Int) async {
        do {
            let c = try await LiveClient.open(
                baseURL: baseURL, key: key, hello: hello, headerPages: headerPages,
                session: urlSession, pingInterval: policy.pingInterval
            )
            guard gen == generation else {
                await c.cancel()
                return
            }
            client = c
            liveSince = .now
            set(.live(engine: c.ready.engine))
            Task {
                for await e in c.events { self.handle(e, gen) }
            }
            if stopping { return end("stopped") }
            drain()
        } catch {
            guard gen == generation else { return }
            let reason = Self.reason(for: error)
            if Self.retryable.contains(reason) { retry(after: reason) } else { end(reason) }
        }
    }

    private func handle(_ event: LiveClient.Event, _ gen: Int) {
        guard gen == generation else { return }
        switch event {
        case let .words(w):
            continuation.yield(.words(w))
        case let .error(e):
            lastError = e.code
        case .closed:
            break
        case let .disconnected(code):
            client = nil
            if stopping { return end("stopped") }
            let reason = lastError ?? Self.reason(forClose: code)
            if Self.retryable.contains(reason) { drop(reason: reason) } else { end(reason) }
        }
    }

    /// Sends queued pages one at a time, then `stop` once stopping and empty.
    private func drain() {
        guard let c = client, drainingGeneration != generation else { return }
        let gen = generation
        drainingGeneration = gen
        Task {
            while gen == generation, let p = queue.first {
                if !sessionSentAudio {
                    sessionSentAudio = true
                    if p.start > covered + 1e-6 { continuation.yield(.gap(from: covered, to: p.start)) }
                }
                do {
                    try await c.send(page: p.data)
                } catch {
                    if gen == generation { drop(reason: "network") }
                    break
                }
                guard gen == generation else { break }
                queue.removeFirst()
                covered = p.end
            }
            if drainingGeneration == gen { drainingGeneration = nil }
            if gen == generation, stopping, !stopSent, queue.isEmpty {
                stopSent = true
                do { try await c.stop() } catch { if gen == generation { end("stopped") } }
            }
        }
    }

    /// Gives up the socket and its queue, then waits to reopen.
    private func drop(reason: String) {
        abandonSocket()
        if let since = liveSince, since.duration(to: .now) >= Self.healthySession { attempt = 0 }
        liveSince = nil
        retry(after: reason)
    }

    private func retry(after reason: String) {
        if stopping { return end("stopped") }
        set(.paused(reason: reason))
        let delay = policy.backoff[min(attempt, policy.backoff.count - 1)]
        attempt += 1
        let gen = generation
        timer?.cancel()
        timer = Task {
            try? await Task.sleep(for: delay)
            self.reopen(gen)
        }
    }

    private func reopen(_ gen: Int) {
        guard gen == generation, case .paused = state else { return }
        open()
    }

    private func stopTimedOut(_ gen: Int) {
        guard stopping, gen == generation else { return }
        end("stopped")
    }

    private func end(_ reason: String) {
        abandonSocket()
        timer?.cancel()
        timer = nil
        set(.off(reason: reason))
        continuation.finish()
        let waiters = stopWaiters
        stopWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }

    private func abandonSocket() {
        generation += 1
        if let c = client { Task { await c.cancel() } }
        client = nil
        queue.removeAll()
    }

    private func set(_ new: State) {
        guard new != state else { return }
        state = new
        continuation.yield(.state(new))
    }

    // MARK: - Reasons

    static func reason(for error: Error) -> String {
        switch error {
        case let LiveClient.Failure.refused(e, _):
            return e.code
        case let LiveClient.Failure.handshake(status):
            switch status {
            case nil: return "network"
            case 401: return "unauthorized"
            case 404: return "no_live_route"
            // akou answers the upgrade with a plain 503 when no streaming model is on disk; any
            // other 5xx is a proxy or a restart, worth another try.
            case 503: return "no_live_engine"
            case let s? where s >= 500: return "network"
            case let s?: return "http_\(s)"
            }
        case let LiveClient.Failure.closedBeforeReady(code):
            return reason(forClose: code)
        case is Endpoint.Failure:
            return "bad_url"
        default:
            return "network"
        }
    }

    /// A close that came without an error frame.
    static func reason(forClose code: Int?) -> String {
        switch code.flatMap(LiveCloseCode.init(rawValue:)) {
        case .badPage: return "bad_message"
        case .keyRevoked: return "key_revoked"
        case .engineBusy: return "engine_busy"
        case .streamLost: return "stream_lost"
        case .noLiveEngine: return "no_live_engine"
        case nil: return "network"
        }
    }
}
