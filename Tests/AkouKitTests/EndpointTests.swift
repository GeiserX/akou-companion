// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import Foundation
import XCTest

final class EndpointTests: XCTestCase {
    func testHttpsBecomesWss() throws {
        XCTAssertEqual(try Endpoint.live(URL(string: "https://akou.example.com")!).absoluteString, "wss://akou.example.com/v1/live")
        XCTAssertEqual(try Endpoint.live(URL(string: "https://example.com/akou/")!).absoluteString, "wss://example.com/akou/v1/live")
        XCTAssertEqual(try Endpoint.api(URL(string: "https://example.com:8443")!, "/v1/server").absoluteString, "https://example.com:8443/v1/server")
    }

    func testPlainHttpOnlyToPrivateAddresses() throws {
        XCTAssertEqual(try Endpoint.live(URL(string: "http://192.168.1.20:8787")!).absoluteString, "ws://192.168.1.20:8787/v1/live")
        for ok in ["127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1", "100.127.255.254", "::1", "fd7a:115c:a1e0::1"] {
            XCTAssertTrue(Endpoint.cleartextAllowed(host: ok), ok)
        }
        for no in ["8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.0.1", "2001:db8::1", "akou.example.com", "localhost"] {
            XCTAssertFalse(Endpoint.cleartextAllowed(host: no), no)
        }
        XCTAssertThrowsError(try Endpoint.live(URL(string: "http://akou.example.com")!)) {
            XCTAssertEqual($0 as? Endpoint.Failure, .cleartextToPublicHost("akou.example.com"))
        }
        XCTAssertThrowsError(try Endpoint.live(URL(string: "ftp://192.168.1.1")!))
    }
}
