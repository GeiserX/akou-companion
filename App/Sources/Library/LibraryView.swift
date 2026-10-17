// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// The recordings of one server, chosen at the top when there are several. What it shows follows
/// the key's scopes: a key that only uploads shows this app's recordings, with a chip per
/// workspace; a key that reads history shows the server's calls, dictations and files, like the
/// desktop app. Recordings waiting to upload come first. Pull to refresh; it refreshes by itself
/// when the app comes to the foreground.
struct LibraryView: View {
    @State private var router = LibraryRouter.shared
    @AppStorage(ServerBook.defaultsKey) private var bookJSON = ""

    private var book: ServerBook { ServerBook.decode(bookJSON) ?? ServerBook() }

    private var selected: ServerEntry? {
        book.server(router.server) ?? book.recordToServer ?? book.servers.first
    }

    var body: some View {
        NavigationStack(path: $router.path) {
            Group {
                if let server = selected {
                    ServerLibraryView(server: server, book: book).id(server.id)
                } else {
                    ContentUnavailableView {
                        Label("No server yet", systemImage: "server.rack")
                    } description: {
                        Text("Add an akou server in Settings. Recordings made before then wait on this phone.")
                    } actions: {
                        Button("Try the demo") { DemoMode.enter() }
                    }
                }
            }
            .navigationTitle(selected?.name ?? "Recordings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if book.servers.count > 1 {
                    ToolbarItem(placement: .topBarLeading) {
                        Menu {
                            Picker("Server", selection: serverChoice) {
                                ForEach(book.servers) { Text($0.name).tag(Optional($0.id)) }
                            }
                        } label: {
                            Label("Server", systemImage: "server.rack")
                        }
                        .accessibilityLabel("Choose the server")
                    }
                }
            }
            .navigationDestination(for: LibraryRoute.self) { route in
                if let server = selected {
                    switch route {
                    case let .job(id): RecordingDetailView(serverID: server.id, jobId: id)
                    case let .call(id): CallDetailView(serverID: server.id, callId: id)
                    case let .dictation(id): DictationDetailView(serverID: server.id, dictationId: id)
                    }
                }
            }
        }
    }

    /// Choosing another server shows its Library from the top.
    private var serverChoice: Binding<UUID?> {
        Binding(get: { selected?.id }, set: { id in
            router.server = id
            router.path = []
        })
    }
}

/// One server's Library: what its key may see, or one line saying why nothing shows.
private struct ServerLibraryView: View {
    let server: ServerEntry
    let book: ServerBook

    @State private var access = ServerAccess()
    @State private var store = LibraryStore.shared
    @State private var calls = CallsStore()
    @State private var dictations = DictationsStore()
    @State private var tab = HistoryTab.calls
    @Environment(\.scenePhase) private var scenePhase

    enum HistoryTab: String, CaseIterable, Identifiable {
        case calls = "Calls"
        case dictations = "Dictations"
        case files = "Files"
        var id: Self { self }
    }

    /// Calls and dictations exist only on a desktop akou; a server has files only.
    private var tabs: [HistoryTab] {
        if case .history(desktop: true) = access.state { return HistoryTab.allCases }
        return [.files]
    }

    private var shownTab: HistoryTab { tabs.contains(tab) ? tab : .files }

    var body: some View {
        List {
            if case .history = access.state, tabs.count > 1 {
                Picker("Show", selection: $tab) {
                    ForEach(tabs) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }
            WaitingSection(items: store.waiting(for: server.id, book: book), server: server)
            switch access.state {
            case .loading:
                HStack { Spacer(); ProgressView(); Spacer() }
            case .notSetUp:
                Text("Set this server's URL and key in Settings.").foregroundStyle(.secondary)
            case let .refused(why):
                Text(why).foregroundStyle(.secondary)
            case let .unreachable(why):
                Section {
                    HStack {
                        Label("\(server.name) did not answer: \(why)", systemImage: "wifi.slash")
                            .font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                        Spacer()
                        Button("Retry") { Task { await reload() } }
                    }
                }
            case .ownRecordings:
                OwnRecordingsSection(store: store)
            case .history:
                switch shownTab {
                case .calls: CallsSection(store: calls, serverID: server.id)
                case .dictations: DictationsSection(store: dictations, serverID: server.id)
                case .files: FilesSection(store: store)
                }
            }
        }
        .refreshable { await reload() }
        .task { await reload() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await reload() } }
        }
        .onChange(of: shownTab) { _, _ in Task { await loadTab() } }
    }

    private func reload() async {
        await store.loadWaiting()
        await access.load(server)
        await loadTab(force: true)
    }

    private func loadTab(force: Bool = false) async {
        switch access.state {
        case .ownRecordings:
            store.show(server: server.id, everyJob: false)
            await store.refresh()
        case .history:
            switch shownTab {
            case .calls: if force || !calls.loaded { await calls.load(server.id) }
            case .dictations: if force || !dictations.loaded { await dictations.refresh(server.id) }
            case .files:
                store.show(server: server.id, everyJob: true)
                if force || store.jobs.isEmpty { await store.refresh() }
            }
        default:
            break
        }
    }
}

/// The recordings in the upload queue that have no job yet. One whose server was removed offers
/// to go to the server shown, when its key may upload.
private struct WaitingSection: View {
    let items: [UploadQueue.Item]
    let server: ServerEntry

