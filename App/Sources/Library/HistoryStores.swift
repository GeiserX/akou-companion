// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import Foundation
import Observation

/// What the Library can show for one server, from its key's scopes (`GET /v1/keys/me`) and its
/// mode (`GET /v1/server`): only this app's recordings, or the server's history.
@MainActor
@Observable
final class ServerAccess {
    enum State: Equatable {
        case loading
        /// No URL or no key in Settings.
        case notSetUp
        /// No answer (a sleeping Mac, no network): one line and a retry.
        case unreachable(String)
        /// The key was refused, or the address is not allowed.
        case refused(String)
        /// A key that only uploads: this app's recordings, as before several servers.
        case ownRecordings
        /// A key with `read` or `admin`: calls and dictations (on a desktop akou) and every file.
        case history(desktop: Bool)
    }

    private(set) var state: State = .loading

    func load(_ server: ServerEntry) async {
        guard let url = server.baseURL, let key = KeyStore.key(server.id), !key.isEmpty else {
            state = .notSetUp
            return
        }
        if case .history = state {} else if state != .ownRecordings { state = .loading }
        let probe = ServerProbe(baseURL: url, key: key)
        do {
            let me = try await probe.keyInfo()
            var mode = server.tested?.mode
            if me.seesHistory { mode = (try? await probe.server())?.mode ?? mode }
            remember(server.id, me: me, mode: mode)
            state = me.seesHistory ? .history(desktop: mode == "app") : .ownRecordings
        } catch ServerProbe.Failure.status(401, _) {
            state = .refused("The server refused this key: it is mistyped or was revoked. Check it in Settings.")
        } catch let f as Endpoint.Failure {
            state = .refused("Refused: \(f). Use https, or plain http only to a private address.")
        } catch ServerProbe.Failure.notAkou {
            state = .unreachable("That address did not answer as an akou server.")
        } catch let ServerProbe.Failure.status(status, code) {
            state = .unreachable("HTTP \(status)\(code.map { ": \($0)" } ?? "")")
        } catch {
            state = .unreachable(error.localizedDescription)
        }
    }

    /// Keeps Settings in step with what the key may do now, so "Record to" never names a key that
    /// can no longer upload.
    private func remember(_ id: UUID, me: KeyInfo, mode: String?) {
        var book = ServerSettings.book
        guard var entry = book.server(id) else { return }
        var t = entry.tested ?? ServerEntry.Tested(version: nil, mode: mode, keyName: me.name, scopes: me.scopes, live: false, liveEngines: [])
        guard t.scopes != me.scopes || t.mode != mode || entry.tested == nil else { return }
        t.scopes = me.scopes
        t.mode = mode
        t.keyName = me.name ?? t.keyName
        entry.tested = t
        book.upsert(entry)
        ServerSettings.save(book)
    }
}

/// A server's calls, newest first (`GET /v1/calls`, metadata only).
@MainActor
@Observable
final class CallsStore {
    private(set) var calls: [CallSummary] = []
    private(set) var loading = false
    private(set) var loaded = false
    private(set) var error: String?
    /// 401 or 403: shown once, never retried in a loop.
    private(set) var cannotSee = false

    func load(_ server: UUID) async {
        guard !loading, let client = ServerSettings.historyClient(server) else { return }
        loading = true
        defer { loading = false; loaded = true }
        do {
            calls = try await client.calls()
            error = nil
            cannotSee = false
        } catch HistoryClient.Failure.cannotSeeHistory {
            cannotSee = true
        } catch {
            self.error = LibraryStore.describe(error)
        }
    }
}

/// A server's dictations, newest first, a page at a time (`GET /v1/dictations`).
@MainActor
@Observable
final class DictationsStore {
    private(set) var items: [Dictation] = []
    private(set) var cursor: String?
    private(set) var loading = false
    private(set) var loaded = false
    private(set) var error: String?
    private(set) var cannotSee = false

    func refresh(_ server: UUID) async {
        guard !loading, let client = ServerSettings.historyClient(server) else { return }
        loading = true
        defer { loading = false; loaded = true }
        do {
            let page = try await client.dictations()
            items = page.items
            cursor = page.nextCursor
            error = nil
            cannotSee = false
        } catch HistoryClient.Failure.cannotSeeHistory {
            cannotSee = true
        } catch {
            self.error = LibraryStore.describe(error)
        }
    }

    func loadMore(_ server: UUID) async {
        guard !loading, let next = cursor, let client = ServerSettings.historyClient(server) else { return }
        loading = true
        defer { loading = false }
        do {
            let page = try await client.dictations(cursor: next)
            let known = Set(items.map(\.id))
            items += page.items.filter { !known.contains($0.id) }
            cursor = page.nextCursor
        } catch {
            self.error = LibraryStore.describe(error)
        }
    }
}
