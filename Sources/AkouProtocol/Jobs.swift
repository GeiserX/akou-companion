// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A job as akou's job routes answer it (`POST /v1/jobs`, `GET /v1/jobs/{id}`, each row of
/// `GET /v1/jobs`). Only the fields the phone reads; every other field is ignored, so a newer
/// server never breaks an older app.
public struct Job: Decodable, Sendable, Equatable {
    public var id: String
    public var title: String?
    /// `queued`, `running`, `done`, `failed` or `cancelled`; kept as text so a new state never fails decoding.
    public var status: String
    public var createdAt: String?
    public var finishedAt: String?
    public var preset: String?
    public var model: String?
    public var language: String?
    /// Whether the server keeps the uploaded audio until the job is deleted. A server that does
    /// not send it is read as `false`, so the phone never deletes its copy on a guess.
    public var keepAudio: Bool
    /// The companion's own labels, when the job carries them; nil for a job another client sent.
    public var metadata: CompanionMetadata?
    public var error: String?

    /// True once the job will not change any more.
    public var ended: Bool { status == "done" || status == "failed" || status == "cancelled" }

    enum CodingKeys: String, CodingKey {
        case id, title, status, preset, model, language, metadata, error
        case createdAt = "created_at"
        case finishedAt = "finished_at"
        case keepAudio = "keep_audio"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title)
        status = try c.decode(String.self, forKey: .status)
        createdAt = try c.decodeIfPresent(String.self, forKey: .createdAt)
        finishedAt = try c.decodeIfPresent(String.self, forKey: .finishedAt)
        preset = try c.decodeIfPresent(String.self, forKey: .preset)
        model = try c.decodeIfPresent(String.self, forKey: .model)
        language = try c.decodeIfPresent(String.self, forKey: .language)
        keepAudio = try c.decodeIfPresent(Bool.self, forKey: .keepAudio) ?? false
        // Another client's metadata can be any JSON: it is simply not ours.
        metadata = try? c.decodeIfPresent(CompanionMetadata.self, forKey: .metadata)
        error = try? c.decodeIfPresent(String.self, forKey: .error)
    }
}

/// The `metadata` the phone sends with each upload. Workspaces are a desktop-only route, so on a
/// server the workspace is a label the phone keeps here.
public struct CompanionMetadata: Codable, Sendable, Equatable {
    /// Always 1: marks a job the companion sent.
    public var companion: Int
    /// The recording's id, the same value as the upload's `Idempotency-Key`.
    public var recordingID: String
    public var workspace: String?

    public init(recordingID: String, workspace: String?) {
        companion = 1
        self.recordingID = recordingID
        self.workspace = workspace
    }

    enum CodingKeys: String, CodingKey {
        case companion, workspace
        case recordingID = "recording_id"
    }
}

/// `GET /v1/jobs/{id}/result?format=json`.
public struct JobResult: Decodable, Sendable, Equatable {
    public var text: String
    public var segments: [JobSegment]
    /// Every word in order, with times and confidences when the engine gives them.
    public var words: [JobWord]
    public var language: String?
    public var durationS: Double?

    enum CodingKeys: String, CodingKey {
        case text, segments, words, language
        case durationS = "duration_s"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        segments = try c.decodeIfPresent([JobSegment].self, forKey: .segments) ?? []
        words = try c.decodeIfPresent([JobWord].self, forKey: .words) ?? []
        language = try c.decodeIfPresent(String.self, forKey: .language)
        durationS = try c.decodeIfPresent(Double.self, forKey: .durationS)
    }
}

public struct JobSegment: Decodable, Sendable, Equatable {
    /// Seconds into the file.
    public var s: Double
    public var e: Double
    public var text: String
    public var speaker: String?
}

/// One word: `s` and `e` are seconds into the file and `c` the confidence from 0 to 1; each is
/// nil from an engine that gives none (Qwen gives no word times).
public struct JobWord: Decodable, Sendable, Equatable {
    public var w: String
    public var s: Double?
    public var e: Double?
    public var c: Double?
}

/// `GET /v1/jobs`: newest first; `cursor` is null on the last page.
public struct JobPage: Decodable, Sendable, Equatable {
    public var jobs: [Job]
    public var cursor: Int?
}

/// akou refuses with `{"error": "<code>", "message": "...", ...}`; the extra fields depend on the code.
public struct AkouErrorBody: Decodable, Sendable, Equatable {
    public var error: String
    public var message: String?
    /// `idempotency_conflict`: the job the key already names, and the fields that differ.
    public var id: String?
    public var fields: [String]?
    /// `queue_full`: the same seconds as the `Retry-After` header.
    public var retryAfterS: Int?

    enum CodingKeys: String, CodingKey {
        case error, message, id, fields
        case retryAfterS = "retry_after_s"
    }
}
