// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import XCTest
@testable import akou

/// What App Transport Security lets the app reach, at run time in the app's own process: plain
/// `http` to a private or Tailscale address (100.64.0.0/10), which `Endpoint` allows and the app's
/// `NSAllowsLocalNetworking` must not undo.
///
/// Runs only when given a URL through xcodebuild's `TEST_RUNNER_` prefix:
///   TEST_RUNNER_AKOU_PLAIN_HTTP_URL   e.g. http://100.64.0.10:18580/ (any server answering 200)
final class TransportTests: XCTestCase {
    func testPlainHTTPToATailnetAddressReachesTheServer() async throws {
        guard let raw = ProcessInfo.processInfo.environment["AKOU_PLAIN_HTTP_URL"], let url = URL(string: raw) else {
            throw XCTSkip("no AKOU_PLAIN_HTTP_URL")
        }
        let host = try XCTUnwrap(url.host)
        XCTAssertTrue(Endpoint.cleartextAllowed(host: host), "\(host) is not an address Endpoint lets plain http reach")
        let (body, response) = try await AkouSession.shared.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode
        print("ATS probe: GET \(raw) answered \(status.map(String.init) ?? "nothing"), \(body.count) bytes")
        XCTAssertEqual(status, 200)
    }
}
