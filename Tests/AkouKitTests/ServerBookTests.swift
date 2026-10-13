// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import Foundation
import XCTest

/// The Keychain as a dictionary, with a switch to refuse writes.
final class MemorySecrets: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: String] = [:]
    var refuseWrites = false

    init(_ items: [String: String] = [:]) { self.items = items }

    var all: [String: String] { lock.withLock { items } }
    func load(account: String) -> String? { lock.withLock { items[account] } }
    func save(_ secret: String, account: String) -> Bool {
        lock.withLock {
            guard !refuseWrites else { return false }
            items[account] = secret
            return true
        }
    }
    func delete(account: String) -> Bool { lock.withLock { items[account] = nil; return true } }
}

final class ServerBookTests: XCTestCase {
    var defaults: UserDefaults!
    var suite: String!
    let fixedID = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!

    override func setUp() {
        suite = "akou-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    /// The phone before several servers: one URL in UserDefaults, one key in the Keychain.
    func oldPhone() -> MemorySecrets {
        defaults.set("https://akou.example.com", forKey: ServerMigration.legacyURLKey)
        return MemorySecrets([ServerMigration.legacyAccount: "ak_old_key"])
    }

    func testTheOldServerBecomesTheFirstServerAndRecordTo() {
        let secrets = oldPhone()
        let book = ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { self.fixedID })
        XCTAssertEqual(book.servers, [ServerEntry(id: fixedID, name: "akou.example.com", url: "https://akou.example.com")])
        XCTAssertEqual(book.recordTo, fixedID)
        XCTAssertEqual(ServerBook.load(defaults), book, "the book is saved")
        XCTAssertEqual(secrets.all, [fixedID.uuidString: "ak_old_key"], "the key moved to the server's own entry, and the old one is gone")
        XCTAssertNil(defaults.object(forKey: ServerMigration.legacyURLKey))
        XCTAssertNil(defaults.object(forKey: ServerMigration.migratingIDKey))
    }

