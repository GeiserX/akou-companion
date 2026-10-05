// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// The settings screen's test: is this an akou server, does the key work, and can it show live text.
public struct ServerProbe: Sendable {
    public enum Failure: Error, Equatable {
        /// An answer other than 2xx, with akou's error `code` when the body carried one.
        case status(Int, code: String?)
        /// A 2xx answer that is not akou's JSON.
        case notAkou
    }

    public let baseURL: URL
    private let key: String
    private let session: URLSession

    public init(baseURL: URL, key: String, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.key = key
        self.session = session
    }

    /// `GET /v1/server`.
    public func server() async throws -> ServerInfo {
        let info: ServerInfo = try await get("/v1/server")
        guard info.name == "akou" else { throw Failure.notAkou }
        return info
    }

    /// `GET /v1/keys/me`: the key's id and scopes. A revoked or mistyped key answers 401.
    public func keyInfo() async throws -> KeyInfo {
        try await get("/v1/keys/me")
    }

    private func get<T: Decodable>(_ path: String) async throws -> T {
        var req = URLRequest(url: try Endpoint.api(baseURL, path))
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        let (body, response) = try await session.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let code = (try? JSONDecoder().decode(ErrorBody.self, from: body))?.code
            throw Failure.status(status, code: code)
        }
        do {
            return try JSONDecoder().decode(T.self, from: body)
        } catch {
            throw Failure.notAkou
        }
    }

    /// akou refuses with `{"error": "<code>", "message": "..."}`.
    private struct ErrorBody: Decodable {
        var error: String?
        var code: String? { error }
    }
}
