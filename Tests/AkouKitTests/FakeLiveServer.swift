// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Network

/// A local stand-in for akou's `GET /v1/live`, on Network.framework's WebSocket server: it checks
/// the bearer on the upgrade, answers `hello` with `ready`, echoes one token per binary frame and
/// answers `stop` with the last words, `closed` and a normal close. Every frame has exactly the
/// fields akou's server sends (src/main/server/live.ts), no more.
///
/// Each session (a socket that sent `hello`) takes the next of `behaviours`, the last one repeating,
/// so a test can make the first session fail and the reopened one work.
final class FakeLiveServer: @unchecked Sendable {
    enum Behaviour: Equatable {
        case normal
        /// Answer `hello` with an `engine_busy` error and close 4409.
        case engineBusy
        /// After this many audio pages, report `too_fast` and close 4400, as akou does once more
        /// than 30 s of audio waits for the engine.
        case tooFast(afterPages: Int)
        /// After this many audio pages, report `key_revoked` and close 4401.
        case keyRevoked(afterPages: Int)
        /// After this many audio pages, stop reading the socket: a server or network that stalls.
        case stall(afterPages: Int)
    }

    /// One frame a session received.
    enum Frame: Equatable {
        case text(String)
        case binary(Data)
    }

    let key: String
    let behaviours: [Behaviour]
    private let listener: NWListener
    private let queue = DispatchQueue(label: "fake-live-server")
    private let lock = NSLock()
    private let authLog: AuthLog
    private var _texts: [String] = []
    private var _binaries: [Data] = []
    private var _sessions: [[Frame]] = []
    private var sessionOf: [ObjectIdentifier: Int] = [:]
    private var binaryFrames: [ObjectIdentifier: Int] = [:]
    private var audioPages: [ObjectIdentifier: Int] = [:]
    private var closed: Set<ObjectIdentifier> = []
    private var connections: [NWConnection] = []

    var authorization: [String] { authLog.values }
    var texts: [String] { lock.withLock { _texts } }
    var binaries: [Data] { lock.withLock { _binaries } }
    /// Every session's frames in the order they arrived, one entry per `hello`.
    var sessions: [[Frame]] { lock.withLock { _sessions } }

    convenience init(key: String = "ak_test", behaviour: Behaviour = .normal) throws {
        try self.init(key: key, behaviours: [behaviour])
    }

    init(key: String = "ak_test", behaviours: [Behaviour]) throws {
        precondition(!behaviours.isEmpty)
        self.key = key
        self.behaviours = behaviours
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        // The handler must be set before the listener copies the parameters.
        let seen = AuthLog()
        ws.setClientRequestHandler(queue) { _, headers in
            let auth = headers.first { $0.name.lowercased() == "authorization" }?.value ?? ""
            seen.append(auth)
            return .init(status: auth == "Bearer \(key)" ? .accept : .reject, subprotocol: nil)
        }
        authLog = seen
        let params = NWParameters.tcp
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
        listener = try NWListener(using: params, on: .any)
    }

    /// Starts listening on a free loopback port and returns the base URL to give the client.
    func start() async throws -> URL {
        listener.newConnectionHandler = { [weak self] conn in
            guard let self else { return }
            self.lock.withLock { self.connections.append(conn) }
            // Network.framework leaves a refused upgrade open without an answer, so the client
            // would wait for its request timeout; close it the way a server's 401 ends the request.
            conn.stateUpdateHandler = { [weak self] state in
                guard let self, case .ready = state else { return }
                if self.authLog.values.last != "Bearer \(self.key)" { conn.cancel() }
            }
            conn.start(queue: self.queue)
            self.receive(on: conn)
        }
        let port: UInt16 = try await withCheckedThrowingContinuation { cont in
            let once = OnceFlag()
            listener.stateUpdateHandler = { [listener] state in
                switch state {
                case .ready: if once.take() { cont.resume(returning: listener.port!.rawValue) }
                case let .failed(e): if once.take() { cont.resume(throwing: e) }
                default: break
                }
            }
            listener.start(queue: queue)
        }
        return URL(string: "http://127.0.0.1:\(port)")!
    }

    func stop() {
        listener.cancel()
        lock.withLock { connections.forEach { $0.cancel() } }
    }

