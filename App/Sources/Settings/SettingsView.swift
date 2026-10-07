// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// The server settings and their test: `GET /v1/server` and `GET /v1/keys/me` on the URL given.
/// The key is kept in the Keychain (`KeyStore`); everything else in `@AppStorage` under the keys
/// in `ServerSettings`.
struct SettingsView: View {
    @AppStorage(ServerSettings.serverURL) private var serverURL = ""
    @AppStorage(ServerSettings.workspaces) private var workspacesJSON = "[]"
    @AppStorage(ServerSettings.defaultWorkspace) private var defaultWorkspace = ""
    @AppStorage(ServerSettings.liveLanguage) private var liveLanguage = "auto"
    @AppStorage(ServerSettings.liveModel) private var liveModel = "auto"
    @AppStorage(ServerSettings.keepLocalCopy) private var keepLocalCopy = false
    @State private var key = ""
    /// The key as the Keychain holds it, so only a real change is saved and retries parked uploads.
    @State private var savedKey = ""
    @State private var newWorkspace = ""
    @State private var engines: [String] = []
    @State private var result: String?
    @State private var testing = false

    private var workspaces: [String] { ServerSettings.decodeWorkspaces(workspacesJSON) }

    var body: some View {
        Form {
            Section("akou server") {
                TextField("https://akou.example.com", text: $serverURL)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("ak_ key", text: $key)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit(saveKey)
            }
            Section {
                Button(testing ? "Testing…" : "Test") { Task { await test() } }
                    .disabled(testing || serverURL.isEmpty || key.isEmpty)
                if let result {
                    Text(result).font(.footnote)
                }
            }
            Section {
                TextField("Language (auto, en, es…)", text: $liveLanguage)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Picker("Live model", selection: $liveModel) {
                    Text("auto").tag("auto")
                    ForEach(modelChoices, id: \.self) { Text($0).tag($0) }
                }
            } header: {
                Text("Live text")
            } footer: {
                Text("The server keeps one live model loaded. Pin the same model on every phone that uses it, or phones that pick another one are refused while it is busy.")
            }
            Section("Workspaces") {
                ForEach(workspaces, id: \.self) { name in
                    Text(name)
                }
                .onDelete(perform: removeWorkspaces)
                HStack {
                    TextField("New workspace", text: $newWorkspace)
                    Button("Add", action: addWorkspace)
                        .disabled(newWorkspace.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                if !workspaces.isEmpty {
                    Picker("New recordings go to", selection: $defaultWorkspace) {
                        Text("None").tag("")
                        ForEach(workspaces, id: \.self) { Text($0).tag($0) }
                    }
                }
            }
            Section {
                Toggle("Keep a copy on this phone", isOn: $keepLocalCopy)
            } footer: {
                Text("Off: a recording is deleted from the phone once the server confirms it kept the audio.")
            }
        }
        .navigationTitle("akou")
        .onAppear {
            savedKey = KeyStore.load() ?? ""
            key = savedKey
        }
        .onDisappear(perform: saveKey)
    }

    /// The models the last test listed, plus a pinned one the server no longer lists, so the
    /// picker never shows an empty choice.
    private var modelChoices: [String] {
        var out = engines
        if liveModel != "auto" && !out.contains(liveModel) { out.append(liveModel) }
        return out
    }

    private func saveKey() {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed != savedKey, KeyStore.save(trimmed) else { return }
        savedKey = trimmed
        // A new key or URL may unblock uploads that were refused with the old one.
        Task { await BackgroundUploader.shared.settingsChanged() }
    }

    private func addWorkspace() {
        let name = newWorkspace.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, !workspaces.contains(name) else { return }
        workspacesJSON = ServerSettings.encodeWorkspaces(workspaces + [name])
        if defaultWorkspace.isEmpty { defaultWorkspace = name }
        newWorkspace = ""
    }

    private func removeWorkspaces(at offsets: IndexSet) {
        var names = workspaces
        names.remove(atOffsets: offsets)
        workspacesJSON = ServerSettings.encodeWorkspaces(names)
        if !names.contains(defaultWorkspace) { defaultWorkspace = names.first ?? "" }
    }

    private func test() async {
        saveKey()
        testing = true
        defer { testing = false }
        guard let url = URL(string: serverURL.trimmingCharacters(in: .whitespaces)) else {
            result = "That is not a URL."
            return
        }
        let probe = ServerProbe(baseURL: url, key: key)
        do {
            let info = try await probe.server()
            let me = try await probe.keyInfo()
            engines = info.liveEngines
            let live = info.supportsLive
                ? "live text with \(info.liveEngines.joined(separator: ", "))"
                : "no live text on this server"
            result = "akou \(info.version ?? "?") (\(info.mode ?? "?")), key \(me.name ?? me.id), \(live)"
            await BackgroundUploader.shared.settingsChanged()
        } catch let f as Endpoint.Failure {
            result = "Refused: \(f). Use https, or plain http only to a private address."
        } catch {
            result = "Failed: \(error)"
        }
    }
}
