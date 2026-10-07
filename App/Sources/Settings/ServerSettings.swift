// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The settings other parts of the app read, under the `@AppStorage` keys they use. The key is
/// not here: it lives in the Keychain (`KeyStore`).
enum ServerSettings {
    /// The akou server's base URL, for example `https://akou.example.com`.
    static let serverURL = "serverURL"
    /// The workspace names, as a JSON array of strings.
    static let workspaces = "workspaces"
    /// The workspace a new recording gets.
    static let defaultWorkspace = "defaultWorkspace"
    /// `auto` or a BCP 47 code, for the live `hello` and the upload.
    static let liveLanguage = "liveLanguage"
    /// `auto` or a streaming model id from `live.engines`. The server keeps one streaming model
    /// loaded, so pinning the same one on every phone avoids 4409 `engine_busy`.
    static let liveModel = "liveModel"
    /// Keep the recording on the phone after the server confirms it kept the audio.
    static let keepLocalCopy = "keepLocalCopy"

    static var defaults: UserDefaults { .standard }

    static var url: URL? {
        guard let s = defaults.string(forKey: serverURL)?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        return URL(string: s)
    }

    static var keepsLocalCopy: Bool { defaults.bool(forKey: keepLocalCopy) }

    static var language: String { defaults.string(forKey: liveLanguage).flatMap { $0.isEmpty ? nil : $0 } ?? "auto" }

    static var model: String { defaults.string(forKey: liveModel).flatMap { $0.isEmpty ? nil : $0 } ?? "auto" }

    static var workspace: String? { defaults.string(forKey: defaultWorkspace).flatMap { $0.isEmpty ? nil : $0 } }

    static func decodeWorkspaces(_ json: String) -> [String] {
        (try? JSONDecoder().decode([String].self, from: Data(json.utf8))) ?? []
    }

    static func encodeWorkspaces(_ names: [String]) -> String {
        (try? JSONEncoder().encode(names)).map { String(decoding: $0, as: UTF8.self) } ?? "[]"
    }
}
