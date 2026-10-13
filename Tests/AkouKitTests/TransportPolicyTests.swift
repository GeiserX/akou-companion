// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import XCTest

/// Where plain `http` may go is decided in one place, `Endpoint`: https always, http only to a
/// loopback, private or Tailscale (100.64.0.0/10) address. App Transport Security cannot say
/// that: `NSAllowsLocalNetworking` lets RFC 1918 and loopback through but refuses 100.64.0.0/10
/// (-1022, measured in the iOS 26.5 Simulator), and its presence makes iOS ignore
/// `NSAllowsArbitraryLoads`. So the app opts out of ATS, and these checks hold the two halves
/// together: the opt-out is exactly that one key, and every request the client builds goes
/// through `Endpoint`.
final class TransportPolicyTests: XCTestCase {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    func testTheAppOptsOutOfATSWithOneKeyAndNoLocalNetworkingKey() throws {
        let data = try Data(contentsOf: Self.root.appending(path: "App/Sources/Info.plist"))
        let plist = try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any])
        let ats = try XCTUnwrap(plist["NSAppTransportSecurity"] as? [String: Any])
        XCTAssertEqual(Set(ats.keys), ["NSAllowsArbitraryLoads"], "NSAllowsLocalNetworking beside it would silently turn the opt-out off")
        XCTAssertEqual(ats["NSAllowsArbitraryLoads"] as? Bool, true)
    }

    func testEveryRequestTheClientBuildsGoesThroughEndpoint() throws {
        let dir = Self.root.appending(path: "Sources/AkouClient")
        var checked = 0
        for file in try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) where file.pathExtension == "swift" {
            let lines = try String(contentsOf: file, encoding: .utf8).split(separator: "\n", omittingEmptySubsequences: false)
            for (i, line) in lines.enumerated() where line.contains("URLRequest(url:") {
                // The URL comes from Endpoint on this line or within the few lines before it.
                let window = lines[max(0, i - 5)...i].joined(separator: "\n")
                XCTAssertTrue(window.contains("Endpoint."), "\(file.lastPathComponent):\(i + 1): \(line.trimmingCharacters(in: .whitespaces))")
                checked += 1
            }
        }
        XCTAssertGreaterThanOrEqual(checked, 3, "the check found the requests it guards")
    }
}
