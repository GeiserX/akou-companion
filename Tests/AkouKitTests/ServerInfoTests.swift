// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation
import XCTest

final class ServerInfoTests: XCTestCase {
    /// A `GET /v1/server` answer in akou 0.6.2's shape (hand-written from src/main/api/routes/server.ts).
    func testDecodesLiveEnginesAndRetainDaysFromA062Answer() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "server-0.6.2", withExtension: "json", subdirectory: "Fixtures"))
        let info = try JSONDecoder().decode(ServerInfo.self, from: Data(contentsOf: url))
        XCTAssertEqual(info.version, "0.6.2")
        XCTAssertTrue(info.supportsLive)
        XCTAssertEqual(info.liveEngines, ["nemotron-en-560", "nemotron-3.5-560"])
        XCTAssertEqual(info.retainDays, 30)
    }

    /// A server older than the live route sends neither `live` nor `retain_days`; the desktop app sends `live: null`.
    func testStillDecodesAnAnswerWithoutLive() throws {
        let old = try JSONDecoder().decode(ServerInfo.self, from: Data(#"{"name":"akou","version":"0.5.0","mode":"server","capabilities":{"jobs":true}}"#.utf8))
        XCTAssertNil(old.live)
        XCTAssertNil(old.retainDays)
        XCTAssertEqual(old.liveEngines, [])
        XCTAssertFalse(old.supportsLive)
        let app = try JSONDecoder().decode(ServerInfo.self, from: Data(#"{"name":"akou","mode":"app","live":null,"retain_days":30,"capabilities":{"jobs":true,"live":false}}"#.utf8))
        XCTAssertNil(app.live)
        XCTAssertEqual(app.retainDays, 30)
    }
}
