import Foundation
import Testing
@testable import TraceMateCore

struct CaptureSessionPlaceholderValidatorTests {
    private let captures = URL(fileURLWithPath: "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIR_PLACEHOLDER", isDirectory: true)

    @Test(.enabled(if: Self.integrationPrerequisitesAvailable)) func successfulCapturesContainCaptureSessionPlaceholderEvents() throws {
        let validator = CaptureSessionPlaceholderValidator()
        for name in ["CaptureSessionPlaceholder_Wireless.capture_tool_placeholder", "CaptureSessionPlaceholder_Wireless_20260728_152602.capture_tool_placeholder"] {
            let output = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json")
            let result = validator.validate(capture: captures.appendingPathComponent(name), output: output)
            #expect(result.state == .valid)
            #expect((result.eventCount ?? 0) > 0)
        }
    }

    @Test(.enabled(if: Self.integrationPrerequisitesAvailable)) func failedCaptureHasNoCaptureSessionPlaceholderEvents() throws {
        let output = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json")
        let result = CaptureSessionPlaceholderValidator().validate(
            capture: captures.appendingPathComponent("CaptureSessionPlaceholder_Wireless_20260811_114149.capture_tool_placeholder"),
            output: output
        )
        #expect(result == CaptureSessionPlaceholderValidation(state: .empty, eventCount: 0))
    }

    @Test func liveJSONReaderCountsOnlyCompleteNewLines() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).ndjson")
        defer { try? FileManager.default.removeItem(at: file) }
        let first = "{\"event\":1}\n{\"event\":2}"
        try Data(first.utf8).write(to: file)

        let initial = CaptureSessionPlaceholderValidator.readCompleteLiveJSONLines(at: file, from: 0)
        #expect(initial.count == 1)
        #expect(initial.nextOffset == UInt64("{\"event\":1}\n".utf8.count))

        let handle = try FileHandle(forWritingTo: file)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("\n{\"event\":3}\n".utf8))
        try handle.close()

        let appended = CaptureSessionPlaceholderValidator.readCompleteLiveJSONLines(at: file, from: initial.nextOffset)
        #expect(appended.count == 2)
        let fileSize = UInt64(try Data(contentsOf: file).count)
        #expect(appended.nextOffset == fileSize)
    }

    private static var integrationPrerequisitesAvailable: Bool {
        FileManager.default.isExecutableFile(atPath: "CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER") &&
            FileManager.default.fileExists(atPath: "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIR_PLACEHOLDER")
    }
}
