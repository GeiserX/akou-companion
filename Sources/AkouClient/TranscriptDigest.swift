// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The opening of a transcript in a few whole sentences, for the recent-recordings widget, the
/// "last recording" intent's fallback summary, and the snapshot the app keeps for both. Pure: no
/// key, no network, no model.
public enum TranscriptDigest {
    /// One word of an akou job result (`JobWord` in akou): `s` and `e` are seconds into the file,
    /// null from an engine that gives no word times; `c` is the confidence, null when there is none.
    public struct Word: Codable, Sendable, Equatable {
        public var w: String
        public var s: Double?
        public var e: Double?
        public var c: Double?

        public init(w: String, s: Double? = nil, e: Double? = nil, c: Double? = nil) {
            self.w = w
            self.s = s
            self.e = e
            self.c = c
        }
    }

    /// The result's `text` when it has any, else its words joined by spaces, cut by `opening(text:limit:)`.
    public static func opening(text: String?, words: [Word], limit: Int = 600) -> String {
        if let text, !collapse(text).isEmpty {
            return opening(text: text, limit: limit)
        }
        return opening(text: words.map(\.w).joined(separator: " "), limit: limit)
    }

    /// The longest run of whole opening sentences that fits in `limit` characters, whitespace
    /// collapsed. When even the first sentence is longer, it is cut at the last word that fits and
    /// ends in "…". The answer is never longer than `limit`.
    public static func opening(text: String, limit: Int = 600) -> String {
        let flat = collapse(text)
        guard limit > 0, !flat.isEmpty else { return "" }
        if flat.count <= limit { return flat }

        var best: Substring?
        for end in sentenceEnds(in: flat) {
            let candidate = flat[..<end]
            if candidate.count > limit { break }
            best = candidate
        }
        if let best { return String(best) }

        // No whole sentence fits: cut at a word boundary, leaving room for the ellipsis.
        let room = limit - 1
        let head = flat.prefix(room + 1)
        if let space = head.lastIndex(of: " "), flat[..<space].count <= room {
            let words = flat[..<space].trimmingCharacters(in: .whitespaces)
            if !words.isEmpty { return words + "…" }
        }
        return String(flat.prefix(room)) + "…"
    }

    /// Runs of whitespace become one space; the ends are trimmed.
    static func collapse(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private static let latinEnds: Set<Character> = [".", "!", "?", "…"]
    private static let wideEnds: Set<Character> = ["。", "！", "？"]
    private static let closers: Set<Character> = ["\"", "'", "”", "’", ")", "]", "»"]

    /// The index just past each sentence end: a run of `.`, `!`, `?` or `…` (with any closing quote
    /// or bracket) followed by a space or the end, or a full-width `。！？` anywhere.
    static func sentenceEnds(in text: String) -> [String.Index] {
        var ends: [String.Index] = []
        var i = text.startIndex
        while i < text.endIndex {
            let c = text[i]
            let isLatin = latinEnds.contains(c)
            guard isLatin || wideEnds.contains(c) else {
                i = text.index(after: i)
                continue
            }
            var j = text.index(after: i)
            while j < text.endIndex, latinEnds.contains(text[j]) || wideEnds.contains(text[j]) || closers.contains(text[j]) {
                j = text.index(after: j)
            }
            if !isLatin || j == text.endIndex || text[j] == " " {
                ends.append(j)
            }
            i = j
        }
        if ends.last != text.endIndex { ends.append(text.endIndex) }
        return ends
    }
}
