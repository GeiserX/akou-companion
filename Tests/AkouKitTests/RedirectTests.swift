// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import Foundation
import XCTest

/// Answers every request on akou.example.com with a 302 to `target`, and records every request,
/// so a test sees whether the key went on to the redirect's host.
final class RedirectStub: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var target = ""
    nonisolated(unsafe) static var seen: [URLRequest] = []
    static let lock = NSLock()

    static func reset(target: String) { lock.withLock { self.target = target; seen = [] } }
    static var requests: [URLRequest] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let target = Self.lock.withLock { Self.seen.append(request); return Self.target }
        let url = request.url!
        if url.host == "akou.example.com" && url.path == "/v1/keys/me" {
            let r = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": target])!
            var next = URLRequest(url: URL(string: target)!)
            next.allHTTPHeaderFields = request.allHTTPHeaderFields
            client?.urlProtocol(self, wasRedirectedTo: next, redirectResponse: r)
            // A redirect that is not followed answers as the 3xx itself.
            client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
            return
        }
        let r = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"id":"k","name":"n","scopes":["jobs"]}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

final class RedirectTests: XCTestCase {
    func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [RedirectStub.self]
        return AkouSession.make(c)
    }

    func testARedirectToAnotherHostIsNotFollowedSoTheKeyStaysHome() async throws {
        RedirectStub.reset(target: "https://elsewhere.example.net/v1/keys/me")
        let probe = ServerProbe(baseURL: URL(string: "https://akou.example.com")!, key: "ak_secret", session: session())
        do {
            _ = try await probe.keyInfo()
            XCTFail("a redirected answer read as the key")
        } catch let f as ServerProbe.Failure {
            XCTAssertEqual(f, .status(302, code: nil))
        }
        XCTAssertEqual(RedirectStub.requests.map { $0.url?.host }, ["akou.example.com"], "nothing reached the other host")
    }

    func testTheSameOriginIsFollowed() async throws {
        RedirectStub.reset(target: "https://akou.example.com/v1/keys/me2")
        let probe = ServerProbe(baseURL: URL(string: "https://akou.example.com")!, key: "ak_secret", session: session())
        let me = try await probe.keyInfo()
        XCTAssertEqual(me.id, "k")
        XCTAssertEqual(RedirectStub.requests.map { $0.url?.path }, ["/v1/keys/me", "/v1/keys/me2"])
    }

    func testTheOriginRule() {
        let base = URL(string: "https://akou.example.com/v1/jobs")
        XCTAssertTrue(AkouSession.allowsRedirect(from: base, to: URL(string: "https://AKOU.example.com:443/x")))
        XCTAssertFalse(AkouSession.allowsRedirect(from: base, to: URL(string: "http://akou.example.com/x")), "scheme")
        XCTAssertFalse(AkouSession.allowsRedirect(from: base, to: URL(string: "https://akou.example.com:8443/x")), "port")
        XCTAssertFalse(AkouSession.allowsRedirect(from: base, to: URL(string: "https://evil.example.com/x")), "host")
        XCTAssertFalse(AkouSession.allowsRedirect(from: base, to: nil))
    }

    /// A sleeping Mac shows its line after 15 s: the probe's and the history reads' requests say so.
    func testProbesAndHistoryReadsGiveUpAfter15Seconds() async throws {
        RedirectStub.reset(target: "")
        let base = URL(string: "https://other.example.com")!
        _ = try? await ServerProbe(baseURL: base, key: "k", session: session()).keyInfo()
        _ = try? await HistoryClient(baseURL: base, key: "k", session: session()).calls()
        XCTAssertEqual(RedirectStub.requests.map(\.timeoutInterval), [15, 15])
    }
}
