// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// The recordings: those waiting to upload first, then the server's, newest first, with a chip
/// per workspace. Pull to refresh; it refreshes by itself when the app comes to the foreground.
struct LibraryView: View {
    @State private var store = LibraryStore.shared
    @State private var router = LibraryRouter.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack(path: $router.path) {
            List {
                if store.workspaces.count > 0 {
                    WorkspaceChips(workspaces: store.workspaces, selected: $store.workspace)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
                if !store.waiting.isEmpty {
                    Section("On this iPhone") {
                        ForEach(store.waiting, id: \.recordingID) { item in
                            WaitingRow(item: item)
                        }
                    }
                }
                Section {
                    ForEach(store.shown, id: \.id) { job in
                        NavigationLink(value: job.id) { JobRow(job: job) }
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
                        Text("No recordings on the server yet.")
                    }
                }
            }
            .navigationTitle("Recordings")
            .navigationDestination(for: String.self) { id in
                RecordingDetailView(jobId: id)
            }
            .refreshable { await store.refresh() }
            .task { await store.refresh() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await store.refresh() } }
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
        case .parked: "Stopped: the server refused it"
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
