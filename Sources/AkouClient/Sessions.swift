// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The URL session every request to an akou server uses by default: ephemeral, with no URL cache,
/// so a request carrying the bearer key is never written to disk by the system's cache, and a
/// redirect to another origin is never followed, so a 3xx can never carry the key elsewhere.
public enum AkouSession {
    public static let shared: URLSession = make()

    /// A session with the akou rules on `configuration` (the tests add their stub protocols).
    public static func make(_ configuration: URLSessionConfiguration = .ephemeral) -> URLSession {
        let c = configuration
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c, delegate: SameOriginRedirects(), delegateQueue: nil)
    }

    /// How long a probe or a history read waits for an answer: a sleeping Mac then shows its
    /// "did not answer" line in seconds, not after the system's 60.
    public static let readTimeout: TimeInterval = 15

    /// A redirect is followed only to the same scheme, host and port. Anything else answers as the
    /// 3xx itself.
    public static func allowsRedirect(from original: URL?, to target: URL?) -> Bool {
        guard let a = original, let b = target else { return false }
        return a.scheme?.lowercased() == b.scheme?.lowercased()
            && a.host(percentEncoded: false)?.lowercased() == b.host(percentEncoded: false)?.lowercased()
            && effectivePort(a) == effectivePort(b)
    }

    static func effectivePort(_ u: URL) -> Int? {
        if let p = u.port { return p }
        switch u.scheme?.lowercased() {
        case "https", "wss": return 443
        case "http", "ws": return 80
        default: return nil
        }
    }
}

/// Refuses a redirect to another origin for every task of an akou session.
final class SameOriginRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        let from = task.originalRequest?.url ?? response.url
        completionHandler(AkouSession.allowsRedirect(from: from, to: request.url) ? request : nil)
    }
}
