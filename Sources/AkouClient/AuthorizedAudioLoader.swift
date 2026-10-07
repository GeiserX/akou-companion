// SPDX-License-Identifier: GPL-3.0-or-later
// AVAssetResourceLoaderDelegate is unavailable on watchOS, so AkouKit builds for the watch without this file.
#if !os(watchOS)
import AVFoundation
import Foundation
import UniformTypeIdentifiers

/// Plays a kept job's audio from the server without putting the key in a URL.
///
/// AVPlayer cannot add a header to the requests it makes, and akou takes the key only as
/// `Authorization: Bearer`. So the player is given `akou-audio://<jobId>`, a scheme it does not
/// know, and asks this loader for the bytes; the loader asks `GET /v1/jobs/{id}/audio` with the
/// header and a `Range` for exactly the bytes the player wants, and answers the player's content
/// information (type, length, seekable) from the server's 206 `Content-Range` and `Content-Type`.
///
/// akou answers 409 `not_kept` for a job uploaded without `keep_audio` and 410 `gone` for a deleted
/// one or a kept file no longer on disk; both reach `onFailure` as typed errors, and fail the player
/// item.
public final class AuthorizedAudioLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    public static let scheme = "akou-audio"

    public enum Failure: Error, Equatable, Sendable {
        /// 409 `not_kept`: the job was uploaded without `keep_audio`, so the server has no audio.
        case notKept
        /// 410 `gone`: the job was deleted, or its kept file is no longer on the server's disk.
        case gone
        /// Any other answer outside 2xx, with akou's error code when the body carried one.
        case status(Int, code: String?)
        /// The URL handed to the loader is not `akou-audio://<jobId>`.
        case notAudioURL
    }

    /// The URL to give AVPlayer for a job: `akou-audio://<jobId>`. It carries the job id and nothing else.
    public static func url(jobId: String) -> URL {
        var c = URLComponents()
        c.scheme = scheme
        c.host = jobId
        return c.url!
    }

    /// The job id in an `akou-audio://<jobId>` URL, or nil for any other URL.
    public static func jobId(in url: URL) -> String? {
        guard url.scheme == scheme, let host = url.host(percentEncoded: false), !host.isEmpty else { return nil }
        return host
    }

    /// Called on the loader's queue when the server refuses a job's audio.
    public var onFailure: (@Sendable (_ jobId: String, _ failure: Failure) -> Void)?

    private let session: URLSession
    private let makeRequest: @Sendable (String) throws -> URLRequest
    let queue = DispatchQueue(label: "akou.audio-loader")
    private var fetches: [ObjectIdentifier: RangeFetch] = [:]

    /// - Parameter makeRequest: builds the plain `GET /v1/jobs/{id}/audio` request for a job id,
    ///   with the `Authorization` header (the app passes `JobsClient.audioRequest`). The loader adds
    ///   the `Range` header to it.
    public init(session: URLSession = AkouSession.shared, makeRequest: @escaping @Sendable (String) throws -> URLRequest) {
        self.session = session
        self.makeRequest = makeRequest
    }

    /// An asset for a job's server audio, answered by this loader. Keep the loader alive as long
    /// as the asset: AVFoundation holds its resource loader's delegate weakly.
    public func asset(jobId: String) -> AVURLAsset {
        let asset = AVURLAsset(url: Self.url(jobId: jobId))
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    // MARK: AVAssetResourceLoaderDelegate (called on `queue`)

    public func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        start(PlayerLoadingRequest(loadingRequest))
    }

    public func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        fetches.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    // MARK: The fetch, testable without AVFoundation

    /// Starts answering one loading request. Must run on `queue`. False when the URL is not ours.
    @discardableResult
    func start(_ request: some AudioLoadingRequest) -> Bool {
        guard let jobId = request.url.flatMap(Self.jobId(in:)) else {
            request.finish(Failure.notAudioURL)
            return false
        }
        let urlRequest: URLRequest
        do {
            urlRequest = try makeRequest(jobId)
        } catch {
            request.finish(error)
            return false
        }
        let key = request.id
        let fetch = RangeFetch(request: request, queue: queue) { [weak self] failure in
            guard let self else { return }
            self.fetches.removeValue(forKey: key)
            if let failure { self.onFailure?(jobId, failure) }
        }
        fetches[key] = fetch
        fetch.start(urlRequest, session: session)
        return true
    }

    /// The `Range` header value for a loading request: the bytes it asks for, from its offset to its
    /// end or to the end of the file. A request for the content information alone asks two bytes,
    /// as AVFoundation's own first request does, so the answer is a 206 that carries the length.
    static func rangeHeader(offset: Int64, length: Int, toEnd: Bool, hasData: Bool) -> String {
        guard hasData else { return "bytes=0-1" }
        if toEnd || length <= 0 { return "bytes=\(offset)-" }
        return "bytes=\(offset)-\(offset + Int64(length) - 1)"
    }

    /// The first byte and the total length in a 206 `Content-Range: bytes <first>-<last>/<total>`.
    /// The total is nil when the server writes `*`.
    static func parseContentRange(_ value: String) -> (first: Int64, total: Int64?)? {
        let v = value.trimmingCharacters(in: .whitespaces)
        guard v.lowercased().hasPrefix("bytes ") else { return nil }
        let rest = v.dropFirst("bytes ".count)
        let parts = rest.split(separator: "/", maxSplits: 1)
        guard parts.count == 2 else { return nil }
        let span = parts[0].split(separator: "-", maxSplits: 1)
        guard span.count == 2, let first = Int64(span[0]) else { return nil }
        return (first, Int64(parts[1]))
    }

    /// The uniform type AVFoundation wants for a MIME type. akou serves Ogg Opus as `audio/ogg`.
    static func contentType(mime: String?) -> String {
        let m = (mime ?? "").lowercased()
        if m.isEmpty || m == "application/octet-stream" || m == "audio/ogg" || m == "audio/opus" || m == "application/ogg" {
            return oggType
        }
        return UTType(mimeType: m)?.identifier ?? oggType
    }

    /// The type Core Audio files Ogg under (`AVURLAsset.audiovisualTypes()` lists it).
    static let oggType = "org.xiph.ogg-audio"

    /// akou refuses with `{"error": "<code>", "message": "..."}`.
    static func failure(status: Int, body: Data) -> Failure {
        switch status {
        case 409: return .notKept
        case 410: return .gone
        default:
            struct ErrorBody: Decodable { var error: String? }
            return .status(status, code: (try? JSONDecoder().decode(ErrorBody.self, from: body))?.error)
        }
    }
}

