// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// Live text for the demo: a fixed script, a word at a time, as the recording reaches each word's
/// time. It opens no socket and never looks at the audio; pausing the recording pauses the words,
/// because only the pages' end times move it on.
public actor DemoLiveText: LiveText {
    /// What the demo "hears", whatever is said.
    public static let script = """
        This is the akou demo. On your own akou server, the words you say appear here while you \
        speak. When you stop, the recording goes to the server, and the full transcript comes back \
        with every word timed, so a tap on a word plays from there. In this demo nothing leaves your \
        iPhone, and these words are always the same.
        """

    /// Seconds per word, a calm speaking pace.
    public static let wordSeconds = 0.4

    /// The script's words with their times in the recording: the first starts at 0.5 s.
    public static let words: [(w: String, s: Double, e: Double)] = script.split(separator: " ").enumerated().map { i, w in
        let s = 0.5 + Double(i) * wordSeconds
        return (String(w), s, s + wordSeconds * 0.8)
    }

    /// The script's words that end within `seconds` of audio: the demo's transcript of a recording
    /// that long.
    public static func words(upTo seconds: Double) -> [(w: String, s: Double, e: Double)] {
        words.filter { $0.e <= seconds }
    }

    public nonisolated let events: AsyncStream<LiveSession.Event>
    private let continuation: AsyncStream<LiveSession.Event>.Continuation
    private var next = 0
    private var started = false
    private var stopped = false

    public init() {
        (events, continuation) = AsyncStream.makeStream(of: LiveSession.Event.self, bufferingPolicy: .unbounded)
    }

    public func start() {
        guard !started, !stopped else { return }
        started = true
        continuation.yield(.state(.live(engine: DemoServer.engine)))
    }

    /// Sends every word that has started by `end`, as the tokens a live engine would.
    public func send(page: Data, endsAt end: Double) {
        guard started, !stopped else { return }
        var tokens: [LiveToken] = []
        while next < Self.words.count, Self.words[next].s <= end {
            tokens.append(LiveToken(text: " " + Self.words[next].w, t: Self.words[next].s))
            next += 1
        }
        if !tokens.isEmpty { continuation.yield(.words(LiveWords(tokens: tokens))) }
    }

    /// Ends live text; `events` has finished when it returns, as `LiveSession.stop()` promises.
    public func stop() {
        guard !stopped else { return }
        stopped = true
        if started { continuation.yield(.state(.off(reason: "stopped"))) }
        continuation.finish()
    }
}
