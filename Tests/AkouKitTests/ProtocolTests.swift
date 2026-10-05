// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation
import XCTest

final class ProtocolTests: XCTestCase {
    func object(_ json: String) throws -> NSDictionary {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? NSDictionary)
    }

    func testHelloOnTheWire() throws {
        let json = try LiveClientMessage.hello(LiveHello(language: "es", model: "nemotron-3.5-560")).json()
        XCTAssertEqual(json, #"{"codec":"ogg-opus","language":"es","model":"nemotron-3.5-560","type":"hello","v":1}"#)
        let pcm = try LiveClientMessage.hello(LiveHello(codec: .pcm16)).json()
        XCTAssertEqual(try object(pcm), ["type": "hello", "v": 1, "codec": "pcm16", "language": "auto", "model": "auto"])
    }

    func testStopOnTheWire() throws {
        XCTAssertEqual(try LiveClientMessage.stop.json(), #"{"type":"stop"}"#)
    }

    func testClientMessagesRoundTrip() throws {
        for m in [LiveClientMessage.hello(LiveHello()), .hello(LiveHello(codec: .pcm16, language: "en", model: "x")), .stop] {
            let back = try JSONDecoder().decode(LiveClientMessage.self, from: Data(try m.json().utf8))
            XCTAssertEqual(back, m)
        }
    }

    func testServerMessagesParse() throws {
        XCTAssertEqual(
            try LiveServerMessage.parse(#"{"type":"ready","engine":"nemotron-3.5-560","lang":"auto","tier_ms":560,"load_ms":4210}"#),
            .ready(LiveReady(engine: "nemotron-3.5-560", lang: "auto", tierMs: 560, loadMs: 4210))
        )
        XCTAssertEqual(
            try LiveServerMessage.parse(#"{"type":"words","tokens":[{"text":" hola","t":1.23,"conf":0.91},{"text":"s","t":1.4}],"final":false}"#),
            .words(LiveWords(tokens: [LiveToken(text: " hola", t: 1.23, conf: 0.91), LiveToken(text: "s", t: 1.4)]))
        )
        XCTAssertEqual(try LiveServerMessage.parse(#"{"type":"closed"}"#), .closed)
        XCTAssertEqual(
            try LiveServerMessage.parse(#"{"type":"error","code":"engine_busy","message":"another session uses nemotron-en-560"}"#),
            .error(LiveError(code: "engine_busy", message: "another session uses nemotron-en-560"))
        )
        // A newer server's message is ignored, not an error.
        XCTAssertEqual(try LiveServerMessage.parse(#"{"type":"progress","x":1}"#), .unknown(type: "progress"))
        XCTAssertThrowsError(try LiveServerMessage.parse(#"{"engine":"no type"}"#))
    }

    func testServerMessagesRoundTrip() throws {
        let all: [LiveServerMessage] = [
            .ready(LiveReady(engine: "e", lang: "es", tierMs: 1120, loadMs: 1)),
            .words(LiveWords(tokens: [LiveToken(text: " a", t: 0.5, conf: nil)], final: true)),
            .closed,
            .error(LiveError(code: "bad_page", message: nil)),
        ]
        for m in all {
            let data = try JSONEncoder().encode(m)
            XCTAssertEqual(try JSONDecoder().decode(LiveServerMessage.self, from: data), m)
        }
    }

    func testCloseCodes() {
        XCTAssertEqual(LiveCloseCode.badPage.rawValue, 4400)
        XCTAssertEqual(LiveCloseCode.keyRevoked.rawValue, 4401)
        XCTAssertEqual(LiveCloseCode.engineBusy.rawValue, 4409)
        XCTAssertEqual(LiveCloseCode(rawValue: 4503), .noLiveEngine)
    }

    func testServerInfoReadsOnlyWhatItNeeds() throws {
        let json = #"{"name":"akou","version":"0.7.0","mode":"server","presets":[{"name":"auto"}],"capabilities":{"jobs":true,"live":true,"wyoming":false},"retain_days":7}"#
        let info = try JSONDecoder().decode(ServerInfo.self, from: Data(json.utf8))
        XCTAssertEqual(info.version, "0.7.0")
        XCTAssertTrue(info.supportsLive)
        let old = #"{"name":"akou","version":"0.6.1","mode":"server","capabilities":{"jobs":true}}"#
        XCTAssertFalse(try JSONDecoder().decode(ServerInfo.self, from: Data(old.utf8)).supportsLive)
    }
}
