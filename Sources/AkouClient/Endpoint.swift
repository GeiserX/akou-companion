// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Where the phone may send audio and the key. `https` always passes. Plain `http` passes only
/// for an IP address that is loopback, RFC 1918, unique-local (fc00::/7) or shared
/// (100.64.0.0/10, which Tailscale uses), the same rule akou's own remote dictation applies.
/// A host name over plain `http` is refused: the phone cannot pin what the name resolves to.
public enum Endpoint {
    public enum Failure: Error, Equatable {
        case unsupportedScheme(String?)
        case missingHost
        case cleartextToPublicHost(String)
    }

    /// The URL of an API path under the server's base URL, for example `/v1/server`.
    public static func api(_ base: URL, _ path: String) throws -> URL {
        try check(base)
        return join(base, path)
    }

    /// The live WebSocket URL: `wss://` for an `https` base, `ws://` for an allowed `http` base.
    public static func live(_ base: URL) throws -> URL {
        let scheme = try check(base)
        var c = URLComponents(url: join(base, "/v1/live"), resolvingAgainstBaseURL: false)!
        c.scheme = scheme == "https" ? "wss" : "ws"
        return c.url!
    }

    @discardableResult
    static func check(_ base: URL) throws -> String {
        let scheme = base.scheme?.lowercased()
        guard scheme == "https" || scheme == "http" else { throw Failure.unsupportedScheme(base.scheme) }
        guard let host = base.host(percentEncoded: false), !host.isEmpty else { throw Failure.missingHost }
        if scheme == "http" && !cleartextAllowed(host: host) { throw Failure.cleartextToPublicHost(host) }
        return scheme!
    }

    static func join(_ base: URL, _ path: String) -> URL {
        var c = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        let prefix = c.path.hasSuffix("/") ? String(c.path.dropLast()) : c.path
        c.path = prefix + path
        c.query = nil
        c.fragment = nil
        return c.url!
    }

    /// True for an IP literal in the ranges above.
    public static func cleartextAllowed(host: String) -> Bool {
        let h = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if let v4 = ipv4(h) {
            let (a, b) = (v4[0], v4[1])
            return a == 127 || a == 10 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168)
                || (a == 100 && (64...127).contains(b))
        }
        if let v6 = ipv6(h) {
            if v6 == [UInt8](repeating: 0, count: 15) + [1] { return true } // ::1
            return v6[0] & 0xFE == 0xFC // fc00::/7
        }
        return false
    }

    static func ipv4(_ s: String) -> [UInt8]? {
        var addr = in_addr()
        guard inet_pton(AF_INET, s, &addr) == 1 else { return nil }
        return withUnsafeBytes(of: &addr) { Array($0) }
    }

    static func ipv6(_ s: String) -> [UInt8]? {
        var addr = in6_addr()
        guard inet_pton(AF_INET6, s, &addr) == 1 else { return nil }
        return withUnsafeBytes(of: &addr) { Array($0) }
    }
}
