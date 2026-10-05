// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The URL session every request to an akou server uses by default: ephemeral, with no URL cache,
/// so a request carrying the bearer key is never written to disk by the system's cache.
public enum AkouSession {
    public static let shared: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()
}
