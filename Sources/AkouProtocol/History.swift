// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

// What akou's history routes answer, as far as the phone reads them: the calls, the dictations and
// their audio, reachable by a key with the `read` or `admin` scope on the desktop app (calls and
// dictations exist only in akou's app mode). Every other field is ignored, so a newer akou never
// breaks an older phone. Times akou writes as numbers are epoch milliseconds.

/// One row of `GET /v1/calls` (akou `src/main/call/recovery.ts` `CallSummary`, without `dir`).
public struct CallSummary: Decodable, Sendable, Equatable, Identifiable {
    public var id: String
    public var title: String
    public var workspace: String
    /// Epoch ms of `call.created`.
    public var createdAt: Double
    /// `recording`, `paused`, `ended`, `interrupted`, `failed`…; kept as text so a new state never fails decoding.
    public var state: String
    /// Epoch ms; null while the call is live.
    public var endedAt: Double?
    /// How many parts (audio files) the call has.
    public var parts: Int
    public var updatedAt: Double?

    enum CodingKeys: String, CodingKey { case id, title, workspace, createdAt, state, endedAt, parts, updatedAt }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        workspace = try c.decodeIfPresent(String.self, forKey: .workspace) ?? ""
        createdAt = try c.decodeIfPresent(Double.self, forKey: .createdAt) ?? 0
        state = try c.decodeIfPresent(String.self, forKey: .state) ?? ""
        endedAt = try c.decodeIfPresent(Double.self, forKey: .endedAt)
        parts = try c.decodeIfPresent(Int.self, forKey: .parts) ?? 0
        updatedAt = try c.decodeIfPresent(Double.self, forKey: .updatedAt)
    }

    /// True while akou is still recording it.
    public var isLive: Bool { endedAt == nil && (state == "recording" || state == "paused" || state == "starting" || state == "restarting") }
}

/// `GET /v1/calls`: newest first.
public struct CallList: Decodable, Sendable, Equatable {
    public var calls: [CallSummary]
}

/// `GET /v1/calls/{id}` (akou `src/main/api/routes/calls.ts` `callDetail`).
public struct CallDetail: Decodable, Sendable, Equatable {
    public var id: String
    public var title: String
    public var workspace: String
    public var state: String
    public var live: Bool
    public var tz: String?
    public var createdAt: Double?
    public var startedAt: Double?
    public var endedAt: Double?
    public var parts: [Part]
    public var final: Final?

    public struct Part: Decodable, Sendable, Equatable {
        /// From 1; `GET /v1/calls/{id}/audio/{part}` serves its audio.
        public var part: Int
        /// Epoch ms the part's audio starts at: a transcript line at `w0` is `(w0 - wallStart) / 1000`
        /// seconds into this part's file.
        public var wallStart: Double?
        public var ended: Ended?

        public struct Ended: Decodable, Sendable, Equatable {
            public var reason: String?
            public var fileSeconds: Double?
        }
    }

    public struct Final: Decodable, Sendable, Equatable {
        /// The final pass: `none`, `queued`, `running`, `done`, `failed`…
        public var state: String?
    }

    enum CodingKeys: String, CodingKey { case id, title, workspace, state, live, tz, createdAt, startedAt, endedAt, parts, final }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        workspace = try c.decodeIfPresent(String.self, forKey: .workspace) ?? ""
        state = try c.decodeIfPresent(String.self, forKey: .state) ?? ""
        live = try c.decodeIfPresent(Bool.self, forKey: .live) ?? false
        tz = try c.decodeIfPresent(String.self, forKey: .tz)
        createdAt = try c.decodeIfPresent(Double.self, forKey: .createdAt)
        startedAt = try c.decodeIfPresent(Double.self, forKey: .startedAt)
        endedAt = try c.decodeIfPresent(Double.self, forKey: .endedAt)
        parts = try c.decodeIfPresent([Part].self, forKey: .parts) ?? []
        final = try? c.decodeIfPresent(Final.self, forKey: .final)
    }

    /// Seconds of audio across the parts that ended; nil while none says.
    public var seconds: Double? {
        let known = parts.compactMap { $0.ended?.fileSeconds }
        return known.isEmpty ? nil : known.reduce(0, +)
    }
}

/// `GET /v1/calls/{id}/transcript?format=json` (akou `src/main/api/routes/follow.ts`).
public struct CallTranscript: Decodable, Sendable, Equatable {
    public var call: String
    public var tz: String?
    /// "Times are local, Europe/Madrid (GMT+2)."
    public var zone: String?
    public var lines: [CallLine]
}

