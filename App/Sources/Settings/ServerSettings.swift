// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation

/// A server recordings can go to: its id, base URL and key.
struct RecordTarget: Equatable {
    var id: UUID
    var url: URL
    var key: String
}

/// The settings other parts of the app read, under the `@AppStorage` keys they use. The servers
/// are a `ServerBook` under `ServerBook.defaultsKey`; their keys live in the Keychain (`KeyStore`).
enum ServerSettings {
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
    /// The server the Library shows, by id.
    static let libraryServer = "libraryServer"

    static var defaults: UserDefaults { .standard }

    /// Every server, after the one-time move from the single server (`ServerMigration`), which
    /// does nothing once done.
    static var book: ServerBook {
        ServerMigration.run(defaults: defaults, secrets: KeyStore.shared)
    }

    static func save(_ book: ServerBook) { book.save(defaults) }

    /// "Record to": the server live text, uploads, the widget snapshot and the last-recording
    /// intent use. Nil without one, or while its URL or key is missing.
    static var recordTo: RecordTarget? { target(book.recordTo) }

    static func target(_ id: UUID?) -> RecordTarget? {
        guard let id, let entry = book.server(id), let url = entry.baseURL,
              let key = KeyStore.key(id), !key.isEmpty else { return nil }
        return RecordTarget(id: id, url: url, key: key)
    }

    static func jobsClient(_ id: UUID?) -> JobsClient? {
        target(id).map { JobsClient(baseURL: $0.url, key: $0.key) }
    }

    static func historyClient(_ id: UUID?) -> HistoryClient? {
        target(id).map { HistoryClient(baseURL: $0.url, key: $0.key) }
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
