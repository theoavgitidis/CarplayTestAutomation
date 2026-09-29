import Foundation
import Network
import Testing
@testable import TraceMateCore

struct AgentInfrastructureTests {
    @Test func rpcFramingReturnsOnlyTheFirstLine() {
        #expect(RPCLineFraming.line(from: Data("first\nsecond\n".utf8)) == Data("first".utf8))
    }

    @Test func rpcFramingRejectsOversizedInput() {
        let oversized = Data(repeating: 0x61, count: RPCLineFraming.maximumLineBytes + 1)
        #expect(RPCLineFraming.line(from: oversized) == nil)
    }

    @Test func persistedActiveJobRecoversAsUnknown() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = try JobRepository(root: root)
        let job = CaptureJob(
            jobId: UUID(), captureId: UUID(), deviceIdPlaceholder: "PLACEHOLDER-MOBILE-DEVICE-ID",
            outputPath: root.appendingPathComponent("capture.capture_tool_placeholder").path,
            liveJSONPath: root.appendingPathComponent("capture.ndjson").path
        )
        try await repository.save(job)

        let recovered = try JobRepository(root: root)
        let loadedJob = await recovered.get(job.jobId)
        let loaded = try #require(loadedJob)
        #expect(loaded.state == .unknown)
        #expect(loaded.lastError?.contains("restarted") == true)
    }

    @Test func idempotencyKeyFindsPersistedJob() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = try JobRepository(root: root)
        let job = CaptureJob(
            jobId: UUID(), captureId: UUID(), deviceIdPlaceholder: "PLACEHOLDER-MOBILE-DEVICE-ID",
            outputPath: root.appendingPathComponent("capture.capture_tool_placeholder").path,
            liveJSONPath: root.appendingPathComponent("capture.ndjson").path,
            idempotencyKey: "android-request-1"
        )
        try await repository.save(job)

        let matchingJob = await repository.job(idempotencyKey: "android-request-1")
        #expect(matchingJob?.jobId == job.jobId)
    }

    @Test func configuredCaptureDirectoryIsUsedForNewCaptures() throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let captureDirectory = root.appendingPathComponent("CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER", isDirectory: true)

        let configuration = AgentConfiguration(root: root, captureDirectory: captureDirectory)

        #expect(configuration.captureDirectory == captureDirectory)
    }

    @Test func discoveryIdentityIsStableAcrossRestarts() throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }

        let first = try TraceMateIdentity.loadOrCreate(root: root)
        let second = try TraceMateIdentity.loadOrCreate(root: root)

        #expect(first == second)
        #expect(first.protocolVersion == 1)
    }

    @Test func jobStateIsOwnerReadableOnly() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = try JobRepository(root: root)
        let job = CaptureJob(
            jobId: UUID(), captureId: UUID(), deviceIdPlaceholder: "PLACEHOLDER-MOBILE-DEVICE-ID",
            outputPath: root.appendingPathComponent("capture.capture_tool_placeholder").path,
            liveJSONPath: root.appendingPathComponent("capture.ndjson").path
        )
        try await repository.save(job)

        let state = root.appendingPathComponent("state/jobs/\(job.jobId.uuidString).json")
        let permissions = try #require(try FileManager.default.attributesOfItem(atPath: state.path)[.posixPermissions] as? NSNumber)
        #expect(permissions.intValue & 0o077 == 0)
    }

    @Test func sha256MatchesKnownContent() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        try Data("TraceMate".utf8).write(to: file)

        #expect(SystemTools.sha256(of: file) == "c08431273fa37498a38182c4e4acef8ac5de3d92accbd53f3a70e508741fed15")
    }

    @Test func logRotationRetainsTheConfiguredNewestLogs() throws {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        for index in 0..<3 {
            let file = directory.appendingPathComponent("\(index).log")
            try Data().write(to: file)
            try FileManager.default.setAttributes(
                [.modificationDate: Date(timeIntervalSince1970: TimeInterval(index))],
                ofItemAtPath: file.path
            )
        }

        SystemTools.rotateLogs(in: directory, retaining: 2)

        let retained = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
        #expect(retained == ["1.log", "2.log"])
    }

    @Test func immediateCaptureToolPlaceholderExitDoesNotReportRunning() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let configuration = AgentConfiguration(
            captureToolPlaceholderPath: "/usr/bin/false",
            root: root,
            startupReadinessSeconds: 0.01,
            minimumFreeSpaceBytes: 0
        )
        let controller = try CaptureController(configuration: configuration)

        await #expect(throws: AgentError.self) {
            try await controller.start(deviceIdPlaceholder: "PLACEHOLDER-MOBILE-DEVICE-ID")
        }
    }

    @Test func stopEscalatesWhenCaptureToolPlaceholderIgnoresTheCooperativeStopCommand() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let capture_tool_placeholder = try executableScript(in: root, named: "ignore-stdin") {
            "#!/bin/sh\nif [ \"$1\" = export ]; then exit 0; fi\nwhile :; do sleep 1; done\n"
        }
        let controller = try CaptureController(configuration: AgentConfiguration(
            captureToolPlaceholderPath: capture_tool_placeholder.path,
            root: root,
            stopGraceSeconds: 0.01,
            startupReadinessSeconds: 0.01,
            minimumFreeSpaceBytes: 0
        ))
        let job = try await controller.start(deviceIdPlaceholder: "PLACEHOLDER-MOBILE-DEVICE-ID")

        _ = try await controller.stop(jobId: job.jobId)
        let stopped = await eventually {
            guard let status = await controller.status(jobId: job.jobId) else { return false }
            return status.state == .verifying || status.state == .completed || status.state == .failed
        }

        #expect(stopped)
    }

    @Test func rpcClientTimesOutWhenThePeerDoesNotRespond() async throws {
        let listener = try NWListener(using: .tcp, on: .any)
        let queue = DispatchQueue(label: "MAC_AGENT_QUEUE_LABEL_PLACEHOLDER.tests.timeout")
        listener.newConnectionHandler = { connection in connection.start(queue: queue) }
        listener.start(queue: queue)
        defer { listener.cancel() }
        let port = try #require(listener.port?.rawValue)

        do {
            _ = try await RPCClient(port: port, timeout: 0.01).send(RPCRequest(command: "health"))
            Issue.record("RPC client unexpectedly received a response")
        } catch let error as URLError {
            #expect(error.code == .timedOut)
        } catch {
            Issue.record("RPC client failed with an unexpected error: \(error)")
        }
    }

    private func temporaryDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func executableScript(in directory: URL, named name: String, contents: () -> String) throws -> URL {
        let script = directory.appendingPathComponent(name)
        try Data(contents().utf8).write(to: script)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: script.path)
        return script
    }

    private func eventually(_ condition: @escaping @Sendable () async -> Bool) async -> Bool {
        for _ in 0..<100 {
            if await condition() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return false
    }
}