    func testRunningItAgainChangesNothing() {
        let secrets = oldPhone()
        let first = ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { self.fixedID })
        let again = ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { UUID() })
        XCTAssertEqual(first, again)
        XCTAssertEqual(secrets.all, [fixedID.uuidString: "ak_old_key"])
    }

    /// A launch cut off after the key was copied but before the book was written: the next launch
    /// reuses the same id, so no second server and no stray Keychain entry.
    func testAnInterruptedMigrationResumesWithTheSameServer() {
        let secrets = oldPhone()
        defaults.set(fixedID.uuidString, forKey: ServerMigration.migratingIDKey)
        _ = secrets.save("ak_old_key", account: fixedID.uuidString)
        let book = ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { UUID() })
        XCTAssertEqual(book.servers.map(\.id), [fixedID])
        XCTAssertEqual(secrets.all, [fixedID.uuidString: "ak_old_key"])
    }

    /// Cut off after the book was written but before the old entries were removed: the next launch
    /// keeps the book and only removes them.
    func testAMigrationCutBeforeItsCleanUpFinishesIt() {
        let secrets = oldPhone()
        defaults.set(fixedID.uuidString, forKey: ServerMigration.migratingIDKey)
        _ = secrets.save("ak_old_key", account: fixedID.uuidString)
        let book = ServerBook(servers: [ServerEntry(id: fixedID, name: "akou.example.com", url: "https://akou.example.com")], recordTo: fixedID)
        book.save(defaults)
        XCTAssertEqual(ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { UUID() }), book)
        XCTAssertEqual(secrets.all, [fixedID.uuidString: "ak_old_key"])
        XCTAssertNil(defaults.object(forKey: ServerMigration.legacyURLKey))
    }

    /// The Keychain refused the copy: nothing is written and the old key stays, for the next launch.
    func testAKeyThatCannotBeCopiedStaysWhereItWas() {
        let secrets = oldPhone()
        secrets.refuseWrites = true
        let book = ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { self.fixedID })
        XCTAssertTrue(book.servers.isEmpty)
        XCTAssertNil(ServerBook.load(defaults))
        XCTAssertEqual(secrets.all, [ServerMigration.legacyAccount: "ak_old_key"])
        XCTAssertEqual(defaults.string(forKey: ServerMigration.legacyURLKey), "https://akou.example.com")
        secrets.refuseWrites = false
        XCTAssertEqual(ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { UUID() }).servers.map(\.id), [fixedID])
    }

    /// An old URL and key that turn up beside a book that does not hold them (a backup restored
    /// after the move) become one more server; the old key is never deleted without its copy.
    func testAnOldPairBesideABookThatLacksItIsAddedNeverDropped() {
        let other = ServerEntry(name: "other", url: "https://other.example.com")
        let secrets = oldPhone()
        _ = secrets.save("ak_other", account: other.id.uuidString)
        ServerBook(servers: [other], recordTo: other.id).save(defaults)
        let book = ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { self.fixedID })
        XCTAssertEqual(book.servers.map(\.id), [other.id, fixedID])
        XCTAssertEqual(book.recordTo, other.id, "Record to stays where it was")
        XCTAssertEqual(secrets.all, [other.id.uuidString: "ak_other", fixedID.uuidString: "ak_old_key"])
        XCTAssertNil(defaults.object(forKey: ServerMigration.legacyURLKey))
    }

    func testANewPhoneStartsWithNoServers() {
        let secrets = MemorySecrets()
        let book = ServerMigration.run(defaults: defaults, secrets: secrets)
        XCTAssertEqual(book, ServerBook())
        XCTAssertEqual(ServerBook.load(defaults), ServerBook())
    }

    /// A key saved before any URL is kept too: the server shows in Settings for its URL to be typed.
    func testAKeyWithoutAURLIsKept() {
        let secrets = MemorySecrets([ServerMigration.legacyAccount: "ak_lonely"])
        let book = ServerMigration.run(defaults: defaults, secrets: secrets, makeID: { self.fixedID })
        XCTAssertEqual(book.servers.first?.name, "akou")
        XCTAssertEqual(book.servers.first?.url, "")
        XCTAssertEqual(secrets.all, [fixedID.uuidString: "ak_lonely"])
    }

    func testRecordToFollowsAddsAndRemoves() {
        var book = ServerBook()
        let a = ServerEntry(name: "a", url: "https://a.example.com")
        let readOnly = ServerEntry(name: "r", url: "https://r.example.com", tested: .init(version: "0.7", mode: "app", keyName: "phone", scopes: ["read"], live: false, liveEngines: []))
        let b = ServerEntry(name: "b", url: "https://b.example.com")
        book.upsert(readOnly)
        XCTAssertNil(book.recordTo, "a read key cannot take recordings")
        book.upsert(a)
        book.upsert(b)
        XCTAssertEqual(book.recordTo, a.id, "the first server that may take recordings")
        book.remove(a.id)
        XCTAssertEqual(book.recordTo, b.id, "the next one that may take recordings, never the read-only one")
        book.remove(b.id)
        XCTAssertNil(book.recordTo)
        XCTAssertEqual(book.servers.map(\.id), [readOnly.id])
    }

    /// A Test that finds "Record to"'s key cannot upload moves "Record to" to one that can.
    func testRecordToLeavesAKeyThatCannotUpload() {
        var a = ServerEntry(name: "a", url: "https://a.example.com")
        let b = ServerEntry(name: "b", url: "https://b.example.com")
        var book = ServerBook()
        book.upsert(a)
        book.upsert(b)
        XCTAssertEqual(book.recordTo, a.id)
        a.tested = .init(version: nil, mode: "app", keyName: "viewer", scopes: ["read"], live: false, liveEngines: [])
        book.upsert(a)
        XCTAssertEqual(book.recordTo, b.id)
    }
}
