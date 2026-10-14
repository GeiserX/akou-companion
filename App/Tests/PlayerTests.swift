// SPDX-License-Identifier: GPL-3.0-or-later
import AVFoundation
import XCTest
@testable import akou

/// The detail screen's player, on a file the recorder itself wrote. Nothing is played: the test
/// only waits for the player to know the length.
@MainActor
final class PlayerTests: XCTestCase {
    func testThePlayerLearnsTheLengthOfARecording() async throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "player-\(UUID().uuidString).opus")
        defer { try? FileManager.default.removeItem(at: url) }
        let file = try RecordingFile(url: url)
        for _ in 0..<20 { file.append([Float](repeating: 0, count: 1600)) }
        let (seconds, error) = await file.finish()
        XCTAssertNil(error)
        XCTAssertEqual(seconds, 2, accuracy: 0.1)

        let player = RecordingPlayer()
        player.load(jobId: "job_local", keptOnServer: false, local: url, client: nil)
        XCTAssertEqual(player.source, .local(url))
        let deadline = Date().addingTimeInterval(20)
        while player.duration == nil && player.problem == nil && Date() < deadline {
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertNil(player.problem)
        // AVFoundation estimates an Ogg's length from its pages, so the bound is loose (3.0 s for
        // this 2 s file in the iOS 26.5 Simulator); without the fix the length stays unknown.
        let length = try XCTUnwrap(player.duration, "the player never learned the length")
        XCTAssertEqual(length, seconds, accuracy: 1.5)
        player.stop()
    }

    /// The server's length is shown over AVFoundation's estimate, which for an Ogg Opus can be far
    /// off (18:38 for a call part of 11:10); the estimate stays the fallback.
    func testTheServersLengthWinsOverThePlayersEstimate() async throws {
        XCTAssertEqual(RecordingPlayer.length(known: 669.66, estimated: 1118), 669.66)
        XCTAssertEqual(RecordingPlayer.length(known: nil, estimated: 1118), 1118)
        XCTAssertEqual(RecordingPlayer.length(known: 0, estimated: 1118), 1118)
        XCTAssertEqual(RecordingPlayer.length(known: .nan, estimated: 1118), 1118)
        XCTAssertNil(RecordingPlayer.length(known: nil, estimated: nil))

        let url = FileManager.default.temporaryDirectory.appending(path: "player-\(UUID().uuidString).opus")
        defer { try? FileManager.default.removeItem(at: url) }
        let file = try RecordingFile(url: url)
        for _ in 0..<20 { file.append([Float](repeating: 0, count: 1600)) }
        _ = await file.finish()

        // A length no estimate of this 2 s file comes near, so only the server's can pass.
        let player = RecordingPlayer()
        player.load(path: nil, local: url, length: 7.5, request: nil, missing: "")
        XCTAssertEqual(player.duration, 7.5)
        let deadline = Date().addingTimeInterval(20)
        while player.estimatedLength == nil && player.problem == nil && Date() < deadline {
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertNil(player.problem)
        XCTAssertNotNil(player.estimatedLength, "the player never read its own length")
        XCTAssertEqual(player.duration, 7.5, "the player's estimate replaced the server's length")
        player.stop()
        XCTAssertNil(player.duration)
    }
}
