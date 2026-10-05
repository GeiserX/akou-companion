// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouOpus
import AkouProtocol
import Foundation
import XCTest

final class LiveClientTests: XCTestCase {
    func testASessionFromHelloToClosed() async throws {
        let server = try FakeLiveServer()
        defer { server.stop() }
        let base = try await server.start()

        let encoder = try OpusEncoder()
        var writer = OggOpusWriter(encoder: encoder, serial: 42)
        let headers = try writer.headerPages()
        let client = try await LiveClient.open(
            baseURL: base, key: "ak_test", hello: LiveHello(language: "es"), headerPages: headers
        )
        XCTAssertEqual(client.ready, LiveReady(engine: "fake-live", lang: "auto", tierMs: 560, loadMs: 3))

        var sent: [Data] = []
        for n in 0..<30 {
            if let page = try writer.append(frame: OggOpusWriterTests.sine(frame: n)) {
                sent.append(page)
                try await client.send(page: page)
            }
        }
        XCTAssertEqual(sent.count, 3)
        try await client.stop()

        var events: [LiveClient.Event] = []
        for await e in client.events { events.append(e) }

        XCTAssertEqual(events, [
            .words(LiveWords(tokens: [LiveToken(text: " page1", t: 0.2, conf: 0.9)])),
            .words(LiveWords(tokens: [LiveToken(text: " page2", t: 0.4, conf: 0.9)])),
            .words(LiveWords(tokens: [LiveToken(text: " page3", t: 0.6, conf: 0.9)])),
            .words(LiveWords(tokens: [LiveToken(text: " end", t: 9.9)], final: true)),
            .closed,
            .disconnected(code: 1000),
        ])
        XCTAssertEqual(server.authorization, ["Bearer ak_test"])
        XCTAssertEqual(server.texts.count, 2)
        XCTAssertEqual(try JSONDecoder().decode(LiveClientMessage.self, from: Data(server.texts[0].utf8)), .hello(LiveHello(language: "es")))
        XCTAssertEqual(server.texts[1], #"{"type":"stop"}"#)
        // Byte for byte what the phone writes to its file: header pages first, then the audio pages.
        XCTAssertEqual(server.binaries, headers + sent)
    }

    func testABadKeyIsRefusedAtTheUpgrade() async throws {
        let server = try FakeLiveServer()
        defer { server.stop() }
        let base = try await server.start()
        do {
            _ = try await LiveClient.open(baseURL: base, key: "ak_wrong", hello: LiveHello(), headerPages: [])
            XCTFail("a wrong key must not get a session")
        } catch let f as LiveClient.Failure {
            guard case .handshake = f else { return XCTFail("got \(f)") }
        }
        XCTAssertEqual(server.texts, [])
    }

    func testEngineBusyIsReportedWithItsCloseCode() async throws {
        let server = try FakeLiveServer(behaviour: .engineBusy)
        defer { server.stop() }
        let base = try await server.start()
        do {
            _ = try await LiveClient.open(baseURL: base, key: "ak_test", hello: LiveHello(), headerPages: [])
            XCTFail("expected a refusal")
        } catch let f as LiveClient.Failure {
            XCTAssertEqual(f, .refused(LiveError(code: "engine_busy", message: "another session uses another engine"), closeCode: LiveCloseCode.engineBusy.rawValue))
        }
    }
}
