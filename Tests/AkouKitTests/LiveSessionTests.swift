// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import AkouOpus
import AkouProtocol
import Foundation
import XCTest

final class LiveSessionTests: XCTestCase {
    private let fakeEngine = LiveSession.State.live(engine: "fake-live")
    /// One URL session per test, so sockets a test leaves behind cannot hold up the next one's.
    private var urlSession: URLSession!

    override func setUp() {
        urlSession = URLSession(configuration: .ephemeral)
    }

    override func tearDown() {
        urlSession.invalidateAndCancel()
    }

    func testTooFastReopensOnceWithTheHeadersThenTheCurrentPageAndNoBacklog() async throws {
        let server = try FakeLiveServer(behaviours: [.tooFast(afterPages: 3), .normal])
        defer { server.stop() }
        let base = try await server.start()
        let pages = try PageMaker(serial: 9)
        let session = LiveSession(
            baseURL: base, key: "ak_test", hello: LiveHello(language: "es"), headerPages: pages.headers,
            policy: .init(backoff: [.milliseconds(400)]), session: urlSession
        )
        let log = EventLog(session.events)
        await session.start()
        await log.expect { $0.states.contains(self.fakeEngine) }

        var first: [PageMaker.Page] = []
        for _ in 0..<3 {
            let p = try pages.next()
            first.append(p)
            await session.send(page: p.data, endsAt: p.end)
        }
        await log.expect { $0.states.contains(.paused(reason: "too_fast")) }

        // Produced while the session is down: they stay in the file and are never sent.
        var offline: [PageMaker.Page] = []
        for _ in 0..<2 {
            let p = try pages.next()
            offline.append(p)
            await session.send(page: p.data, endsAt: p.end)
        }
        await log.expect { $0.states.filter { $0 == self.fakeEngine }.count == 2 }

        var second: [PageMaker.Page] = []
        for _ in 0..<3 {
            let p = try pages.next()
            second.append(p)
            await session.send(page: p.data, endsAt: p.end)
        }
        await session.stop()
        await log.expect { $0.finished }

        XCTAssertEqual(log.states, [
            .connecting, fakeEngine, .paused(reason: "too_fast"), .connecting, fakeEngine, .off(reason: "stopped"),
        ])
        let sessions = server.sessions
        XCTAssertEqual(sessions.count, 2, "exactly one reopen")
        let hello = FakeLiveServer.Frame.text(try LiveClientMessage.hello(LiveHello(language: "es")).json())
        let headers = pages.headers.map(FakeLiveServer.Frame.binary)
        XCTAssertEqual(sessions.first, [hello] + headers + first.map { .binary($0.data) })
        XCTAssertEqual(sessions.last, [hello] + headers + second.map { .binary($0.data) } + [.text(#"{"type":"stop"}"#)])
        XCTAssertEqual(log.gaps.count, 1)
        XCTAssertEqual(log.gaps.first?.from ?? -1, first.last!.end, accuracy: 1e-9)
        XCTAssertEqual(log.gaps.first?.to ?? -1, offline.last!.end, accuracy: 1e-9)
    }

    func testAStalledServerTripsTheQueueCapAndTheReopenSendsNoBacklog() async throws {
        let server = try FakeLiveServer(behaviours: [.stall(afterPages: 0), .normal])
        defer { server.stop() }
        let base = try await server.start()
        let pages = try PageMaker(serial: 11)
        let session = LiveSession(
            baseURL: base, key: "ak_test", hello: LiveHello(), headerPages: pages.headers,
            policy: .init(backoff: [.milliseconds(50)]), session: urlSession
        )
        let log = EventLog(session.events)
        await session.start()
        await log.expect { $0.states.contains(self.fakeEngine) }

        // Large pages fill the socket's buffers quickly; once the server stops reading, sends wait
        // and the queue grows until the cap drops the session.
        var sent = 0
        var dropped = false
        while !dropped && sent < 5000 {
            let p = pages.big(index: sent)
            sent += 1
            await session.send(page: p.data, endsAt: p.end)
            dropped = await session.drops > 0
        }
        XCTAssertTrue(dropped, "the queue cap never tripped after \(sent) pages")
        let highWater = await session.queueHighWater
        XCTAssertEqual(highWater, 25, "the queue reaches the cap and never passes it")
        await log.expect { $0.states.filter { $0 == self.fakeEngine }.count == 2 }

        var after: [PageMaker.Page] = []
        for i in sent..<(sent + 3) {
            let p = pages.big(index: i)
            after.append(p)
            await session.send(page: p.data, endsAt: p.end)
        }
        await session.stop()
        await log.expect { $0.finished }

        XCTAssertEqual(log.states, [
            .connecting, fakeEngine, .paused(reason: "behind"), .connecting, fakeEngine, .off(reason: "stopped"),
        ])
        let sessions = server.sessions
        XCTAssertEqual(sessions.count, 2)
        XCTAssertEqual(
            Array(sessions.last?.dropFirst() ?? []),
            pages.headers.map(FakeLiveServer.Frame.binary) + after.map { .binary($0.data) } + [.text(#"{"type":"stop"}"#)],
            "the new session carries the headers, then only pages made after the drop"
        )
        let gap = try XCTUnwrap(log.gaps.first)
        XCTAssertEqual(gap.to, after.first!.end - 0.2, accuracy: 1e-9)
        XCTAssertLessThan(gap.from, gap.to)
    }

    func testEngineBusyIsTerminal() async throws {
        let server = try FakeLiveServer(behaviours: [.engineBusy])
        defer { server.stop() }
        let base = try await server.start()
        let pages = try PageMaker(serial: 12)
        let session = LiveSession(
            baseURL: base, key: "ak_test", hello: LiveHello(), headerPages: pages.headers,
            policy: .init(backoff: [.milliseconds(20)]), session: urlSession
        )
        let log = EventLog(session.events)
        await session.start()
        await log.expect { $0.states.contains(.off(reason: "engine_busy")) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(log.states, [.connecting, .off(reason: "engine_busy")])
        XCTAssertEqual(server.sessions.count, 1, "no reopen")
        let p = try pages.next()
        await session.send(page: p.data, endsAt: p.end)
        await session.stop()
        XCTAssertEqual(server.sessions.count, 1)
    }

    func testKeyRevokedIsTerminal() async throws {
        let server = try FakeLiveServer(behaviours: [.keyRevoked(afterPages: 1)])
        defer { server.stop() }
        let base = try await server.start()
        let pages = try PageMaker(serial: 13)
        let session = LiveSession(
            baseURL: base, key: "ak_test", hello: LiveHello(), headerPages: pages.headers,
            policy: .init(backoff: [.milliseconds(20)]), session: urlSession
        )
        let log = EventLog(session.events)
        await session.start()
        await log.expect { $0.states.contains(self.fakeEngine) }
        let p = try pages.next()
        await session.send(page: p.data, endsAt: p.end)
        await log.expect { $0.states.contains(.off(reason: "key_revoked")) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(log.states, [.connecting, fakeEngine, .off(reason: "key_revoked")])
        XCTAssertEqual(server.sessions.count, 1, "no reopen")
    }

    /// akou answers the upgrade with a plain 503 `no_live_engine` when it has no streaming model:
    /// live text ends after one try instead of reopening every 30 s for the whole recording.
    func testA503AtTheUpgradeIsNoLiveEngineAndTerminal() async throws {
        let server = try PlainHTTPRefusal(status: 503, code: "no_live_engine")
        defer { server.stop() }
        let base = try await server.start()
        let pages = try PageMaker(serial: 14)
        let session = LiveSession(
            baseURL: base, key: "ak_test", hello: LiveHello(), headerPages: pages.headers,
            policy: .init(backoff: [.milliseconds(20)]), session: urlSession
        )
        let log = EventLog(session.events)
        await session.start()
        await log.expect { $0.states.contains(.off(reason: "no_live_engine")) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(log.states, [.connecting, .off(reason: "no_live_engine")])
        XCTAssertEqual(server.requests, 1, "no reopen")
        await session.stop()
    }

    /// The control for the test above: a 502 from a proxy is not the server saying no, so the
    /// session keeps trying.
    func testA502AtTheUpgradeIsRetried() async throws {
        let server = try PlainHTTPRefusal(status: 502, code: "bad_gateway")
        defer { server.stop() }
        let base = try await server.start()
        let pages = try PageMaker(serial: 15)
        let session = LiveSession(
            baseURL: base, key: "ak_test", hello: LiveHello(), headerPages: pages.headers,
            policy: .init(backoff: [.milliseconds(20)]), session: urlSession
        )
        let log = EventLog(session.events)
        await session.start()
        await log.expect { _ in server.requests >= 3 }
        XCTAssertTrue(log.states.contains(.paused(reason: "network")))
        XCTAssertFalse(log.states.contains { if case .off = $0 { return true }; return false })
        await session.stop()
    }

    /// The recorder waits for `events` to end after `stop()`, so it must end on every path: here
    /// stop comes before start, and a start after it opens nothing.
    func testStopBeforeStartFinishesTheEventsAndNothingOpens() async throws {
        let server = try PlainHTTPRefusal(status: 502, code: "bad_gateway")
        defer { server.stop() }
        let base = try await server.start()
        let pages = try PageMaker(serial: 16)
        let session = LiveSession(
            baseURL: base, key: "ak_test", hello: LiveHello(), headerPages: pages.headers,
            policy: .init(backoff: [.milliseconds(20)]), session: urlSession
        )
        let log = EventLog(session.events)
        await session.stop()
        await log.expect { $0.finished }
        await session.start()
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(server.requests, 0, "no session after stop")
        XCTAssertEqual(log.states, [])
    }

    /// The same for a session already ended by the server: stop still returns with `events` done.
    func testStopAfterATerminalRefusalFinishesTheEvents() async throws {
        let server = try PlainHTTPRefusal(status: 503, code: "no_live_engine")
        defer { server.stop() }
        let base = try await server.start()
        let pages = try PageMaker(serial: 17)
        let session = LiveSession(
            baseURL: base, key: "ak_test", hello: LiveHello(), headerPages: pages.headers,
            policy: .init(backoff: [.milliseconds(20)]), session: urlSession
        )
        let log = EventLog(session.events)
        await session.start()
        await log.expect { $0.states.contains(.off(reason: "no_live_engine")) }
        await session.stop()
        await log.expect { $0.finished }
    }

    /// Opt-in: streams the ffmpeg fixture in real time through a real akou server. Runs only with
    /// AKOU_URL and AKOU_API_KEY set, for example against a throwaway server-mode akou.
    func testARealServerWhenConfigured() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let url = env["AKOU_URL"].flatMap(URL.init(string:)), let key = env["AKOU_API_KEY"] else {
            throw XCTSkip("set AKOU_URL and AKOU_API_KEY to run against a real akou server")
        }
        let file = try Data(contentsOf: try XCTUnwrap(Bundle.module.url(forResource: "ffmpeg-1s-16k", withExtension: "opus", subdirectory: "Fixtures")))
        let all = try OggPage.readAll(file)
        let encoded = try all.map { try $0.encoded() }
        let session = LiveSession(baseURL: url, key: key, hello: LiveHello(), headerPages: Array(encoded.prefix(2)))
        let log = EventLog(session.events)
        await session.start()
        await log.expect(timeout: .seconds(60)) { $0.states.contains { if case .live = $0 { true } else { false } } }
        for (page, data) in zip(all.dropFirst(2), encoded.dropFirst(2)) {
            await session.send(page: data, endsAt: Double(page.granulePosition - 312) / 48000)
            try await Task.sleep(for: .milliseconds(200))
        }
        await session.stop()
        await log.expect { $0.finished }
        XCTAssertEqual(log.states.last, .off(reason: "stopped"))
        XCTAssertTrue(log.gaps.isEmpty)
    }
}

/// Real Ogg Opus pages from the writer, with the second each one ends at.
final class PageMaker {
    struct Page {
        var data: Data
        var end: Double
    }

    private var writer: OggOpusWriter
    private var frame = 0
    let headers: [Data]
    let serial: UInt32

    init(serial: UInt32) throws {
        self.serial = serial
        writer = OggOpusWriter(encoder: try OpusEncoder(), serial: serial)
        headers = try writer.headerPages()
    }

    func next() throws -> Page {
        while true {
            let page = try writer.append(frame: OggOpusWriterTests.sine(frame: frame))
            frame += 1
            if let page { return Page(data: page, end: writer.seconds) }
        }
    }

    /// A 60 KB page with the place of the `index`th 200 ms page: valid Ogg, not valid Opus, for
    /// the stalled-server test where only the bytes on the wire matter.
    func big(index: Int) -> Page {
        let page = OggPage(
            granulePosition: Int64(index + 1) * 9600, serial: serial, sequence: UInt32(index + 2),
            packets: [Data(repeating: UInt8(truncatingIfNeeded: index), count: 60_000)]
        )
        return Page(data: try! page.encoded(), end: Double(index + 1) * 0.2)
    }
}

/// Collects a session's events as they come, for tests to wait on and inspect.
final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _events: [LiveSession.Event] = []
    private var _finished = false

    init(_ stream: AsyncStream<LiveSession.Event>) {
        Task { [weak self] in
            for await e in stream { self?.lock.withLock { self?._events.append(e) } }
            self?.lock.withLock { self?._finished = true }
        }
    }

    var events: [LiveSession.Event] { lock.withLock { _events } }
    /// The session ended its event stream: every event is in.
    var finished: Bool { lock.withLock { _finished } }

    var states: [LiveSession.State] {
        events.compactMap { if case let .state(s) = $0 { s } else { nil } }
    }

    var gaps: [(from: Double, to: Double)] {
        events.compactMap { if case let .gap(f, t) = $0 { (f, t) } else { nil } }
    }

    func expect(timeout: Duration = .seconds(5), file: StaticString = #filePath, line: UInt = #line, _ done: @escaping (EventLog) -> Bool) async {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if done(self) { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("timed out; events so far: \(events)", file: file, line: line)
    }
}