    private func receive(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, context, _, error in
            guard let self, error == nil, let data else { return }
            let meta = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
            let id = ObjectIdentifier(conn)
            if self.lock.withLock({ self.closed.contains(id) }) { return }
            switch meta?.opcode {
            case .text: self.onText(String(decoding: data, as: UTF8.self), conn)
            case .binary: self.onBinary(data, conn)
            default: break
            }
            if case let .stall(after) = self.behaviour(of: conn), self.lock.withLock({ self.audioPages[id, default: 0] }) >= after {
                return // never read again: the client's sends back up
            }
            self.receive(on: conn)
        }
    }

    private func behaviour(of conn: NWConnection) -> Behaviour {
        let index = lock.withLock { sessionOf[ObjectIdentifier(conn)] } ?? 0
        return behaviours[min(index, behaviours.count - 1)]
    }

    private func record(_ frame: Frame, _ conn: NWConnection, hello: Bool = false) {
        let id = ObjectIdentifier(conn)
        lock.withLock {
            if hello, sessionOf[id] == nil {
                sessionOf[id] = _sessions.count
                _sessions.append([])
            }
            if let i = sessionOf[id] { _sessions[i].append(frame) }
        }
    }

    private func onText(_ text: String, _ conn: NWConnection) {
        lock.withLock { _texts.append(text) }
        let type = (try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])?["type"] as? String
        record(.text(text), conn, hello: type == "hello")
        switch (type, behaviour(of: conn)) {
        case ("hello", .engineBusy):
            send(#"{"type":"error","code":"engine_busy","message":"another session uses another engine"}"#, on: conn)
            close(conn, code: 4409)
        case ("hello", _):
            send(#"{"type":"ready","engine":"fake-live","lang":"auto","tier_ms":560,"load_ms":3}"#, on: conn)
        case ("stop", _):
            send(#"{"type":"words","tokens":[{"text":" end","t":9.9,"conf":0.8}]}"#, on: conn)
            send(#"{"type":"closed"}"#, on: conn)
            close(conn, code: 1000)
        default:
            break
        }
    }

    private func onBinary(_ data: Data, _ conn: NWConnection) {
        let id = ObjectIdentifier(conn)
        record(.binary(data), conn)
        let n = lock.withLock { () -> Int in
            _binaries.append(data)
            binaryFrames[id, default: 0] += 1
            // The first two binary frames of a session are the OpusHead and OpusTags pages.
            audioPages[id] = max(0, binaryFrames[id]! - 2)
            return binaryFrames[id]!
        }
        guard n > 2 else { return }
        switch behaviour(of: conn) {
        case let .tooFast(after) where n - 2 >= after:
            send(#"{"type":"error","code":"too_fast","message":"more than 30 s of audio is waiting for the engine"}"#, on: conn)
            close(conn, code: 4400)
        case let .keyRevoked(after) where n - 2 >= after:
            send(#"{"type":"error","code":"key_revoked","message":"the key was revoked"}"#, on: conn)
            close(conn, code: 4401)
        default:
            send(#"{"type":"words","tokens":[{"text":" page\#(n - 2)","t":\#(Double(n - 2) / 5),"conf":0.9}]}"#, on: conn)
        }
    }

    private func send(_ text: String, on conn: NWConnection) {
        let meta = NWProtocolWebSocket.Metadata(opcode: .text)
        let ctx = NWConnection.ContentContext(identifier: "text", metadata: [meta])
        conn.send(content: Data(text.utf8), contentContext: ctx, isComplete: true, completion: .idempotent)
    }

    private func close(_ conn: NWConnection, code: UInt16) {
        lock.withLock { _ = closed.insert(ObjectIdentifier(conn)) }
        let meta = NWProtocolWebSocket.Metadata(opcode: .close)
        meta.closeCode = code == 1000 ? .protocolCode(.normalClosure) : .applicationCode(code)
        let ctx = NWConnection.ContentContext(identifier: "close", metadata: [meta])
        conn.send(content: nil, contentContext: ctx, isComplete: true, completion: .idempotent)
    }
}

final class AuthLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _values: [String] = []
    var values: [String] { lock.withLock { _values } }
    func append(_ v: String) { lock.withLock { _values.append(v) } }
}

final class OnceFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func take() -> Bool {
        lock.withLock {
            if done { return false }
            done = true
            return true
        }
    }
}

/// A server that answers every request, the live upgrade included, with one plain HTTP status and
/// a JSON error body, the way akou refuses `GET /v1/live` before any socket exists. It counts the
/// requests, so a test can tell a terminal refusal from one the session retries.
final class PlainHTTPRefusal: @unchecked Sendable {
    let status: Int
    let code: String
    private let listener: NWListener
    private let queue = DispatchQueue(label: "plain-http-refusal")
    private let lock = NSLock()
    private var _requests = 0

    var requests: Int { lock.withLock { _requests } }

    init(status: Int, code: String) throws {
        self.status = status
        self.code = code
        listener = try NWListener(using: .tcp, on: .any)
    }

    func start() async throws -> URL {
        listener.newConnectionHandler = { [weak self] conn in
            guard let self else { return }
            conn.start(queue: self.queue)
            conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, _, _ in
                guard let self, data != nil else { return conn.cancel() }
                self.lock.withLock { self._requests += 1 }
                let body = #"{"error":"\#(self.code)","message":"refused by the test server"}"#
                let head = "HTTP/1.1 \(self.status) Refused\r\nContent-Type: application/json\r\n"
                    + "Content-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n"
                conn.send(content: Data((head + body).utf8), completion: .contentProcessed { _ in conn.cancel() })
            }
        }
        let port: UInt16 = try await withCheckedThrowingContinuation { cont in
            let once = OnceFlag()
            listener.stateUpdateHandler = { [listener] state in
                switch state {
                case .ready: if once.take() { cont.resume(returning: listener.port!.rawValue) }
                case let .failed(e): if once.take() { cont.resume(throwing: e) }
                default: break
                }
            }
            listener.start(queue: queue)
        }
        return URL(string: "http://127.0.0.1:\(port)")!
    }

    func stop() { listener.cancel() }
}
