// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import XCTest

/// The widget extension never holds the server key: it reads the App Group snapshot and the record
/// state, and nothing in it can reach the Keychain or the server. A source check, because the app
/// targets do not build under `swift test`.
final class WidgetKeyBoundaryTests: XCTestCase {
    static let app = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "App")

    /// What reads the key or speaks to the server with it.
    static let forbidden = ["KeyStore", "SecItem", "kSecClass", "Authorization", "JobsClient", "LiveSession", "LiveClient", "ServerProbe", "BackgroundUploader"]

    func testNothingTheWidgetExtensionCompilesTouchesTheKey() throws {
        // The extension compiles Widgets/ and Shared/, and nothing else.
        let spec = try String(contentsOf: Self.app.appending(path: "project.yml"), encoding: .utf8)
        let widgetTarget = try XCTUnwrap(spec.components(separatedBy: "  AkouCompanionWidgets:").dropFirst().first)
        let sources = widgetTarget.components(separatedBy: "configFiles:")[0]
        XCTAssertEqual(sources.split(separator: "\n").filter { $0.contains("- ") }.map { $0.trimmingCharacters(in: .whitespaces) }, ["- Widgets", "- Shared"])

        var checked = 0
        for dir in ["Widgets", "Shared"] {
            let files = try FileManager.default.contentsOfDirectory(at: Self.app.appending(path: dir), includingPropertiesForKeys: nil)
            for file in files where file.pathExtension == "swift" {
                let text = try String(contentsOf: file, encoding: .utf8)
                for word in Self.forbidden {
                    XCTAssertFalse(text.contains(word), "\(dir)/\(file.lastPathComponent) mentions \(word)")
                }
                checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 5)

        // And it shares no Keychain group with the app.
        let entitlements = try String(contentsOf: Self.app.appending(path: "Config/Widgets.entitlements"), encoding: .utf8)
        XCTAssertFalse(entitlements.contains("keychain-access-groups"))
    }
}
