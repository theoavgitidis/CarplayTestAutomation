import Foundation

public struct CaptureSessionPlaceholderValidator: Sendable {
    private let captureToolPlaceholderPath: String
    private let timeout: TimeInterval

    public init(captureToolPlaceholderPath: String = "CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER", timeout: TimeInterval = 60) {
        self.captureToolPlaceholderPath = captureToolPlaceholderPath
        self.timeout = timeout
    }

    public func validate(capture: URL, output: URL) -> CaptureSessionPlaceholderValidation {
        guard FileManager.default.isReadableFile(atPath: capture.path) else {
            return CaptureSessionPlaceholderValidation(state: .corrupt, diagnostic: "Capture is not readable")
        }

        let process = Process()
        let diagnostics = FileManager.default.temporaryDirectory
            .appendingPathComponent("tracemate-capture_tool_placeholder-export-\(UUID().uuidString).log")
        process.executableURL = URL(fileURLWithPath: captureToolPlaceholderPath)
        process.arguments = [
            "export", "-i", capture.path, "-o", output.path,
            "--transport=wifi", "--layer=CaptureSessionPlaceholder Session",
        ]
        FileManager.default.createFile(atPath: diagnostics.path, contents: nil, attributes: [.posixPermissions: 0o600])
        guard let diagnosticHandle = try? FileHandle(forWritingTo: diagnostics) else {
            return CaptureSessionPlaceholderValidation(state: .exportFailed, diagnostic: "Cannot create CAPTURE_TOOL_PLACEHOLDER diagnostic log")
        }
        defer {
            try? diagnosticHandle.close()
            try? FileManager.default.removeItem(at: diagnostics)
        }
        process.standardOutput = diagnosticHandle
        process.standardError = diagnosticHandle

        do {
            let finished = DispatchSemaphore(value: 0)
            process.terminationHandler = { _ in finished.signal() }
            try process.run()
            if finished.wait(timeout: .now() + timeout) == .timedOut {
                process.terminate()
                _ = finished.wait(timeout: .now() + 5)
                return CaptureSessionPlaceholderValidation(state: .exportFailed, diagnostic: "CAPTURE_TOOL_PLACEHOLDER export timed out")
            }
        } catch {
            return CaptureSessionPlaceholderValidation(state: .exportFailed, diagnostic: error.localizedDescription)
        }

        let data = (try? Data(contentsOf: diagnostics)) ?? Data()
        let message = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard process.terminationStatus == 0 else {
            return CaptureSessionPlaceholderValidation(state: .exportFailed, diagnostic: message)
        }

        do {
            let data = try Data(contentsOf: output)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: output.path)
            guard
                let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                let wifi = root["Wi-Fi"] as? [String: Any],
                let events = wifi["CaptureSessionPlaceholder Session"] as? [Any]
            else {
                return CaptureSessionPlaceholderValidation(state: .exportFailed, diagnostic: "CAPTURE_TOOL_PLACEHOLDER export did not contain Wi-Fi/CaptureSessionPlaceholder Session")
            }
            return CaptureSessionPlaceholderValidation(state: events.isEmpty ? .empty : .valid, eventCount: events.count)
        } catch {
            return CaptureSessionPlaceholderValidation(state: .exportFailed, diagnostic: "Cannot parse CAPTURE_TOOL_PLACEHOLDER export: \(error.localizedDescription)")
        }
    }

    public static func readCompleteLiveJSONLines(at url: URL, from offset: UInt64) -> (count: Int, nextOffset: UInt64) {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return (0, offset) }
        defer { try? handle.close() }
        guard (try? handle.seek(toOffset: offset)) != nil else { return (0, offset) }
        let data = handle.readDataToEndOfFile()
        guard let lastNewline = data.lastIndex(of: 0x0A) else { return (0, offset) }
        let complete = data[...lastNewline]
        let count = complete.split(separator: 0x0A).reduce(into: 0) { count, line in
            if (try? JSONSerialization.jsonObject(with: Data(line))) != nil { count += 1 }
        }
        return (count, offset + UInt64(complete.count))
    }
}
