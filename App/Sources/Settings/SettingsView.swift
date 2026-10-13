// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// The servers, "Record to", live text, workspaces and the local copy. Each server's key is in the
/// Keychain (`KeyStore`); the servers are a `ServerBook` and everything else is in `@AppStorage`
/// under the keys in `ServerSettings`.
struct SettingsView: View {
    @AppStorage(ServerBook.defaultsKey) private var bookJSON = ""
    @AppStorage(ServerSettings.workspaces) private var workspacesJSON = "[]"
    @AppStorage(ServerSettings.defaultWorkspace) private var defaultWorkspace = ""
    @AppStorage(ServerSettings.liveLanguage) private var liveLanguage = "auto"
    @AppStorage(ServerSettings.liveModel) private var liveModel = "auto"
    @AppStorage(ServerSettings.keepLocalCopy) private var keepLocalCopy = false
    @State private var newWorkspace = ""
    /// The id a new server gets, fixed while its editor is open.
    @State private var newServerID = UUID()

    private var workspaces: [String] { ServerSettings.decodeWorkspaces(workspacesJSON) }

    private var book: ServerBook { ServerBook.decode(bookJSON) ?? ServerBook() }

    var body: some View {
        Form {
            Section {
                ForEach(book.servers) { entry in
                    NavigationLink {
                        ServerEditorView(id: entry.id, isNew: false)
                    } label: {
                        ServerRow(entry: entry, isRecordTo: entry.id == book.recordTo)
                    }
                }
                NavigationLink {
                    ServerEditorView(id: newServerID, isNew: true)
                } label: {
                    Label("Add a server", systemImage: "plus")
                }
                if book.servers.contains(where: \.canBeRecordTo) {
                    Picker("Record to", selection: recordTo) {
                        ForEach(book.servers.filter(\.canBeRecordTo)) { Text($0.name).tag(Optional($0.id)) }
                    }
                }
            } header: {
                Text("Servers")
            } footer: {
                Text(serversFooter)
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
        .onAppear { newServerID = UUID() }
    }

    private var serversFooter: String {
        let readOnly = book.servers.filter { !$0.canBeRecordTo }.map(\.name)
        var text = "Live text, uploads, the widget and the last-recording summary use Record to. Recordings already made keep the server they were made for."
        if !readOnly.isEmpty {
            text += " \(readOnly.joined(separator: ", ")): its key cannot upload, so it cannot be Record to."
        }
        return text
    }

    private var recordTo: Binding<UUID?> {
        Binding(
            get: { book.recordTo },
            set: { id in
                var b = book
                b.recordTo = id
                bookJSON = b.encoded()
                Task { await BackgroundUploader.shared.settingsChanged() }
            })
    }

    /// The models the last Test of "Record to" listed, plus a pinned one the server no longer
    /// lists, so the picker never shows an empty choice.
    private var modelChoices: [String] {
        var out = book.recordToServer?.tested?.liveEngines ?? []
        if liveModel != "auto" && !out.contains(liveModel) { out.append(liveModel) }
        return out
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
}

/// A server in the list: its name, its host and what its key may do.
private struct ServerRow: View {
    let entry: ServerEntry
    let isRecordTo: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(entry.name)
                if isRecordTo {
                    Text("Record to").font(.caption).padding(.horizontal, 6).padding(.vertical, 1)
                        .background(Color.accentColor.opacity(0.15), in: Capsule())
                }
            }
            Text(entry.baseURL?.host(percentEncoded: false) ?? "No URL yet")
                .font(.footnote).foregroundStyle(.secondary)
            Text(ServerEditorView.summary(entry.tested))
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}
