// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// One server: its name, URL and key, and the Test (`GET /v1/server` and `GET /v1/keys/me`), which
/// says what the server is and what this key may do there. The key goes to the Keychain under the
/// server's id; the rest to the server list (`ServerBook`).
struct ServerEditorView: View {
    let id: UUID
    let isNew: Bool

    @State private var name = ""
    @State private var url = ""
    @State private var key = ""
    /// The key as the Keychain holds it, so only a real change is saved and retries parked uploads.
    @State private var savedKey = ""
    @State private var tested: ServerEntry.Tested?
    @State private var result: String?
    @State private var testing = false
    @State private var confirmingDelete = false
    @State private var deleted = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("akou server") {
                TextField("Name (optional)", text: $name)
                    .autocorrectionDisabled()
                TextField("https://akou.example.com", text: $url)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("ak_ key", text: $key)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit(save)
            }
            Section {
                Button(testing ? "Testing…" : "Test") { Task { await test() } }
                    .disabled(testing || url.isEmpty || key.isEmpty)
                if let result {
                    Text(result).font(.footnote)
                }
                if let tested {
                    ForEach(Self.abilities(tested), id: \.self) { line in
                        Label(line.text, systemImage: line.allowed ? "checkmark.circle" : "xmark.circle")
                            .font(.footnote)
                            .foregroundStyle(line.allowed ? .primary : .secondary)
                    }
                }
            } footer: {
                Text("The key comes from the server's Keys page or `akou keys create`. Its scopes decide what this phone may do there: `jobs` uploads recordings, `read` shows every call, dictation and file, `admin` does both.")
            }
            if !isNew {
                Section {
                    Button("Delete this server", role: .destructive) { confirmingDelete = true }
                }
            }
        }
        .navigationTitle(isNew ? "New server" : (name.isEmpty ? "Server" : name))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isNew {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        save()
                        dismiss()
                    }
                    .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .confirmationDialog("Delete \(name.isEmpty ? "this server" : name)?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive, action: delete)
        } message: {
            Text("Its key is removed from this phone. Recordings still waiting for it stay on this phone, stopped, until you send them to another server from Recordings.")
        }
        .onAppear(perform: load)
        .onDisappear {
            // Like the rest of Settings, a change is kept when leaving; a new server only once it has something.
            if !deleted && (!isNew || !url.isEmpty || !key.isEmpty) { save() }
        }
    }

    private func load() {
        guard let entry = ServerSettings.book.server(id) else { return }
        name = entry.name
        url = entry.url
        tested = entry.tested
        savedKey = KeyStore.key(id) ?? ""
        key = savedKey
    }

    private func save() {
        let trimmedURL = url.trimmingCharacters(in: .whitespaces)
        var book = ServerSettings.book
        var entry = book.server(id) ?? ServerEntry(id: id, name: "", url: trimmedURL)
        // A new address is a new server as far as the last Test knows.
        if entry.url != trimmedURL { entry.tested = nil; tested = nil }
        entry.url = trimmedURL
        let typed = name.trimmingCharacters(in: .whitespaces)
        entry.name = typed.isEmpty ? ServerEntry.name(for: trimmedURL) : typed
        if let tested { entry.tested = tested }
        let changed = book.server(id) != entry
        book.upsert(entry)
        if changed { ServerSettings.save(book) }
        let trimmedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        let keyChanged = trimmedKey != savedKey && KeyStore.save(trimmedKey, server: id)
        if keyChanged { savedKey = trimmedKey }
        // A new key or URL may unblock uploads that were refused with the old one.
        if changed || keyChanged { Task { await BackgroundUploader.shared.settingsChanged() } }
    }

    private func delete() {
        deleted = true
        var book = ServerSettings.book
        book.remove(id)
        ServerSettings.save(book)
        KeyStore.delete(server: id)
        // Its waiting recordings are parked on the next pass, never sent elsewhere on a guess.
        Task { await BackgroundUploader.shared.settingsChanged() }
        dismiss()
    }

    private func test() async {
        save()
        testing = true
        defer { testing = false }
        guard let base = URL(string: url.trimmingCharacters(in: .whitespaces)) else {
            result = "That is not a URL."
            return
        }
        // The key as saved: a pasted trailing newline must not fail the Test of a key that works.
        let probe = ServerProbe(baseURL: base, key: key.trimmingCharacters(in: .whitespacesAndNewlines))
        do {
            let info = try await probe.server()
            let me = try await probe.keyInfo()
            tested = ServerEntry.Tested(
                version: info.version, mode: info.mode, keyName: me.name ?? me.id, scopes: me.scopes,
                live: info.supportsLive, liveEngines: info.liveEngines)
            let live = info.supportsLive
                ? "live text with \(info.liveEngines.joined(separator: ", "))"
                : "no live text on this server"
            result = "akou \(info.version ?? "?") (\(info.mode ?? "?")), key \(me.name ?? me.id) (\(me.scopes.joined(separator: ", "))), \(live)"
            save()
        } catch let f as Endpoint.Failure {
            result = "Refused: \(f). Use https, or plain http only to a private address."
        } catch ServerProbe.Failure.status(401, _) {
            result = "The server refused the key: it is mistyped or was revoked."
        } catch {
            result = "Failed: \(error.localizedDescription)"
        }
    }

    struct Ability: Hashable {
        var text: String
        var allowed: Bool
    }

    /// What the key may do here, line by line, from its scopes and the server's mode.
    static func abilities(_ t: ServerEntry.Tested) -> [Ability] {
        let desktop = t.mode == "app"
        return [
            Ability(text: t.canUpload ? "Uploads recordings: it can be Record to" : "Cannot upload: it cannot be Record to", allowed: t.canUpload),
            Ability(text: t.seesHistory
                ? (desktop ? "Sees every call, dictation and file" : "Sees every file (calls and dictations live on a desktop akou)")
                : "Sees only the recordings this key uploaded", allowed: t.seesHistory),
        ]
    }

    /// One line for the server list.
    static func summary(_ t: ServerEntry.Tested?) -> String {
        guard let t else { return "Not tested yet" }
        switch (t.canUpload, t.seesHistory) {
        case (true, true): return "Records and sees history"
        case (true, false): return "Records; sees its own recordings"
        case (false, true): return "Sees history; cannot record"
        case (false, false): return "This key can do nothing the app uses"
        }
    }
}
