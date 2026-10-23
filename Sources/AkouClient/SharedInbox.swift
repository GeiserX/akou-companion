// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import UniformTypeIdentifiers

/// Audio another app shared to akou through the share sheet (a voice memo, a file, a WhatsApp
/// voice note), waiting in the App Group container for the app to queue it.
///
/// The share extension holds no key and makes no request: it copies the file into the inbox as
/// `<id>.<ext>` and then writes `<id>.json`, the sidecar, so a sidecar always has its whole file.
/// The app, at launch and each time it becomes active, moves each file into its own container and
/// queues it as a recording, with the file's own name and content type on the upload. The id is
/// the recording id, so the `Idempotency-Key` too.
public enum SharedInbox {
    public struct Sidecar: Codable, Sendable, Equatable {
        /// The recording id, a UUID.
        public var id: String
        /// The name the other app gave the file, without the extension: the recording's title.
        public var title: String
        /// The type the file was shared as.
        public var typeIdentifier: String
        /// The extension of the file in the inbox and of the upload's file name, lower case.
        public var fileExtension: String
        public var contentType: String
        /// The server to send it to; nil sends it to "Record to".
        public var serverID: UUID?
        public var sharedAt: Date

        public var fileName: String { "\(id).\(fileExtension)" }
    }

    /// The inbox inside the App Group container.
    public static func directory(in container: URL) -> URL {
        container.appending(path: "SharedAudio", directoryHint: .isDirectory)
    }

    /// The types the share extension takes. Its `NSExtensionActivationRule` names the same ones.
    public static let acceptedTypes = [
        "public.audio",
        "public.mpeg-4-audio",
        "com.apple.m4a-audio",
        "public.mp3",
        "com.microsoft.waveform-audio",
        "com.apple.coreaudio-format",
        "org.xiph.ogg-audio",
        "org.xiph.opus",
    ]

    /// The first of `identifiers` (an attachment's registered types) that is audio akou takes.
    public static func audioType(of identifiers: [String]) -> String? {
        let accepted = acceptedTypes.compactMap(UTType.init)
        return identifiers.first { id in
            if acceptedTypes.contains(id) { return true }
            guard let type = UTType(id) else { return false }
            return accepted.contains { type.conforms(to: $0) }
        }
    }

    /// The content type of an upload with this extension. A few by hand, because the server and
    /// its ffmpeg only care that it is audio, and the system's table differs between versions.
    public static func contentType(fileExtension ext: String) -> String {
        switch ext {
        case "opus", "ogg", "oga": "audio/ogg"
        case "m4a", "mp4", "m4b": "audio/mp4"
        case "aac": "audio/aac"
        case "mp3": "audio/mpeg"
        case "wav": "audio/wav"
        case "caf": "audio/x-caf"
        case "aif", "aiff": "audio/aiff"
        case "flac": "audio/flac"
        case "webm": "audio/webm"
        default: UTType(filenameExtension: ext)?.preferredMIMEType ?? "application/octet-stream"
        }
    }

    /// Copies `file` (shared as `typeIdentifier`, named `suggestedName` by the other app) into
    /// `inbox`, then writes its sidecar. Called by the share extension.
    @discardableResult
    public static func add(
        file: URL, typeIdentifier: String, suggestedName: String?, serverID: UUID? = nil,
        to inbox: URL, id: UUID = UUID(), now: Date = Date()
    ) throws -> Sidecar {
        let fm = FileManager.default
        try fm.createDirectory(at: inbox, withIntermediateDirectories: true)
        let named = suggestedName.flatMap { $0.isEmpty ? nil : $0 } ?? file.lastPathComponent
        let ext = Self.fileExtension(
            [file.pathExtension, (named as NSString).pathExtension, UTType(typeIdentifier)?.preferredFilenameExtension ?? ""]
        )
        var title = Self.title((named as NSString).deletingPathExtension)
        if title.isEmpty { title = "Shared audio" }
        let sidecar = Sidecar(
            id: id.uuidString, title: title, typeIdentifier: typeIdentifier, fileExtension: ext,
            contentType: contentType(fileExtension: ext), serverID: serverID, sharedAt: now
        )
        let dest = inbox.appending(path: sidecar.fileName)
        try? fm.removeItem(at: dest)
        try fm.copyItem(at: file, to: dest)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(sidecar).write(to: inbox.appending(path: "\(sidecar.id).json"), options: .atomic)
        return sidecar
    }

