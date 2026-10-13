// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation
import Security

/// Each server's `ak_` key, in the Keychain only: never in UserDefaults, a file or a log. The
/// account is the server's id; before several servers, the one key was under `default`.
///
/// Readable after the first unlock since boot and never synced or restored to another device
/// (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`), so a background upload can read it while
/// the phone is locked.
struct KeyStore: SecretStore {
    private static let service = "akou-companion.server-key"

    static let shared = KeyStore()

    private static func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    /// The saved key, or nil when there is none.
    func load(account: String) -> String? {
        var q = Self.query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Saves `secret`, replacing the old one; an empty key deletes it.
    @discardableResult
    func save(_ secret: String, account: String) -> Bool {
        let key = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return delete(account: account) }
        let data = Data(key.utf8)
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(Self.query(account) as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var add = Self.query(account)
        add.merge(update) { _, new in new }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    @discardableResult
    func delete(account: String) -> Bool {
        let status = SecItemDelete(Self.query(account) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    /// A server's key.
    static func key(_ server: UUID) -> String? { shared.load(account: server.uuidString) }
    @discardableResult static func save(_ key: String, server: UUID) -> Bool { shared.save(key, account: server.uuidString) }
    @discardableResult static func delete(server: UUID) -> Bool { shared.delete(account: server.uuidString) }
}
