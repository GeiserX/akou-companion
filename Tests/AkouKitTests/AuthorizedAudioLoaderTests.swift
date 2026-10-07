// SPDX-License-Identifier: GPL-3.0-or-later
import AVFoundation
import Foundation
import XCTest

@testable import AkouClient

/// Serves one byte array the way akou's `GET /v1/jobs/{id}/audio` does: 206 with `Content-Range`
/// for a `Range` request, 200 for none (or for every request when `honourRange` is off), or a
/// refusal with akou's error body. Records every request it sees.
final class AudioStubProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var body = Data()
    nonisolated(unsafe) static var refusal: (Int, String)?
    nonisolated(unsafe) static var honourRange = true
    nonisolated(unsafe) static var seen: [URLRequest] = []
    static let lock = NSLock()

    static func reset(_ body: Data) {
        lock.withLock {
            self.body = body
            refusal = nil
            honourRange = true
            seen = []
        }
    }

    static var seenRequests: [URLRequest] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let (body, refusal, honour) = Self.lock.withLock {
            Self.seen.append(request)
            return (Self.body, Self.refusal, Self.honourRange)
        }
        let url = request.url!
        if let (status, json) = refusal {
            let r = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(json.utf8))
            client?.urlProtocolDidFinishLoading(self)
            return
        }
        var status = 200
        var slice = body[...]
        var headers = ["Content-Type": "audio/ogg", "Accept-Ranges": "bytes"]
        if honour, let range = request.value(forHTTPHeaderField: "Range"), range.hasPrefix("bytes=") {
            let span = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
            let first = Int(span[0])!
            let last = span.count > 1 && !span[1].isEmpty ? min(Int(span[1])!, body.count - 1) : body.count - 1
            slice = body[first...last]
            status = 206
            headers["Content-Range"] = "bytes \(first)-\(last)/\(body.count)"
        }
        headers["Content-Length"] = String(slice.count)
        let r = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
        // In pieces, as a network answers, so the loader's skipping and capping cross chunk edges.
        var i = slice.startIndex
        while i < slice.endIndex {
            let j = min(i + 1000, slice.endIndex)
            client?.urlProtocol(self, didLoad: Data(slice[i..<j]))
            i = j
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// Stands in for AVFoundation's loading request and records how it was answered.
final class FakeLoadingRequest: AudioLoadingRequest, @unchecked Sendable {
    var id: ObjectIdentifier { ObjectIdentifier(self) }
    let url: URL?
    let wantsContentInformation: Bool
    let hasData: Bool
    let requestedOffset: Int64
    let requestedLength: Int
    let toEnd: Bool

    var info: AudioContentInfo?
    var data = Data()
    var finished: XCTestExpectation
    var error: Error?
    var finishCount = 0

    init(url: URL?, info: Bool = true, data: Bool = true, offset: Int64 = 0, length: Int = 0, toEnd: Bool = false) {
        self.url = url
        wantsContentInformation = info
        hasData = data
        requestedOffset = offset
        requestedLength = length
        self.toEnd = toEnd
        finished = XCTestExpectation(description: "finished")
    }

    func setContentInformation(_ info: AudioContentInfo) { self.info = info }
    func respond(with data: Data) { self.data.append(data) }
    func finish(_ error: Error?) {
        self.error = error
        finishCount += 1
        finished.fulfill()
    }
}

final class AuthorizedAudioLoaderTests: XCTestCase {
    static let key = "ak_test_9f8e7d6c5b4a"
    static let base = URL(string: "https://akou.example.com")!

    func fixture() throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "ffmpeg-1s-16k", withExtension: "opus", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }

    func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [AudioStubProtocol.self]
        c.urlCache = nil
        return URLSession(configuration: c)
    }

    /// The request the app builds with `JobsClient.audioRequest`: the path and the bearer header.
    static func audioRequest(_ jobId: String) throws -> URLRequest {
        var r = URLRequest(url: base.appending(path: "/v1/jobs/\(jobId)/audio"))
        r.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        return r
    }

    func loader(_ failures: FailureLog? = nil) -> AuthorizedAudioLoader {
        let l = AuthorizedAudioLoader(session: session(), makeRequest: Self.audioRequest)
        if let failures { l.onFailure = { id, f in failures.add(id, f) } }
        return l
    }

    func run(_ loader: AuthorizedAudioLoader, _ request: FakeLoadingRequest) async {
        loader.queue.sync { _ = loader.start(request) }
        await fulfillment(of: [request.finished], timeout: 10)
        // Let any late callback land, then read the request's state on the loader's queue.
        loader.queue.sync {}
    }

    func testTheRangeHeaderAsksExactlyTheBytesTheRequestWants() async throws {
        let body = try fixture()
        AudioStubProtocol.reset(body)
        let l = loader()
        let r = FakeLoadingRequest(url: AuthorizedAudioLoader.url(jobId: "job_1"), info: false, offset: 100, length: 50)
        await run(l, r)
        XCTAssertNil(r.error)
        XCTAssertEqual(AudioStubProtocol.seenRequests.map { $0.value(forHTTPHeaderField: "Range") }, ["bytes=100-149"])
        XCTAssertEqual(r.data, body[100..<150])
        XCTAssertEqual(AudioStubProtocol.seenRequests.first?.url?.path, "/v1/jobs/job_1/audio")
    }

    func testARequestToTheEndAsksAnOpenRange() async throws {
        let body = try fixture()
        AudioStubProtocol.reset(body)
        let r = FakeLoadingRequest(url: AuthorizedAudioLoader.url(jobId: "job_1"), info: false, offset: 3000, length: 10, toEnd: true)
        await run(loader(), r)
        XCTAssertEqual(AudioStubProtocol.seenRequests.map { $0.value(forHTTPHeaderField: "Range") }, ["bytes=3000-"])
        XCTAssertEqual(r.data, body[3000...])
    }

    func testA206FillsTheContentInformationFromContentRange() async throws {
        let body = try fixture()
        AudioStubProtocol.reset(body)
        let r = FakeLoadingRequest(url: AuthorizedAudioLoader.url(jobId: "job_1"), info: true, offset: 0, length: 2)
        await run(loader(), r)
        XCTAssertNil(r.error)
        XCTAssertEqual(AudioStubProtocol.seenRequests.map { $0.value(forHTTPHeaderField: "Range") }, ["bytes=0-1"])
        XCTAssertEqual(r.info, AudioContentInfo(contentType: "org.xiph.ogg-audio", contentLength: Int64(body.count), byteRangeAccessSupported: true))
        XCTAssertEqual(r.data, body[0..<2])
    }

    func testA200WithoutRangeStillServesTheRequestedBytes() async throws {
        let body = try fixture()
        AudioStubProtocol.reset(body)
        AudioStubProtocol.honourRange = false
        let r = FakeLoadingRequest(url: AuthorizedAudioLoader.url(jobId: "job_1"), info: true, offset: 1500, length: 1800)
        await run(loader(), r)
        XCTAssertNil(r.error)
        XCTAssertEqual(r.info?.contentLength, Int64(body.count))
        XCTAssertEqual(r.data, body[1500..<3300])
        XCTAssertEqual(r.finishCount, 1)
    }

    func test409IsNotKept() async throws {
        AudioStubProtocol.reset(Data())
        AudioStubProtocol.refusal = (409, #"{"error":"not_kept","message":"job j was not submitted with keep_audio"}"#)
        let log = FailureLog()
        let r = FakeLoadingRequest(url: AuthorizedAudioLoader.url(jobId: "job_2"), length: 2)
        await run(loader(log), r)
        XCTAssertEqual(r.error as? AuthorizedAudioLoader.Failure, .notKept)
        XCTAssertNil(r.info)
        XCTAssertTrue(r.data.isEmpty)
        XCTAssertEqual(log.all, [Failed(jobId: "job_2", failure: .notKept)])
    }

    func test410IsGone() async throws {
        AudioStubProtocol.reset(Data())
        AudioStubProtocol.refusal = (410, #"{"error":"gone","message":"job j kept its audio, but the file is no longer on disk"}"#)
        let log = FailureLog()
        let r = FakeLoadingRequest(url: AuthorizedAudioLoader.url(jobId: "job_3"), length: 2)
        await run(loader(log), r)
        XCTAssertEqual(r.error as? AuthorizedAudioLoader.Failure, .gone)
        XCTAssertEqual(log.all, [Failed(jobId: "job_3", failure: .gone)])
    }

    func testAnotherRefusalCarriesAkousCode() async throws {
        AudioStubProtocol.reset(Data())
        AudioStubProtocol.refusal = (401, #"{"error":"unauthorized","message":"a valid bearer token is required"}"#)
        let r = FakeLoadingRequest(url: AuthorizedAudioLoader.url(jobId: "job_4"), length: 2)
        await run(loader(), r)
        XCTAssertEqual(r.error as? AuthorizedAudioLoader.Failure, .status(401, code: "unauthorized"))
    }

    func testTheKeyTravelsInTheHeaderAndInNoURL() async throws {
        AudioStubProtocol.reset(try fixture())
        let assetURL = AuthorizedAudioLoader.url(jobId: "job_1")
        XCTAssertEqual(assetURL.absoluteString, "akou-audio://job_1")
        let r = FakeLoadingRequest(url: assetURL, length: 2)
        await run(loader(), r)
        let seen = AudioStubProtocol.seenRequests
        XCTAssertEqual(seen.count, 1)
        for req in seen {
            XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer \(Self.key)")
            XCTAssertFalse(req.url!.absoluteString.contains(Self.key))
        }
        XCTAssertFalse(assetURL.absoluteString.contains(Self.key))
    }

    func testAnURLThatIsNotOursIsRefused() async throws {
        AudioStubProtocol.reset(Data())
        let r = FakeLoadingRequest(url: URL(string: "https://akou.example.com/v1/jobs/j/audio"), length: 2)
        await run(loader(), r)
        XCTAssertEqual(r.error as? AuthorizedAudioLoader.Failure, .notAudioURL)
        XCTAssertTrue(AudioStubProtocol.seenRequests.isEmpty)
    }

    func testContentRangeParsing() {
        XCTAssertEqual(AuthorizedAudioLoader.parseContentRange("bytes 0-1/12345")?.first, 0)
        XCTAssertEqual(AuthorizedAudioLoader.parseContentRange("bytes 100-149/12345")?.total, 12345)
        XCTAssertNil(AuthorizedAudioLoader.parseContentRange("bytes 100-149/*")?.total)
        XCTAssertNil(AuthorizedAudioLoader.parseContentRange("items 0-1/2"))
    }

    // MARK: Through AVFoundation itself

    /// The whole path the app uses: AVURLAsset on `akou-audio://`, this loader, Range requests to
    /// the stub serving the Ogg Opus fixture. Proves AVFoundation reads Ogg Opus from a custom
    /// scheme on this platform, with about the duration `afinfo` reports for the file (1.0065 s).
    func testAVFoundationReadsTheFixtureThroughTheLoader() async throws {
        let body = try fixture()
        AudioStubProtocol.reset(body)
        let l = loader()
        let asset = l.asset(jobId: "job_1")
        let (playable, duration, tracks) = try await asset.load(.isPlayable, .duration, .tracks)
        XCTAssertTrue(playable)
        // A streamed Ogg's duration is AVFoundation's estimate from the pages it read (1.06 s on
        // macOS 26 against the 1.0065 s `afinfo` reads from the whole file), so the bound is loose.
        XCTAssertEqual(duration.seconds, 1.0065, accuracy: 0.1)
        XCTAssertEqual(tracks.filter { $0.mediaType == .audio }.count, 1)
        let seen = AudioStubProtocol.seenRequests
        XCTAssertFalse(seen.isEmpty)
        for req in seen {
            XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer \(Self.key)")
            XCTAssertNotNil(req.value(forHTTPHeaderField: "Range"))
        }
        _ = l
    }

    /// The player reaches readyToPlay and its clock moves, muted: nothing is played through a
    /// speaker (the player is muted and its volume is zero).
    @MainActor
    func testAMutedPlayerPlaysTheFixtureThroughTheLoader() async throws {
        AudioStubProtocol.reset(try fixture())
        let l = loader()
        let item = AVPlayerItem(asset: l.asset(jobId: "job_1"))
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        player.volume = 0
        // A cold iOS Simulator's media services take about 10 s to answer the first item.
        let deadline = Date().addingTimeInterval(30)
        while item.status == .unknown && Date() < deadline { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertEqual(item.status, .readyToPlay, "item error: \(String(describing: item.error))")
        player.play()
        while player.currentTime().seconds < 0.2 && Date() < deadline { try await Task.sleep(for: .milliseconds(50)) }
        player.pause()
        XCTAssertGreaterThan(player.currentTime().seconds, 0.2)
        _ = l
    }
}

struct Failed: Equatable {
    var jobId: String
    var failure: AuthorizedAudioLoader.Failure
}

final class FailureLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [Failed] = []
    func add(_ id: String, _ f: AuthorizedAudioLoader.Failure) { lock.withLock { items.append(Failed(jobId: id, failure: f)) } }
    var all: [Failed] { lock.withLock { items } }
}
