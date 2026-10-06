// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation
import XCTest

/// Answers every request from a table keyed by path and records the requests.
final class StubProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var answers: [String: (Int, String)] = [:]
    nonisolated(unsafe) static var seen: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.seen.append(request)
        let (status, body) = Self.answers[request.url!.path] ?? (404, #"{"error":"not_found"}"#)
        let resp = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class ServerProbeTests: XCTestCase {
    func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: c)
    }

    override func setUp() {
        StubProtocol.answers = [:]
        StubProtocol.seen = []
    }

    func testProbeReadsTheServerAndTheKey() async throws {
        StubProtocol.answers["/v1/server"] = (200, #"{"name":"akou","version":"0.7.0","mode":"server","capabilities":{"jobs":true,"live":true}}"#)
        StubProtocol.answers["/v1/keys/me"] = (200, #"{"id":"k1","name":"phone","scopes":["jobs"]}"#)
        let probe = ServerProbe(baseURL: URL(string: "https://akou.example.com")!, key: "ak_x", session: session())
        let info = try await probe.server()
        XCTAssertTrue(info.supportsLive)
        let key = try await probe.keyInfo()
        XCTAssertEqual(key.scopes, ["jobs"])
        XCTAssertEqual(StubProtocol.seen.map { $0.value(forHTTPHeaderField: "Authorization") }, ["Bearer ak_x", "Bearer ak_x"])
    }

    func testAWrongKeyIsA401WithAkousCode() async throws {
        StubProtocol.answers["/v1/keys/me"] = (401, #"{"error":"unauthorized","message":"a valid bearer token is required"}"#)
        let probe = ServerProbe(baseURL: URL(string: "https://akou.example.com")!, key: "ak_bad", session: session())
        do {
            _ = try await probe.keyInfo()
            XCTFail("expected 401")
        } catch {
            XCTAssertEqual(error as? ServerProbe.Failure, .status(401, code: "unauthorized"))
        }
    }

    func testSomethingElseIsNotAkou() async throws {
        StubProtocol.answers["/v1/server"] = (200, #"{"name":"other","capabilities":{}}"#)
        let probe = ServerProbe(baseURL: URL(string: "https://example.com")!, key: "ak_x", session: session())
        do {
            _ = try await probe.server()
            XCTFail("expected notAkou")
        } catch {
            XCTAssertEqual(error as? ServerProbe.Failure, .notAkou)
        }
    }
}
