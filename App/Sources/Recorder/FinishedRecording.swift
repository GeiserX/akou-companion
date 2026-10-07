// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A recording that stopped: its file is complete and ready to upload.
struct FinishedRecording: Identifiable, Codable, Sendable, Equatable {
    /// Also the upload's `Idempotency-Key`, so a retried upload finds the first job.
    let id: UUID
    /// `Application Support/Recordings/<id>.opus`.
    let fileURL: URL
    let startedAt: Date
    /// Seconds of audio in the file; paused time is not in it.
    let duration: TimeInterval
    let workspace: String?
    let title: String?
    /// `auto` or a BCP 47 code, as the live session asked for it.
    let language: String
    /// `auto` or an engine id.
    let model: String
}