/// The content information a 2xx answer gives the player.
struct AudioContentInfo: Equatable {
    var contentType: String
    var contentLength: Int64
    var byteRangeAccessSupported: Bool
}

/// One request from the player: what it asks for and how it is answered. `PlayerLoadingRequest`
/// wraps AVFoundation's; the tests pass a fake.
protocol AudioLoadingRequest: AnyObject {
    var id: ObjectIdentifier { get }
    var url: URL? { get }
    var wantsContentInformation: Bool { get }
    var hasData: Bool { get }
    var requestedOffset: Int64 { get }
    var requestedLength: Int { get }
    var toEnd: Bool { get }
    func setContentInformation(_ info: AudioContentInfo)
    func respond(with data: Data)
    func finish(_ error: Error?)
}

final class PlayerLoadingRequest: AudioLoadingRequest {
    let request: AVAssetResourceLoadingRequest
    init(_ request: AVAssetResourceLoadingRequest) { self.request = request }

    var id: ObjectIdentifier { ObjectIdentifier(request) }
    var url: URL? { request.request.url }
    var wantsContentInformation: Bool { request.contentInformationRequest != nil }
    var hasData: Bool { request.dataRequest != nil }
    var requestedOffset: Int64 { request.dataRequest?.requestedOffset ?? 0 }
    var requestedLength: Int { request.dataRequest?.requestedLength ?? 0 }
    var toEnd: Bool { request.dataRequest?.requestsAllDataToEndOfResource ?? false }

    func setContentInformation(_ info: AudioContentInfo) {
        guard let c = request.contentInformationRequest else { return }
        c.contentType = info.contentType
        c.contentLength = info.contentLength
        c.isByteRangeAccessSupported = info.byteRangeAccessSupported
    }

    func respond(with data: Data) { request.dataRequest?.respond(with: data) }

