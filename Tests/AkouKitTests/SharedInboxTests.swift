// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import Foundation
import XCTest

/// Audio shared from another app: the extension's copy into the App Group, the app's import into
/// the upload queue, and the upload it ends in.
final class SharedInboxTests: XCTestCase {
    var dir: URL!
    var inbox: URL!
    var home: URL!
    let folder = "Library/Application Support/Recordings"
    let server = UUID()

    override func setUpWithError() throws {
        FakeAkouHTTP.reset()
        dir = FileManager.default.temporaryDirectory.appending(path: "inbox-\(UUID().uuidString)")
        inbox = SharedInbox.directory(in: dir.appending(path: "group"))
        home = dir.appending(path: "home")
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    func makeQueue() throws -> UploadQueue {
        try UploadQueue(directory: dir.appending(path: "state"), audioDirectory: home, keepLocalCopy: { false })
    }

    /// A file as the system hands it to the share extension.
    func shared(_ name: String, _ bytes: String = "audio bytes") throws -> URL {
        let src = dir.appending(path: "src-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: src, withIntermediateDirectories: true)
        let file = src.appending(path: name)
        try Data(bytes.utf8).write(to: file)
        return file
    }

    func testTheExtensionCopiesTheFileAndWritesItsSidecar() throws {
        let id = UUID()
        let s = try SharedInbox.add(file: try shared("New Recording 3.m4a"), typeIdentifier: "com.apple.m4a-audio", suggestedName: nil, to: inbox, id: id)
        XCTAssertEqual(s.id, id.uuidString)
        XCTAssertEqual(s.title, "New Recording 3")
        XCTAssertEqual(s.fileExtension, "m4a")
        XCTAssertEqual(s.contentType, "audio/mp4")
        XCTAssertNil(s.serverID)
        XCTAssertEqual(try Data(contentsOf: inbox.appending(path: "\(id.uuidString).m4a")), Data("audio bytes".utf8))
        XCTAssertTrue(FileManager.default.fileExists(atPath: inbox.appending(path: "\(id.uuidString).json").path))
    }

    func testTheSuggestedNameAndTheTypeFillInWhatTheFileNameLacks() throws {
        // A WhatsApp voice note as a provider can hand it over: a temporary name, the real one suggested.
        let a = try SharedInbox.add(file: try shared("tmp123"), typeIdentifier: "org.xiph.opus", suggestedName: "PTT-20261023-WA0001.opus", to: inbox)
        XCTAssertEqual(a.title, "PTT-20261023-WA0001")
        XCTAssertEqual(a.fileExtension, "opus")
        XCTAssertEqual(a.contentType, "audio/ogg")
        // No extension anywhere but the type's.
        let b = try SharedInbox.add(file: try shared("blob"), typeIdentifier: "public.mp3", suggestedName: nil, to: inbox)
        XCTAssertEqual(b.fileExtension, "mp3")
        XCTAssertEqual(b.contentType, "audio/mpeg")
        // A name that would collide with a sidecar never becomes the extension.
        XCTAssertEqual(SharedInbox.fileExtension(["json", "../x", "WAV"]), "wav")
        XCTAssertEqual(SharedInbox.fileExtension(["", "json"]), "audio")
        // A title the server would refuse with a 422 is made one it takes.
        let long = try SharedInbox.add(file: try shared(String(repeating: "é", count: 150) + ".m4a"), typeIdentifier: "com.apple.m4a-audio", suggestedName: String(repeating: "😀", count: 150) + ".m4a", to: inbox)
        XCTAssertEqual(long.title, String(repeating: "😀", count: 100))
        XCTAssertEqual(SharedInbox.title("a\u{1B}[31m\tb\u{0}  c\n"), "a [31m b c")
        XCTAssertEqual(SharedInbox.title("\u{7}\n"), "")
    }

    func testAFileLeftWithoutItsSidecarIsSweptOnceItIsOld() async throws {
        let queue = try makeQueue()
        let fm = FileManager.default
        try fm.createDirectory(at: inbox, withIntermediateDirectories: true)
        let now = Date()
        let old = inbox.appending(path: "\(UUID().uuidString).m4a")
        let fresh = inbox.appending(path: "\(UUID().uuidString).m4a")
        try Data("a".utf8).write(to: old)
        try Data("b".utf8).write(to: fresh)
        try fm.setAttributes([.modificationDate: now.addingTimeInterval(-SharedInbox.orphanAge - 60)], ofItemAtPath: old.path)
        // A file whose sidecar is there but cannot be queued yet stays, however old.
        let kept = try SharedInbox.add(file: try shared("kept.m4a"), typeIdentifier: "com.apple.m4a-audio", suggestedName: nil, to: inbox)
        let keptFile = inbox.appending(path: kept.fileName)
        try fm.setAttributes([.modificationDate: now.addingTimeInterval(-SharedInbox.orphanAge - 60)], ofItemAtPath: keptFile.path)
        SharedInbox.sweepOrphans(in: inbox, now: now)
        XCTAssertTrue(fm.fileExists(atPath: keptFile.path))

        try await SharedInbox.importPending(from: inbox, into: queue, folder: folder, defaultServer: server, now: now)
        XCTAssertFalse(fm.fileExists(atPath: old.path))
        XCTAssertTrue(fm.fileExists(atPath: fresh.path))
    }

    func testOnlyAudioIsTaken() {
        XCTAssertEqual(SharedInbox.audioType(of: ["public.jpeg", "com.apple.m4a-audio"]), "com.apple.m4a-audio")
        XCTAssertEqual(SharedInbox.audioType(of: ["com.microsoft.waveform-audio"]), "com.microsoft.waveform-audio")
        XCTAssertEqual(SharedInbox.audioType(of: ["org.xiph.opus"]), "org.xiph.opus")
        XCTAssertNil(SharedInbox.audioType(of: ["public.jpeg", "public.plain-text", "com.adobe.pdf"]))
        XCTAssertEqual(SharedInbox.contentType(fileExtension: "caf"), "audio/x-caf")
        XCTAssertEqual(SharedInbox.contentType(fileExtension: "wav"), "audio/wav")
    }

    func testTheAppQueuesASharedFileOnceWithItsOwnNameAndType() async throws {
        let queue = try makeQueue()
        let s = try SharedInbox.add(file: try shared("memo.m4a"), typeIdentifier: "com.apple.m4a-audio", suggestedName: nil, to: inbox)

        let first = try await SharedInbox.importPending(from: inbox, into: queue, folder: folder, defaultServer: server)
        XCTAssertEqual(first.map(\.recordingID), [s.id])
        let item = try XCTUnwrap(first.first)
        XCTAssertEqual(item.state, .pending)
        XCTAssertEqual(item.serverID, server)
        XCTAssertEqual(item.fileName, "\(folder)/\(s.id).m4a")
        XCTAssertEqual(item.submission.title, "memo")
        XCTAssertEqual(item.submission.uploadFileName, "\(s.id).m4a")
        XCTAssertEqual(item.submission.uploadContentType, "audio/mp4")
        // The file moved out of the App Group into the app's container.
        XCTAssertEqual(try Data(contentsOf: queue.audioURL(item)), Data("audio bytes".utf8))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: inbox.path), [])

        // A second pass, as at the next activation, queues nothing again.
        let second = try await SharedInbox.importPending(from: inbox, into: queue, folder: folder, defaultServer: server)
        XCTAssertEqual(second, [])
        let items = await queue.items
        XCTAssertEqual(items.map(\.recordingID), [s.id])

        // And the queue keeps the name and type across a restart of the app.
        let reopened = try makeQueue()
        let again = await reopened.item(s.id)
        XCTAssertEqual(again?.submission.uploadFileName, "\(s.id).m4a")
        XCTAssertEqual(again?.submission.uploadContentType, "audio/mp4")
    }

