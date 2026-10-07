// SPDX-License-Identifier: GPL-3.0-or-later
import AkouProtocol
import Foundation

/// The live text of one recording, built from the tokens of every live session and the gaps
/// between them. A token whose text starts with a space starts a new word; any other token continues
/// the word before it. A line ends after sentence punctuation or at a silence of 1.5 s or more
/// between tokens, and a gap always ends the line before it.
public struct LiveTranscript: Sendable, Equatable {
    public struct Line: Sendable, Equatable {
        public var text: String
        /// Seconds into the recording of the line's first and last token.
        public var start: Double
        public var end: Double

        public init(text: String, start: Double, end: Double) {
            self.text = text
            self.start = start
            self.end = end
        }
    }

    public enum Item: Sendable, Equatable {
        case line(Line)
        /// Live text paused between these two points of the recording; the final transcript fills it.
        case gap(from: Double, to: Double)
    }

    /// The silence between two tokens that ends a line, in seconds.
    public static let lineBreakSilence = 1.5

    /// Closed lines and gaps, oldest first.
    public private(set) var items: [Item] = []
    /// The line still growing.
    public private(set) var openLine: Line?
    /// The newest closed line: what the Live Activity shows.
    public private(set) var lastClosedLine: String?

    public init() {}

    public mutating func append(_ words: LiveWords) {
        for token in words.tokens { append(token) }
    }

    public mutating func append(_ token: LiveToken) {
        guard !token.text.isEmpty else { return }
        let startsWord = token.text.first!.isWhitespace
        if let line = openLine, startsWord, closeAtNextWord || token.t - line.end >= Self.lineBreakSilence {
            close()
        }
        if var line = openLine {
            line.text += token.text
            line.end = token.t
            openLine = line
        } else {
            let text = String(token.text.drop { $0.isWhitespace })
            guard !text.isEmpty else { return }
            openLine = Line(text: text, start: token.t, end: token.t)
        }
        // A full stop ends the line only once the next word starts, so "3." then "14" stays one word.
        closeAtNextWord = token.text.last.map { Self.sentenceEnd.contains($0) } ?? false
    }

    /// Marks live text as paused from `from` to `to` (seconds into the recording).
    public mutating func gap(from: Double, to: Double) {
        close()
        items.append(.gap(from: from, to: to))
    }

    /// Ends the open line, for example when the recording stops.
    public mutating func close() {
        closeAtNextWord = false
        guard let line = openLine else { return }
        openLine = nil
        items.append(.line(line))
        lastClosedLine = line.text
    }

    /// What the view says for a gap.
    public static func gapLabel(from: Double, to: Double) -> String {
        "live text paused, \(clock(from)) to \(clock(to)), will come with the final transcript"
    }

    /// `m:ss`, or `h:mm:ss` from one hour.
    public static func clock(_ seconds: Double) -> String {
        let s = max(0, Int(seconds))
        let (h, m, sec) = (s / 3600, s / 60 % 60, s % 60)
        let ss = sec < 10 ? "0\(sec)" : "\(sec)"
        if h > 0 { return "\(h):\(m < 10 ? "0\(m)" : "\(m)"):\(ss)" }
        return "\(m):\(ss)"
    }

    private static let sentenceEnd: Set<Character> = [".", "?", "!", "\u{2026}", "\u{3002}", "\u{FF1F}", "\u{FF01}"]
    private var closeAtNextWord = false
}