/// One transcript line: who said what, when.
public struct CallLine: Decodable, Sendable, Equatable, Identifiable {
    public var id: String
    /// The line's local wall-clock time in the call's zone, `15:41:03`.
    public var time: String
    /// Epoch ms the line starts and ends.
    public var w0: Double
    public var w1: Double?
    public var part: Int
    /// `mic` or `call`.
    public var ch: String?
    /// The speaker's id (`you`, `c2`) and the name akou shows for it.
    public var spk: String?
    public var speaker: String
    public var text: String

    enum CodingKeys: String, CodingKey { case id, time, w0, w1, part, ch, spk, speaker, text }

    /// One odd line (a field missing or null) never costs the whole transcript.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        w0 = (try? c.decodeIfPresent(Double.self, forKey: .w0)) ?? 0
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? "w\(Int(w0))"
        time = (try? c.decodeIfPresent(String.self, forKey: .time)) ?? ""
        w1 = try? c.decodeIfPresent(Double.self, forKey: .w1)
        part = (try? c.decodeIfPresent(Int.self, forKey: .part)) ?? 1
        ch = try? c.decodeIfPresent(String.self, forKey: .ch)
        spk = try? c.decodeIfPresent(String.self, forKey: .spk)
        speaker = (try? c.decodeIfPresent(String.self, forKey: .speaker)) ?? spk ?? ""
        text = (try? c.decodeIfPresent(String.self, forKey: .text)) ?? ""
    }
}

/// `GET /v1/calls/{id}/enhanced` (akou `src/main/api/routes/post-call.ts`): the summary, when there is one.
public struct CallEnhanced: Decodable, Sendable, Equatable {
    public var call: String
    /// Null when the call has no enhanced notes yet.
    public var enhanced: Notes?

    public struct Notes: Decodable, Sendable, Equatable {
        public var rev: Int
        public var template: String?
        public var markdown: String
    }
}

/// `GET /v1/calls/{id}/notes` (akou `src/main/api/routes/notes.ts`, `NoteView` in `src/core/log/fold.ts`).
public struct CallNotes: Decodable, Sendable, Equatable {
    public var call: String
    public var notes: [CallNote]
}

public struct CallNote: Decodable, Sendable, Equatable, Identifiable {
    public var id: String
    public var text: String
    /// Epoch ms the line was begun.
    public var w: Double
    /// `human` or `agent`.
    public var author: String?

    enum CodingKeys: String, CodingKey { case id, text, w, author }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        w = (try? c.decodeIfPresent(Double.self, forKey: .w)) ?? 0
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? "n\(Int(w))"
        text = (try? c.decodeIfPresent(String.self, forKey: .text)) ?? ""
        author = try? c.decodeIfPresent(String.self, forKey: .author)
    }
}

/// One dictation (akou `src/main/api/routes/dictation.ts` `dictationBody`).
public struct Dictation: Decodable, Sendable, Equatable, Identifiable {
    public var id: String
    /// Epoch ms the dictation started.
    public var at: Double
    /// `inserted`, `drafted`, `done`, `empty`, `failed`…
    public var state: String
    /// The app it went to, by the name people know it, when the OS gave one.
    public var appName: String?
    public var seconds: Double?
    /// What was inserted (after the tidy, if any) and what the engine heard.
    public var text: String?
    public var raw: String?
    public var language: String?
    public var words: [JobWord]
    public var engine: String?
    public var model: String?
    public var error: String?

    enum CodingKeys: String, CodingKey {
        case id, at, state, seconds, text, raw, language, words, engine, model, error
        case appName = "app_name"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        at = try c.decodeIfPresent(Double.self, forKey: .at) ?? 0
        state = try c.decodeIfPresent(String.self, forKey: .state) ?? ""
        appName = try c.decodeIfPresent(String.self, forKey: .appName)
        seconds = try c.decodeIfPresent(Double.self, forKey: .seconds)
        text = try c.decodeIfPresent(String.self, forKey: .text)
        raw = try c.decodeIfPresent(String.self, forKey: .raw)
        language = try c.decodeIfPresent(String.self, forKey: .language)
        words = (try? c.decodeIfPresent([JobWord].self, forKey: .words)) ?? []
        engine = try c.decodeIfPresent(String.self, forKey: .engine)
        model = try c.decodeIfPresent(String.self, forKey: .model)
        error = try? c.decodeIfPresent(String.self, forKey: .error)
    }
}

/// `GET /v1/dictations`: newest first; `next_cursor` is null on the last page.
public struct DictationPage: Decodable, Sendable, Equatable {
    public var items: [Dictation]
    public var nextCursor: String?

    enum CodingKeys: String, CodingKey {
        case items
        case nextCursor = "next_cursor"
    }
}

extension KeyInfo {
    /// The key may upload recordings: `jobs` or `admin`.
    public var canUpload: Bool { scopes.contains("jobs") || scopes.contains("admin") }
    /// The key may read the server's history (every call, dictation and job): `read` or `admin`.
    public var seesHistory: Bool { scopes.contains("read") || scopes.contains("admin") }
}