    func testAPassCutShortAfterTheMoveIsFinishedByTheNext() async throws {
        let queue = try makeQueue()
        let s = try SharedInbox.add(file: try shared("note.wav"), typeIdentifier: "com.microsoft.waveform-audio", suggestedName: nil, to: inbox)
        // The file reached the app's container, then the app was killed before it was queued.
        let dest = home.appending(path: folder)
        try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        try FileManager.default.moveItem(at: inbox.appending(path: s.fileName), to: dest.appending(path: s.fileName))

        let queued = try await SharedInbox.importPending(from: inbox, into: queue, folder: folder, defaultServer: server)
        XCTAssertEqual(queued.map(\.recordingID), [s.id])
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: inbox.path), [])
    }

    func testTheSidecarsServerWinsOverRecordTo() async throws {
        let queue = try makeQueue()
        let chosen = UUID()
        try SharedInbox.add(file: try shared("a.mp3"), typeIdentifier: "public.mp3", suggestedName: nil, serverID: chosen, to: inbox)
        let queued = try await SharedInbox.importPending(from: inbox, into: queue, folder: folder, defaultServer: server)
        XCTAssertEqual(queued.first?.serverID, chosen)
    }

    func testAnImportedFileUploadsWithItsNameTypeAndIdempotencyKey() async throws {
        let queue = try makeQueue()
        let s = try SharedInbox.add(file: try shared("memo.m4a", "ftypM4A"), typeIdentifier: "com.apple.m4a-audio", suggestedName: nil, to: inbox)
        try await SharedInbox.importPending(from: inbox, into: queue, folder: folder, defaultServer: nil)

        let client = JobsClient(baseURL: URL(string: "https://akou.example.com")!, key: FakeAkouHTTP.key)
        try await queue.drain(client: client, transport: URLSessionTransport(session: FakeAkouHTTP.session()), scratch: dir.appending(path: "scratch"))

        let req = try XCTUnwrap(FakeAkouHTTP.seen.first { $0.method == "POST" })
        XCTAssertEqual(req.headers["idempotency-key"], s.id)
        XCTAssertEqual(req.fileName, "\(s.id).m4a")
        XCTAssertEqual(req.fileContentType, "audio/mp4")
        XCTAssertEqual(req.fields["title"], "memo")
        XCTAssertEqual(req.fileBytes, Data("ftypM4A".utf8))
    }
}
