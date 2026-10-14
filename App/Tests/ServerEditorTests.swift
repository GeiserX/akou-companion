// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import XCTest
@testable import akou

/// The server editor's Test: what it leaves on screen after a pass and after a failure.
@MainActor
final class ServerEditorTests: XCTestCase {
    /// A Test that fails after a good one must not keep showing what the good key could do.
    func testARefusedKeyClearsTheAbilitiesOfTheLastGoodTest() throws {
        let info = try JSONDecoder().decode(ServerInfo.self, from: Data(#"{"name":"akou","version":"1.2.0","mode":"app","capabilities":{"jobs":true,"live":false}}"#.utf8))
        let me = try JSONDecoder().decode(KeyInfo.self, from: Data(#"{"id":"k1","name":"phone","scopes":["read","jobs"]}"#.utf8))
        var report = ServerEditorView.TestReport()
        report.passed(info, me)
        let good = try XCTUnwrap(report.tested)
        XCTAssertTrue(ServerEditorView.abilities(good).contains(.init(text: "Sees every call, dictation and file", allowed: true)))

        report.failed(ServerProbe.Failure.status(401, code: nil))
        XCTAssertEqual(report.result, "The server refused the key: it is mistyped or was revoked.")
        XCTAssertNil(report.tested, "the abilities of the last good Test are still shown")

        report.passed(info, me)
        report.failed(URLError(.timedOut))
        XCTAssertNil(report.tested)
        XCTAssertEqual(report.result?.hasPrefix("Failed: "), true)
    }
}