    func finish(_ error: Error?) {
        if let error { request.finishLoading(with: error) } else { request.finishLoading() }
    }
}

/// One Range request to the server for one loading request. Every touch of the loading request
/// happens on the loader's queue.
final class RangeFetch: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let request: any AudioLoadingRequest
    private let queue: DispatchQueue
    private let done: (AuthorizedAudioLoader.Failure?) -> Void
    private var task: URLSessionDataTask?
    private var status = 0
    private var errorBody = Data()
    /// Bytes at the start of the answer that come before the requested offset (a 200 that ignored
    /// `Range` starts at byte 0).
    private var skip: Int64 = 0
    /// Bytes still owed to the player; nil for "to the end".
    private var remaining: Int64?
    private var finished = false

    init(request: any AudioLoadingRequest, queue: DispatchQueue, done: @escaping (AuthorizedAudioLoader.Failure?) -> Void) {
        self.request = request
        self.queue = queue
        self.done = done
    }

    func start(_ base: URLRequest, session: URLSession) {
        var r = base
        r.setValue(
            AuthorizedAudioLoader.rangeHeader(
                offset: request.requestedOffset, length: request.requestedLength,
                toEnd: request.toEnd, hasData: request.hasData),
            forHTTPHeaderField: "Range")
        r.cachePolicy = .reloadIgnoringLocalCacheData
        if request.hasData && !request.toEnd { remaining = Int64(request.requestedLength) }
        if !request.hasData { remaining = 0 }
        let t = session.dataTask(with: r)
        t.delegate = self
        task = t
        t.resume()
    }

    /// The player gave up on this request.
    func cancel() {
        finished = true
        task?.cancel()
    }

    private func finish(_ error: Error?, failure: AuthorizedAudioLoader.Failure? = nil) {
        guard !finished else { return }
        finished = true
        task?.cancel()
        request.finish(error)
        done(failure)
    }

    // MARK: URLSessionDataDelegate (hops to the loader's queue)

    func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void
    ) {
        queue.async { self.received(response) }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        queue.async { self.received(data) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        queue.async { self.completed(error) }
    }

    private func received(_ response: URLResponse) {
        guard !finished else { return }
        let http = response as? HTTPURLResponse
        status = http?.statusCode ?? 0
        guard (200..<300).contains(status) else { return }
        var length = response.expectedContentLength
        var first: Int64 = 0
        if status == 206, let cr = http?.value(forHTTPHeaderField: "Content-Range"),
            let parsed = AuthorizedAudioLoader.parseContentRange(cr)
        {
            first = parsed.first
            length = parsed.total ?? -1
        }
        skip = max(0, request.requestedOffset - first)
        if !request.hasData { skip = 0 }
        if request.wantsContentInformation {
            let ranges = status == 206 || http?.value(forHTTPHeaderField: "Accept-Ranges")?.lowercased() == "bytes"
            request.setContentInformation(
                AudioContentInfo(
                    contentType: AuthorizedAudioLoader.contentType(mime: response.mimeType),
                    contentLength: length,
                    byteRangeAccessSupported: ranges))
        }
        if remaining == 0 { finish(nil) }
    }

    private func received(_ data: Data) {
        guard !finished else { return }
        guard (200..<300).contains(status) else {
            if errorBody.count < 4096 { errorBody.append(data.prefix(4096 - errorBody.count)) }
            return
        }
        var chunk = data[...]
        if skip > 0 {
            let n = Int(min(skip, Int64(chunk.count)))
            chunk = chunk.dropFirst(n)
            skip -= Int64(n)
        }
        if let left = remaining { chunk = chunk.prefix(Int(min(left, Int64(chunk.count)))) }
        if !chunk.isEmpty { request.respond(with: Data(chunk)) }
        if let left = remaining {
            remaining = left - Int64(chunk.count)
            if remaining == 0 { finish(nil) }
        }
    }

    private func completed(_ error: Error?) {
        guard !finished else { return }
        if let error { return finish(error) }
        guard (200..<300).contains(status) else {
            let f = AuthorizedAudioLoader.failure(status: status, body: errorBody)
            return finish(f, failure: f)
        }
        finish(nil)
    }
}

#endif