    /// Moves every shared file in `inbox` into `folder` (a path relative to the queue's audio
    /// directory) and queues it, for its sidecar's server or else `defaultServer`. A sidecar is
    /// deleted only once its recording is in the queue, so a pass cut short is finished by the
    /// next one, and a second pass over the same files queues nothing twice. A file with no
    /// sidecar older than `orphanAge` (an extension killed between its copy and its sidecar) is
    /// deleted, since nothing would ever send it. Returns what it queued.
    @discardableResult
    public static func importPending(
        from inbox: URL, into queue: UploadQueue, folder: String, defaultServer: UUID?, now: Date = Date()
    ) async throws -> [UploadQueue.Item] {
        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil)) ?? []
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let sidecars = files.filter { $0.pathExtension == "json" }.compactMap { url -> (URL, Sidecar)? in
            guard let data = try? Data(contentsOf: url), let s = try? decoder.decode(Sidecar.self, from: data),
                  UUID(uuidString: s.id) != nil, s.fileExtension == Self.fileExtension([s.fileExtension]) else { return nil }
            return (url, s)
        }.sorted { $0.1.sharedAt < $1.1.sharedAt }

        let destDir = queue.audioDirectory.appending(path: folder, directoryHint: .isDirectory)
        try fm.createDirectory(at: destDir, withIntermediateDirectories: true)
        var queued: [UploadQueue.Item] = []
        for (sidecarURL, s) in sidecars {
            let source = inbox.appending(path: s.fileName)
            let dest = destDir.appending(path: s.fileName)
            if fm.fileExists(atPath: source.path) {
                try? fm.removeItem(at: dest)
                do { try fm.moveItem(at: source, to: dest) } catch { continue }
            } else if !fm.fileExists(atPath: dest.path) {
                // The file is gone from both places: nothing is left to send.
                try? fm.removeItem(at: sidecarURL)
                continue
            }
            let submission = JobsClient.Submission(
                recordingID: s.id, title: s.title, audioFileName: s.fileName, audioContentType: s.contentType
            )
            let relative = folder.isEmpty ? s.fileName : "\(folder)/\(s.fileName)"
            guard let item = try? await queue.enqueue(submission, fileName: relative, serverID: s.serverID ?? defaultServer) else { continue }
            try? fm.removeItem(at: sidecarURL)
            queued.append(item)
        }
        sweepOrphans(in: inbox, now: now)
        return queued
    }

    /// How long a file may wait in the inbox without its sidecar. The extension writes the
    /// sidecar a moment after the copy, so a file this old without one never gets it.
    static let orphanAge: TimeInterval = 10 * 60

    /// Deletes every file in `inbox` that is not a sidecar, has no `<stem>.json` beside it and
    /// was last written more than `orphanAge` before `now`.
    static func sweepOrphans(in inbox: URL, now: Date) {
        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(at: inbox, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for url in files where url.pathExtension != "json" {
            let sidecar = url.deletingPathExtension().appendingPathExtension("json")
            guard !fm.fileExists(atPath: sidecar.path),
                  let written = try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
                  now.timeIntervalSince(written) > orphanAge else { continue }
            try? fm.removeItem(at: url)
        }
    }

    /// The longest title the server takes, in UTF-16 code units as it counts them.
    static let maxTitleLength = 200

    /// `raw` as a title the server accepts: control characters become spaces, runs of white space
    /// one space, trimmed, and cut to `maxTitleLength` without splitting a character. The server
    /// answers any other title with a 422 that a retry only repeats.
    static func title(_ raw: String) -> String {
        let scalars = raw.unicodeScalars.map { $0.properties.generalCategory == .control ? " " : Character($0) }
        let words = String(scalars).split(whereSeparator: { $0.isWhitespace })
        var title = ""
        for c in words.joined(separator: " ") {
            if title.utf16.count + c.utf16.count > maxTitleLength { break }
            title.append(c)
        }
        return title.trimmingCharacters(in: .whitespaces)
    }

    /// The first usable extension of `candidates`, lower case: letters and digits only, at most
    /// eight, and never `json`, which names the sidecars. `audio` when none is.
    static func fileExtension(_ candidates: [String]) -> String {
        for c in candidates {
            let ext = c.lowercased()
            if !ext.isEmpty, ext.count <= 8, ext != "json", ext.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.contains($0) && $0.isASCII }) {
                return ext
            }
        }
        return "audio"
    }
}
