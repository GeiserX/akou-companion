// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// One akou server the phone knows: its name, its URL and what its last Test learned. Its `ak_`
/// key is never here: it lives in the Keychain under the server's id (`SecretStore`).
public struct ServerEntry: Codable, Sendable, Equatable, Identifiable {
    public var id: UUID
    public var name: String
    /// The base URL as typed, for example `https://akou.example.com`.
    public var url: String
    /// What the last Test learned; nil before the first one.
    public var tested: Tested?

    public struct Tested: Codable, Sendable, Equatable {
        public var version: String?
        /// `server` or `app` (a desktop akou, the only mode with calls and dictations).
        public var mode: String?
        public var keyName: String?
        public var scopes: [String]
        public var live: Bool
        public var liveEngines: [String]

        public init(version: String?, mode: String?, keyName: String?, scopes: [String], live: Bool, liveEngines: [String]) {
            self.version = version
            self.mode = mode
            self.keyName = keyName
            self.scopes = scopes
            self.live = live
            self.liveEngines = liveEngines
        }

        public var canUpload: Bool { scopes.contains("jobs") || scopes.contains("admin") }
        public var seesHistory: Bool { scopes.contains("read") || scopes.contains("admin") }
    }

    public init(id: UUID = UUID(), name: String, url: String, tested: Tested? = nil) {
        self.id = id
        self.name = name
        self.url = url
        self.tested = tested
    }

    public var baseURL: URL? {
        let s = url.trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? nil : URL(string: s)
    }

    /// May recordings go here? Until a Test says otherwise, yes; after one, only with `jobs` or `admin`.
    public var canBeRecordTo: Bool { tested?.canUpload ?? true }

    /// The host of a URL, for naming a server; "akou" when there is none.
    public static func name(for url: String) -> String {
        URL(string: url.trimmingCharacters(in: .whitespaces))?.host(percentEncoded: false).flatMap { $0.isEmpty ? nil : $0 } ?? "akou"
    }
}

/// Every server the phone knows and the one recordings go to ("Record to"): live text, uploads,
/// the widget's snapshot and the last-recording intent use it. Kept as JSON in `UserDefaults`
/// under `defaultsKey`.
public struct ServerBook: Codable, Sendable, Equatable {
    public static let defaultsKey = "servers"

    public var servers: [ServerEntry]
    public var recordTo: UUID?

    public init(servers: [ServerEntry] = [], recordTo: UUID? = nil) {
        self.servers = servers
        self.recordTo = recordTo
    }

    public func server(_ id: UUID?) -> ServerEntry? {
        guard let id else { return nil }
        return servers.first { $0.id == id }
    }

    public var recordToServer: ServerEntry? { server(recordTo) }

    /// Adds or replaces a server. The first server that may take recordings becomes "Record to";
    /// "Record to" whose key turns out unable to upload passes to the next one that can.
    public mutating func upsert(_ entry: ServerEntry) {
        if let i = servers.firstIndex(where: { $0.id == entry.id }) {
            servers[i] = entry
        } else {
            servers.append(entry)
        }
        if recordToServer?.canBeRecordTo != true { recordTo = servers.first(where: \.canBeRecordTo)?.id }
    }

    /// Removes a server. When it was "Record to", the next server that may take recordings is.
    /// Its key is the caller's to delete; its queued recordings stay, parked (`UploadQueue`).
    public mutating func remove(_ id: UUID) {
        servers.removeAll { $0.id == id }
        if recordTo == id { recordTo = servers.first(where: \.canBeRecordTo)?.id }
    }

    public static func decode(_ json: String) -> ServerBook? {
        try? JSONDecoder().decode(ServerBook.self, from: Data(json.utf8))
    }

    public func encoded() -> String {
        (try? JSONEncoder().encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? #"{"servers":[]}"#
    }

    public static func load(_ defaults: UserDefaults) -> ServerBook? {
        defaults.string(forKey: defaultsKey).flatMap(decode)
    }

    public func save(_ defaults: UserDefaults) {
        defaults.set(encoded(), forKey: Self.defaultsKey)
    }
}

/// Where the keys live: the Keychain in the app, a dictionary in the tests. `account` is the
/// server's id as a string.
public protocol SecretStore: Sendable {
    func load(account: String) -> String?
    @discardableResult func save(_ secret: String, account: String) -> Bool
    @discardableResult func delete(account: String) -> Bool
}

/// The move from one server to several, once. Before, the phone kept one URL in `UserDefaults`
/// (`serverURL`) and one key in the Keychain (account `default`). The first launch with several
/// servers makes them the first server, named by its host, and "Record to".
///
/// Safe to run on every launch and to cut off at any point:
/// 1. the new server's id is chosen once and kept (`migratingIDKey`), so a rerun reuses it;
/// 2. the key is copied to the new id's Keychain entry;
/// 3. the server is written into the book;
/// 4. the old URL, the old key and the kept id are removed.
/// The old key is removed only once the book holds a server with its copy. An old pair that turns
/// up beside a book that does not hold it is added as one more server, never dropped.
public enum ServerMigration {
    public static let legacyURLKey = "serverURL"
    public static let legacyAccount = "default"
    public static let migratingIDKey = "servers.migratingID"

    @discardableResult
    public static func run(defaults: UserDefaults, secrets: some SecretStore, makeID: () -> UUID = UUID.init) -> ServerBook {
        let existing = ServerBook.load(defaults)
        let url = defaults.string(forKey: legacyURLKey)?.trimmingCharacters(in: .whitespaces) ?? ""
        let key = secrets.load(account: legacyAccount)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let keptID = defaults.string(forKey: migratingIDKey).flatMap(UUID.init(uuidString:))
        guard !url.isEmpty || !key.isEmpty else {
            let book = existing ?? ServerBook()
            if existing == nil { book.save(defaults) }
            if defaults.object(forKey: legacyURLKey) != nil || keptID != nil { cleanUp(defaults: defaults, secrets: secrets) }
            return book
        }
        if let existing, holds(existing, url: url, key: key, keptID: keptID, secrets: secrets) {
            cleanUp(defaults: defaults, secrets: secrets)
            return existing
        }
        let id = keptID ?? {
            let new = makeID()
            defaults.set(new.uuidString, forKey: migratingIDKey)
            return new
        }()
        if !key.isEmpty {
            // Without the key in its new place nothing is written: the next launch tries again.
            guard secrets.save(key, account: id.uuidString) else { return existing ?? ServerBook() }
        }
        var book = existing ?? ServerBook()
        book.upsert(ServerEntry(id: id, name: ServerEntry.name(for: url), url: url))
        book.save(defaults)
        cleanUp(defaults: defaults, secrets: secrets)
        return book
    }

    /// Whether the book already holds the old pair: the server the migration made, or a server
    /// whose own key is the old key (or, with no old key, whose URL is the old URL).
    private static func holds(_ book: ServerBook, url: String, key: String, keptID: UUID?, secrets: some SecretStore) -> Bool {
        if let keptID, book.server(keptID) != nil, key.isEmpty || secrets.load(account: keptID.uuidString) == key { return true }
        if key.isEmpty { return book.servers.contains { $0.url == url } }
        return book.servers.contains { secrets.load(account: $0.id.uuidString) == key }
    }

    private static func cleanUp(defaults: UserDefaults, secrets: some SecretStore) {
        secrets.delete(account: legacyAccount)
        defaults.removeObject(forKey: legacyURLKey)
        defaults.removeObject(forKey: migratingIDKey)
    }
}
