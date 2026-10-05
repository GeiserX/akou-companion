// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Network

/// A local stand-in for akou's `GET /v1/live`, on Network.framework's WebSocket server: it checks
/// the bearer on the upgrade, answers `hello` with `ready`, echoes one token per binary frame and
/// answers `stop` with the final words, `closed` and a normal close.
final class FakeLiveServer: @unchecked Sendable {
    enum Behaviour {
        case normal
        /// Answer `hello` with an `engine_busy` error and close 4409.
        case engineBusy
    }

    let key: String
    let behaviour: Behaviour
    private let listener: NWListener
    private let queue = DispatchQueue(label: "fake-live-server")
    private let lock = NSLock()
    private let authLog: AuthLog
    private var _texts: [String] = []
    private var _binaries: [Data] = []
    private var connections: [NWConnection] = []

    var authorization: [String] { authLog.values }
    var texts: [String] { lock.withLock { _texts } }
    var binaries: [Data] { lock.withLock { _binaries } }

    init(key: String = "ak_test", behaviour: Behaviour = .normal) throws {
        self.key = key
        self.behaviour = behaviour
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
            switch meta?.opcode {
            case .text: self.onText(String(decoding: data, as: UTF8.self), conn)
            case .binary: self.onBinary(data, conn)
            default: break
            }
            self.receive(on: conn)
        }
    }

    private func onText(_ text: String, _ conn: NWConnection) {
        lock.withLock { _texts.append(text) }
        let type = (try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])?["type"] as? String
        switch (type, behaviour) {
        case ("hello", .normal):
            send(#"{"type":"ready","engine":"fake-live","lang":"auto","tier_ms":560,"load_ms":3}"#, on: conn)
        case ("hello", .engineBusy):
            send(#"{"type":"error","code":"engine_busy","message":"another session uses another engine"}"#, on: conn)
            close(conn, code: 4409)
        case ("stop", _):
            send(#"{"type":"words","tokens":[{"text":" end","t":9.9}],"final":true}"#, on: conn)
            send(#"{"type":"closed"}"#, on: conn)
            close(conn, code: 1000)
        default:
            break
        }
    }

    private func onBinary(_ data: Data, _ conn: NWConnection) {
        let n = lock.withLock { () -> Int in
            _binaries.append(data)
            return _binaries.count
        }
        // The first two binary frames are the OpusHead and OpusTags pages: no words for them.
        guard n > 2 else { return }
        send(#"{"type":"words","tokens":[{"text":" page\#(n - 2)","t":\#(Double(n - 2) / 5),"conf":0.9}],"final":false}"#, on: conn)
    }

    private func send(_ text: String, on conn: NWConnection) {
        let meta = NWProtocolWebSocket.Metadata(opcode: .text)
        let ctx = NWConnection.ContentContext(identifier: "text", metadata: [meta])
        conn.send(content: Data(text.utf8), contentContext: ctx, isComplete: true, completion: .idempotent)
    }

    private func close(_ conn: NWConnection, code: UInt16) {
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
