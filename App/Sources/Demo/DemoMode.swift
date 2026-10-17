// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation

/// The demo: a server inside the app (`DemoServer`) holding three sample readings, live text from
/// a fixed script (`DemoLiveText`), and every recording made in it kept on the phone as a demo job
/// with that script as its transcript. Nothing in it reaches the network. It is on while the server
/// list holds the demo's server, which is then "Record to"; the rest of the app treats it as any
/// server, through the same clients.
@MainActor
enum DemoMode {
    /// Set once the first-run screen was answered; cleared when leaving the demo leaves no server,
    /// so that screen offers pairing again.
    static let welcomedKey = "welcomed"

    static var serverID: UUID? { ServerSettings.book.servers.first(where: isDemo)?.id }

    static var isOn: Bool { serverID != nil }

    static func isDemo(_ entry: ServerEntry) -> Bool { DemoServer.handles(entry.baseURL) }

    static func isDemo(_ id: UUID?) -> Bool { id != nil && id == serverID }

    /// Where the recordings made in the demo are kept until the app ends or the demo is left.
    static var directory: URL { URL.applicationSupportDirectory.appending(path: "Demo", directoryHint: .isDirectory) }

    /// Adds the demo's server, makes it "Record to" and fills it with the samples.
    static func enter() {
        var book = ServerSettings.book
        let id = serverID ?? UUID()
        if book.server(id) == nil {
            let tested = ServerEntry.Tested(
                version: "demo", mode: "server", keyName: "demo", scopes: ["jobs"], live: true, liveEngines: [DemoServer.engine])
            KeyStore.save(DemoServer.key, server: id)
            book.upsert(ServerEntry(id: id, name: "Demo server", url: DemoServer.baseURL.absoluteString, tested: tested))
        }
        book.recordTo = id
        ServerSettings.save(book)
        UserDefaults.standard.set(true, forKey: welcomedKey)
        start()
        LibraryRouter.shared.server = id
        LibraryRouter.shared.path = []
    }

    /// Removes the demo's server, its key and every recording made in it. With no other server
    /// left, the first-run screen shows again.
    static func leave() {
        guard let id = serverID else { return }
        var book = ServerSettings.book
        book.remove(id)
        ServerSettings.save(book)
        KeyStore.delete(server: id)
        DemoServer.set([])
        try? FileManager.default.removeItem(at: directory)
        LibraryRouter.shared.server = nil
        LibraryRouter.shared.path = []
        // The widget showed the demo's recordings.
        SnapshotWriter.listRefreshed([])
        if book.servers.isEmpty { UserDefaults.standard.set(false, forKey: welcomedKey) }
        Task { await BackgroundUploader.shared.settingsChanged() }
    }

    /// At launch and on entering: the samples, and none of an earlier run's recordings.
    static func start() {
        guard isOn else { return }
        try? FileManager.default.removeItem(at: directory)
        DemoServer.set(samples())
    }

    /// A recording made in the demo becomes a done job on the demo's server: its own audio, and as
    /// its transcript the words the live script reached in its length.
    static func keep(_ finished: FinishedRecording) {
        let audio = directory.appending(path: finished.fileURL.lastPathComponent)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try FileManager.default.moveItem(at: finished.fileURL, to: audio)
        } catch {
            return
        }
        DemoServer.add(DemoServer.Recording(
            id: "demo_\(finished.id.uuidString.lowercased())",
            title: finished.title ?? "Demo recording",
            workspace: finished.workspace,
            createdAt: finished.startedAt,
            audio: audio,
            result: DemoServer.result(words: DemoLiveText.words(upTo: finished.duration))
        ))
    }

    /// The bundled readings (LibriVox recordings, public domain), from `Samples/samples.json`.
    static func samples(now: Date = .now, bundle: Bundle = .main) -> [DemoServer.Recording] {
        struct Sample: Decodable {
            var id: String
            var title: String
            var workspace: String?
            var minutesAgo: Double
            var words: [Word]
        }
        // `["Four", 0.296, 0.616]`: the word, its start and its end in seconds.
        struct Word: Decodable {
            var w: String
            var s: Double
            var e: Double

            init(from decoder: Decoder) throws {
                var c = try decoder.unkeyedContainer()
                w = try c.decode(String.self)
                s = try c.decode(Double.self)
                e = try c.decode(Double.self)
            }
        }
        guard let url = bundle.url(forResource: "samples", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let samples = try? JSONDecoder().decode([Sample].self, from: data) else { return [] }
        return samples.compactMap { s in
            guard let audio = bundle.url(forResource: s.id, withExtension: "opus") else { return nil }
            return DemoServer.Recording(
                id: s.id, title: s.title, workspace: s.workspace,
                createdAt: now.addingTimeInterval(-s.minutesAgo * 60), audio: audio,
                result: DemoServer.result(words: s.words.map { ($0.w, $0.s, $0.e) }))
        }
    }
}
