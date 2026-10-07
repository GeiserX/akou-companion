// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation
import Network
import UIKit

/// Sends each finished recording to the server once, through a background `URLSession`, so an
/// upload goes on with the app suspended or the phone locked, and recordings made with no server
/// in reach go up by themselves when it comes back.
///
/// The decisions live in `UploadQueue` (AkouKit, tested there); this class only runs its
/// requests: the upload from a body file on the background session, then the read-back of the
/// job (`GET /v1/jobs/{id}`) that tells the queue whether the server keeps the audio.
@MainActor
final class BackgroundUploader: NSObject {
    static let shared = BackgroundUploader()

    static var sessionIdentifier: String { (Bundle.main.bundleIdentifier ?? "akou-companion") + ".uploads" }

    /// The app's container. A recording is named relative to it, because its absolute path
    /// changes when the app is updated.
    private static let home = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true).resolvingSymlinksInPath()
    private static var support: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0] }
    /// Where the multipart bodies wait while their upload runs.
    private static var bodies: URL { support.appending(path: "UploadBodies", directoryHint: .isDirectory) }

    private var queue: UploadQueue?
    private var session: URLSession?
    private var received: [Int: Data] = [:]
    private var work: [Task<Void, Never>] = []
    private var monitor: NWPathMonitor?
    private var wake: Task<Void, Never>?
    private var pumping = false
    private var pumpAgain = false
    private var backgroundCompletion: (() -> Void)?

    /// Opens the queue and the background session, and sends whatever is due. Safe to call often.
    func start() {
        guard queue == nil else { return }
        do {
            try FileManager.default.createDirectory(at: Self.bodies, withIntermediateDirectories: true)
            queue = try UploadQueue(
                directory: Self.support.appending(path: "Uploads", directoryHint: .isDirectory),
                audioDirectory: Self.home,
                keepLocalCopy: { UserDefaults.standard.bool(forKey: ServerSettings.keepLocalCopy) }
            )
        } catch {
            // The queue file is unreadable; nothing can be sent until it is, and nothing is lost.
            return
        }
        let config = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in await BackgroundUploader.shared.pump() }
        }
        monitor.start(queue: DispatchQueue(label: "akou-companion.network"))
        self.monitor = monitor
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in await BackgroundUploader.shared.pump() }
        }
        Task { await reconcile() }
    }

    /// Queues a finished recording, under the workspace and language it was recorded with. `file`
    /// must be inside the app's container.
    func enqueue(recordingID: String, file: URL, title: String?, workspace: String?, language: String) async {
        start()
        guard let queue else { return }
        let path = file.resolvingSymlinksInPath().path
        let prefix = Self.home.path.hasSuffix("/") ? Self.home.path : Self.home.path + "/"
        let relative = path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : file.lastPathComponent
        let submission = JobsClient.Submission(
            recordingID: recordingID,
            title: title,
            language: language,
            workspace: workspace
        )
        _ = try? await queue.enqueue(submission, fileName: relative)
        await pump()
    }

    /// Every recording in the queue, in the order they were made, for the Library's "On this
    /// iPhone" section.
    func items() async -> [UploadQueue.Item] {
        start()
        return await queue?.items ?? []
    }

    /// The phone's own copy of a queued recording; nil when the queue is not open.
    func localAudio(_ item: UploadQueue.Item) -> URL? {
        queue?.audioURL(item)
    }

    /// The key or the URL changed: recordings parked on a refusal get another try.
    func settingsChanged() async {
        start()
        try? await queue?.retryParked()
        await pump()
    }

    /// `application(_:handleEventsForBackgroundURLSession:completionHandler:)`: the system woke
    /// the app for finished transfers. `completion` runs once every answer is handled.
    func handleEvents(completion: @escaping () -> Void) {
        backgroundCompletion = completion
        start()
    }

    /// After a launch: an item left `uploading` whose transfer the system no longer runs goes back
    /// to `pending`, and bodies no transfer needs are deleted.
    private func reconcile() async {
        guard let queue, let session else { return }
        let live = Set(await session.allTasks.compactMap(\.taskDescription))
        try? await queue.requeueInterrupted(except: live)
        let files = (try? FileManager.default.contentsOfDirectory(at: Self.bodies, includingPropertiesForKeys: nil)) ?? []
        for f in files where !live.contains(f.deletingPathExtension().lastPathComponent) {
            try? FileManager.default.removeItem(at: f)
        }
        await pump()
    }

    /// Starts every due upload and reads back every submitted job.
    func pump() async {
        if pumping {
            pumpAgain = true
            return
        }
        pumping = true
        defer { pumping = false }
        repeat {
            pumpAgain = false
            await pumpOnce()
        } while pumpAgain
        await scheduleWake()
    }

    private func pumpOnce() async {
        guard let queue, let session, let url = ServerSettings.url, let key = KeyStore.load(),
              (try? Endpoint.api(url, "/v1/jobs")) != nil else { return }
        let client = JobsClient(baseURL: url, key: key)
        for item in (try? await queue.claimDue()) ?? [] {
            let body = Self.bodies.appending(path: "\(item.recordingID).multipart")
            do {
                let request = try client.submitRequest(item.submission, audio: queue.audioURL(item), bodyFile: body)
                let task = session.uploadTask(with: request, fromFile: body)
                task.taskDescription = item.recordingID
                task.resume()
            } catch {
                try? FileManager.default.removeItem(at: body)
                try? await queue.uploadCouldNotStart(item.recordingID, "\(error)")
            }
        }
        // The read-back needs no wait: `keep_audio` is set when the job is made, and a system
        // wake gives the app about 30 seconds, less than `wait=60` would hold the request.
        for (recordingID, jobID) in await queue.dueForConfirm() {
            guard let request = try? client.jobRequest(jobID) else { continue }
            let answer = await URLSessionTransport().send(request)
            try? await queue.confirmEnded(recordingID, answer)
        }
    }

    /// Wakes the queue when the earliest backoff or `Retry-After` ends, while the app runs.
    private func scheduleWake() async {
        wake?.cancel()
        guard let at = await queue?.nextWake() else { return }
        let delay = max(1, at.timeIntervalSinceNow)
        wake = Task {
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            await self.pump()
        }
    }

    private func uploadEnded(_ recordingID: String, _ answer: UploadQueue.Answer) {
        let t = Task {
            try? await queue?.uploadEnded(recordingID, answer)
            try? FileManager.default.removeItem(at: Self.bodies.appending(path: "\(recordingID).multipart"))
            await pump()
        }
        work.append(t)
    }

    private func finishedEvents() {
        let pending = work
        work = []
        Task {
            for t in pending { await t.value }
            let completion = backgroundCompletion
            backgroundCompletion = nil
            completion?()
        }
    }
}

extension BackgroundUploader: URLSessionDataDelegate {
    nonisolated func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let id = dataTask.taskIdentifier
        MainActor.assumeIsolated { received[id, default: Data()].append(data) }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        let id = task.taskIdentifier
        let recordingID = task.taskDescription
        let http = task.response as? HTTPURLResponse
        let status = http?.statusCode
        let headers = http.map(URLSessionTransport.headers) ?? [:]
        let failure = error?.localizedDescription
        MainActor.assumeIsolated {
            let body = received.removeValue(forKey: id) ?? Data()
            guard let recordingID else { return }
            if let status, failure == nil {
                uploadEnded(recordingID, .http(status: status, headers: headers, body: body))
            } else {
                uploadEnded(recordingID, .unreachable(failure ?? "no answer"))
            }
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        MainActor.assumeIsolated { finishedEvents() }
    }
}
