// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A `multipart/form-data` body written to a file. A background `URLSession` uploads only from a
/// file, and a recording can be an hour long, so the audio is copied through in chunks and never
/// held in memory whole.
public enum Multipart {
    public struct FilePart: Sendable {
        public var name: String
        public var fileName: String
        public var contentType: String
        public var url: URL

        public init(name: String, fileName: String, contentType: String, url: URL) {
            self.name = name
            self.fileName = fileName
            self.contentType = contentType
            self.url = url
        }
    }

    /// Writes the text fields, then the file, to `destination` (replacing it) and returns the
    /// boundary for the `Content-Type: multipart/form-data; boundary=...` header.
    @discardableResult
    public static func write(
        fields: [(name: String, value: String)],
        file: FilePart,
        to destination: URL,
        boundary: String = "akou-\(UUID().uuidString)"
    ) throws -> String {
        let fm = FileManager.default
        try? fm.removeItem(at: destination)
        guard fm.createFile(atPath: destination.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: destination.path])
        }
        let out = try FileHandle(forWritingTo: destination)
        defer { try? out.close() }

        for (name, value) in fields {
            try out.write(contentsOf: Data(
                "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(quoted(name))\"\r\n\r\n\(value)\r\n".utf8
            ))
        }
        try out.write(contentsOf: Data((
            "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(quoted(file.name))\"; filename=\"\(quoted(file.fileName))\"\r\n"
                + "Content-Type: \(file.contentType)\r\n\r\n"
        ).utf8))
        let input = try FileHandle(forReadingFrom: file.url)
        defer { try? input.close() }
        while let chunk = try input.read(upToCount: 1 << 20), !chunk.isEmpty {
            try out.write(contentsOf: chunk)
        }
        try out.write(contentsOf: Data("\r\n--\(boundary)--\r\n".utf8))
        return boundary
    }

    /// A header parameter value cannot hold a quote or a line break; the names here are ours, so
    /// dropping them is enough.
    static func quoted(_ s: String) -> String {
        String(s.unicodeScalars.filter { $0 != "\"" && $0 != "\r" && $0 != "\n" }.map(Character.init))
    }
}