    var body: some View {
        if !items.isEmpty {
            Section("On this iPhone") {
                ForEach(items, id: \.recordingID) { item in
                    VStack(alignment: .leading, spacing: 6) {
                        WaitingRow(item: item)
                        if item.waitsForAServer && server.canBeRecordTo && !DemoMode.isDemo(server) {
                            Button("Send to \(server.name)") {
                                Task {
                                    await BackgroundUploader.shared.send(item.recordingID, to: server.id)
                                    await LibraryStore.shared.loadWaiting()
                                }
                            }
                            .font(.footnote)
                            .buttonStyle(.bordered)
                        }
                    }
                }
            }
        }
    }
}

/// This app's recordings on a server whose key only uploads: the list as before several servers.
private struct OwnRecordingsSection: View {
    @Bindable var store: LibraryStore

    var body: some View {
        if store.workspaces.count > 0 {
            WorkspaceChips(workspaces: store.workspaces, selected: $store.workspace)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
        }
        JobsSection(store: store, empty: "No recordings on the server yet.")
    }
}

/// Every job on the server, from any client, each with its result.
private struct FilesSection: View {
    let store: LibraryStore

    var body: some View {
        JobsSection(store: store, empty: "No files on the server.")
    }
}

private struct JobsSection: View {
    let store: LibraryStore
    let empty: String

    var body: some View {
        Section {
            ForEach(store.shown, id: \.id) { job in
                NavigationLink(value: LibraryRoute.job(job.id)) { JobRow(job: job) }
                    .task {
                        if job.id == store.shown.last?.id { await store.loadMore() }
                    }
            }
            if store.loading {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        } footer: {
            if let error = store.error {
                Text(error)
            } else if store.shown.isEmpty && !store.loading {
                Text(empty)
            }
        }
    }
}

private struct CallsSection: View {
    let store: CallsStore
    let serverID: UUID

    var body: some View {
        Section {
            ForEach(store.calls) { call in
                NavigationLink(value: LibraryRoute.call(call.id)) { CallRow(call: call) }
            }
            if store.loading && store.calls.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        } footer: {
            if store.cannotSee {
                Text("This key cannot see history.")
            } else if let error = store.error {
                Text(error)
            } else if store.loaded && store.calls.isEmpty {
                Text("No calls on this akou.")
            }
        }
    }
}

private struct DictationsSection: View {
    let store: DictationsStore
    let serverID: UUID

    var body: some View {
        Section {
            ForEach(store.items) { d in
                NavigationLink(value: LibraryRoute.dictation(d.id)) { DictationRow(dictation: d) }
                    .task {
                        if d.id == store.items.last?.id { await store.loadMore(serverID) }
                    }
            }
            if store.loading {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        } footer: {
            if store.cannotSee {
                Text("This key cannot see history.")
            } else if let error = store.error {
                Text(error)
            } else if store.loaded && store.items.isEmpty {
                Text("No dictations on this akou.")
            }
        }
    }
}

private struct WorkspaceChips: View {
    let workspaces: [String]
    @Binding var selected: String?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                chip("All", on: selected == nil) { selected = nil }
                ForEach(workspaces, id: \.self) { w in
                    chip(w, on: selected == w) { selected = selected == w ? nil : w }
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
    }

    private func chip(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .buttonStyle(.bordered)
            .tint(on ? .accentColor : .secondary)
            .accessibilityAddTraits(on ? .isSelected : [])
    }
}

private struct WaitingRow: View {
    let item: UploadQueue.Item

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(item.submission.title ?? "Recording")
            HStack(spacing: 6) {
                Image(systemName: symbol)
                Text(state)
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            if let error = item.lastError {
                Text(error).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
        }
    }

    private var state: String {
        switch item.state {
        case .pending: item.attempts == 0 ? "Waiting to upload" : "Waiting to try again"
        case .uploading: "Uploading"
        case .parked: item.waitsForAServer ? "Stopped: its server was removed" : "Stopped: the server refused it"
        case .submitted, .done: "Uploaded"
        }
    }

    private var symbol: String {
        switch item.state {
        case .pending: "clock"
        case .uploading: "arrow.up.circle"
        case .parked: "exclamationmark.triangle"
        case .submitted, .done: "checkmark.circle"
        }
    }
}

struct CallRow: View {
    let call: CallSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(call.title.isEmpty ? "Untitled call" : call.title).lineLimit(1)
            HStack(spacing: 6) {
                if let d = LibraryStore.date(ms: call.createdAt) {
                    Text(d, format: .dateTime.day().month().hour().minute())
                }
                if let end = call.endedAt {
                    Text("· \(Duration.seconds(max(0, (end - call.createdAt) / 1000)).formatted(.units(allowed: [.hours, .minutes], width: .narrow)))")
                }
                if !call.workspace.isEmpty { Text("· \(call.workspace)") }
                if call.isLive { Text("· recording").foregroundStyle(.red) }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
    }
}

struct DictationRow: View {
    let dictation: Dictation

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(dictation.text.flatMap { $0.isEmpty ? nil : $0 } ?? "No text (\(dictation.state))").lineLimit(2)
            HStack(spacing: 6) {
                if let d = LibraryStore.date(ms: dictation.at) {
                    Text(d, format: .dateTime.day().month().hour().minute())
                }
                if let app = dictation.appName { Text("· \(app)") }
                if let s = dictation.seconds { Text("· \(Int(s.rounded())) s") }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
    }
}

struct JobRow: View {
    let job: Job

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(job.title ?? "Untitled recording").lineLimit(1)
            HStack(spacing: 6) {
                if let d = LibraryStore.date(job.createdAt) {
                    Text(d, format: .dateTime.day().month().hour().minute())
                }
                if let w = job.metadata?.workspace, !w.isEmpty { Text("· \(w)") }
                if job.status != "done" { Text("· \(job.status)") }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
    }
}
