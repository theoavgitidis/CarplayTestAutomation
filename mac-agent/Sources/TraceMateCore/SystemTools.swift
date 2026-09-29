import CryptoKit
import Foundation

enum SystemTools {
    static func availableSpace(at url: URL) -> UInt64? {
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage.map { UInt64($0) }
    }

    static func sha256(of file: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: file) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try? handle.read(upToCount: 1_048_576), !data.isEmpty {
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    static func rotateLogs(in directory: URL, retaining maximum: Int) {
        guard maximum >= 0,
              let files = try? FileManager.default.contentsOfDirectory(
                  at: directory,
                  includingPropertiesForKeys: [.contentModificationDateKey]
              )
        else { return }
        let logs = files.filter { $0.pathExtension == "log" }.sorted {
            let left = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            let right = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            return left > right
        }
        for file in logs.dropFirst(maximum) { try? FileManager.default.removeItem(at: file) }
    }
}

enum RPCLineFraming {
    static let maximumLineBytes = 1_048_576

    static func line(from data: Data) -> Data? {
        guard data.count <= maximumLineBytes, let newline = data.firstIndex(of: 0x0A) else { return nil }
        return Data(data[..<newline])
    }
}
