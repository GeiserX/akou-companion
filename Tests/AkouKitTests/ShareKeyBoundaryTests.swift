// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import Foundation
import XCTest

/// The share extension never holds the server key and makes no request: it copies a file into
/// the App Group and closes, and the app uploads it. A source check, like `WidgetKeyBoundaryTests`,
/// because the app targets do not build under `swift test`.
final class ShareKeyBoundaryTests: XCTestCase {
    static let app = WidgetKeyBoundaryTests.app

    /// What reads the key, speaks to a server, or writes the app's own upload queue.
    static let forbidden = WidgetKeyBoundaryTests.forbidden + ["URLSession", "URLRequest", "UploadQueue", "importPending"]

    func testNothingTheShareExtensionCompilesTouchesTheKeyOrTheNetwork() throws {
        // The extension compiles ShareExtension/, and nothing else.
        let spec = try String(contentsOf: Self.app.appending(path: "project.yml"), encoding: .utf8)
        let target = try XCTUnwrap(spec.components(separatedBy: "  AkouCompanionShare:").dropFirst().first)
        let sources = target.components(separatedBy: "configFiles:")[0]
        XCTAssertEqual(sources.split(separator: "\n").filter { $0.contains("- ") }.map { $0.trimmingCharacters(in: .whitespaces) }, ["- ShareExtension"])

        var checked = 0
        let files = try FileManager.default.contentsOfDirectory(at: Self.app.appending(path: "ShareExtension"), includingPropertiesForKeys: nil)
        for file in files where file.pathExtension == "swift" {
            let text = try String(contentsOf: file, encoding: .utf8)
            for word in Self.forbidden {
                XCTAssertFalse(text.contains(word), "ShareExtension/\(file.lastPathComponent) mentions \(word)")
            }
            checked += 1
        }
        XCTAssertGreaterThan(checked, 0)

        // It shares the App Group with the app, and no Keychain group.
        let entitlements = try String(contentsOf: Self.app.appending(path: "Config/Share.entitlements"), encoding: .utf8)
        XCTAssertFalse(entitlements.contains("keychain-access-groups"))
        XCTAssertTrue(entitlements.contains("group.io.github.geiserx.akou-companion"))
    }

    /// The share sheet offers akou for exactly the types the extension then takes.
    func testTheActivationRuleNamesTheAcceptedTypes() throws {
        let plist = try String(contentsOf: Self.app.appending(path: "ShareExtension/Info.plist"), encoding: .utf8)
        let named = plist.components(separatedBy: "UTI-CONFORMS-TO \"").dropFirst().compactMap { $0.split(separator: "\"").first.map(String.init) }
        XCTAssertEqual(named, SharedInbox.acceptedTypes)
    }
}
