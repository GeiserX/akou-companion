// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// One live session on akou's `GET /v1/live`: one WebSocket, from `hello` to `closed`.
///
/// There is no resume. After a network drop the caller opens a new session and keeps sending from
/// the current page; the pages it missed are in the phone's file and the final pass covers them.
public actor LiveClient {
    public enum Event: Sendable, Equatable {
        case words(LiveWords)
        /// The server finished after `stop`.
        case closed
        case error(LiveError)
        /// The socket ended. `code` is the WebSocket close code when the server sent one
        /// (1000 after `closed`, or a `LiveCloseCode`).
        case disconnected(code: Int?)
    }

    public enum Failure: Error, Equatable {
        /// The upgrade was refused (401 for a bad key, 404 on a server without the route) or the
        /// connection failed before it; `status` is the HTTP status when there was an answer.
        case handshake(status: Int?)
        /// The server answered `hello` with an error and closed the socket.
        case refused(LiveError, closeCode: Int?)
        /// The socket closed before `ready` without an error message.
        case closedBeforeReady(closeCode: Int?)
    }

    /// The server's answer to `hello`.
    public nonisolated let ready: LiveReady
    /// Everything the server sends after `ready`, ending with `.disconnected`.
    public nonisolated let events: AsyncStream<Event>

    private let task: URLSessionWebSocketTask
    private let continuation: AsyncStream<Event>.Continuation
    private var pinger: Task<Void, Never>?

    /// Opens a session: connects with the key as a bearer on the upgrade request, sends `hello`,
    /// waits for `ready`, then sends `headerPages` (the OpusHead and OpusTags pages for `ogg-opus`).
    public static func open(
        baseURL: URL,
        key: String,
        hello: LiveHello,
        headerPages: [Data],
        session: URLSession = .shared,
        pingInterval: Duration = .seconds(20)
    ) async throws -> LiveClient {
        var req = URLRequest(url: try Endpoint.live(baseURL))
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        let task = session.webSocketTask(with: req)
        task.resume()

        do {
            try await task.send(.string(LiveClientMessage.hello(hello).json()))
        } catch {
            task.cancel()
            throw Failure.handshake(status: (task.response as? HTTPURLResponse)?.statusCode)
        }

        let ready: LiveReady
        do {
            ready = try await awaitReady(task)
        } catch let f as Failure {
            task.cancel()
            throw f
        } catch {
            task.cancel()
            if let status = (task.response as? HTTPURLResponse)?.statusCode, status != 101 {
                throw Failure.handshake(status: status)
            }
            throw Failure.closedBeforeReady(closeCode: closeCode(task))
        }

        for page in headerPages {
            try await task.send(.data(page))
        }
        let client = LiveClient(task: task, ready: ready)
        await client.start(pingInterval: pingInterval)
        return client
    }

    private init(task: URLSessionWebSocketTask, ready: LiveReady) {
        self.task = task
        self.ready = ready
        (events, continuation) = AsyncStream.makeStream(of: Event.self, bufferingPolicy: .unbounded)
    }

    /// Sends one audio frame: one Ogg page for `ogg-opus`, raw samples for `pcm16`.
    public func send(page: Data) async throws {
        try await task.send(.data(page))
    }

    /// Ends the recording: the server sends the last words with `final: true`, then `closed`.
    public func stop() async throws {
        try await task.send(.string(LiveClientMessage.stop.json()))
    }

    /// Drops the socket without `stop`, for example when the app is about to be suspended.
    public func cancel() {
        pinger?.cancel()
        task.cancel(with: .normalClosure, reason: nil)
    }

    private func start(pingInterval: Duration) {
        let task = self.task
        let continuation = self.continuation
        Task {
            while true {
                do {
                    let message = try await task.receive()
                    guard case let .string(text) = message, let parsed = try? LiveServerMessage.parse(text) else { continue }
                    switch parsed {
                    case let .words(w): continuation.yield(.words(w))
                    case .closed: continuation.yield(.closed)
                    case let .error(e): continuation.yield(.error(e))
                    case .ready, .unknown: continue
                    }
                } catch {
                    continuation.yield(.disconnected(code: Self.closeCode(task)))
                    continuation.finish()
                    self.stopPinging()
                    return
                }
            }
        }
        pinger = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: pingInterval)
                if Task.isCancelled { return }
                task.sendPing { _ in }
            }
        }
    }

    private func stopPinging() {
        pinger?.cancel()
    }

    private static func awaitReady(_ task: URLSessionWebSocketTask) async throws -> LiveReady {
        while true {
            let message = try await task.receive()
            guard case let .string(text) = message else { continue }
            switch try LiveServerMessage.parse(text) {
            case let .ready(r):
                return r
            case let .error(e):
                // The server closes right after an error; read until the close to learn its code.
                while (try? await task.receive()) != nil {}
                throw Failure.refused(e, closeCode: closeCode(task))
            default:
                continue
            }
        }
    }

    private static func closeCode(_ task: URLSessionWebSocketTask) -> Int? {
        let raw = task.closeCode.rawValue
        return raw == 0 ? nil : raw
    }
}
