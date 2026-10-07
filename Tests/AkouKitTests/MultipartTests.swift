// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import Foundation
import XCTest

final class MultipartTests: XCTestCase {
    func testWritesFieldsThenTheFileByteForByte() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "multipart-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        // Every byte value, past the 1 MB chunk size, so the copy loop runs more than once.
        var audio = Data((0..<256).map(UInt8.init))
        while audio.count < 1_300_000 { audio.append(audio) }
        let audioURL = dir.appending(path: "a.opus")
        try audio.write(to: audioURL)

        let bodyURL = dir.appending(path: "body")
        let boundary = try Multipart.write(
            fields: [("title", "Standup"), ("metadata", #"{"companion":1}"#)],
            file: .init(name: "file", fileName: "r1.opus", contentType: "audio/ogg", url: audioURL),
            to: bodyURL,
            boundary: "B0UND"
        )
        XCTAssertEqual(boundary, "B0UND")
        let body = try Data(contentsOf: bodyURL)
        let parts = FakeAkouHTTP.parts(body, boundary: boundary)
        XCTAssertEqual(parts.map(\.name), ["title", "metadata", "file"])
        XCTAssertEqual(String(decoding: parts[0].value, as: UTF8.self), "Standup")
        XCTAssertEqual(String(decoding: parts[1].value, as: UTF8.self), #"{"companion":1}"#)
        XCTAssertEqual(parts[2].fileName, "r1.opus")
        XCTAssertEqual(parts[2].value, audio)
        XCTAssertTrue(body.starts(with: Data("--B0UND\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nStandup\r\n".utf8)))
        XCTAssertEqual(body.suffix(13), Data("\r\n--B0UND--\r\n".utf8))
    }
}
