import Foundation

public enum AgentError: LocalizedError {
    case captureAlreadyRunning
    case invalidDeviceIdPlaceholder
    case jobNotFound
    case jobNotRunning
    case captureToolPlaceholderUnavailable
    case insufficientStorage
    case invalidIdempotencyKey

    public var errorDescription: String? {
        switch self {
        case .captureAlreadyRunning: "A TraceMate CAPTURE_TOOL_PLACEHOLDER capture is already active"
        case .invalidDeviceIdPlaceholder: "DEVICE_ID_PLACEHOLDER contains unsupported characters"
        case .jobNotFound: "Capture job was not found"
        case .jobNotRunning: "Capture job is not running"
        case .captureToolPlaceholderUnavailable: "CAPTURE_TOOL_PLACEHOLDER CLI is not executable"
        case .insufficientStorage: "Insufficient free space for an CAPTURE_TOOL_PLACEHOLDER capture"
        case .invalidIdempotencyKey: "Request ID contains unsupported characters"
        }
    }
}

public actor CaptureController {
    private final class Runtime: @unchecked Sendable {
        let process: Process
        let stdin: FileHandle
        let log: FileHandle
        let powerAssertion: Process?
        var monitor: Task<Void, Never>?

        init(process: Process, stdin: FileHandle, log: FileHandle, powerAssertion: Process?) {
            self.process = process
            self.stdin = stdin
            self.log = log
            self.powerAssertion = powerAssertion
        }
    }

    private let configuration: AgentConfiguration
    private let repository: JobRepository
    private let validator: CaptureSessionPlaceholderValidator
    private var runtimes: [UUID: Runtime] = [:]

    public init(configuration: AgentConfiguration) throws {
        self.configuration = configuration
        repository = try JobRepository(root: configuration.root)
        validator = CaptureSessionPlaceholderValidator(captureToolPlaceholderPath: configuration.captureToolPlaceholderPath, timeout: configuration.validationTimeoutSeconds)
        try Self.prepareDirectories(root: configuration.root)
    }

    public func listJobs() async -> [CaptureJob] { await repository.all() }
    public func status(jobId: UUID) async -> CaptureJob? { await repository.get(jobId) }

    public func start(
        deviceIdPlaceholder: String,
        requestedName: String? = nil,
        idempotencyKey: String? = nil
    ) async throws -> CaptureJob {
        guard Self.validDeviceIdPlaceholder(deviceIdPlaceholder) else { throw AgentError.invalidDeviceIdPlaceholder }
        guard idempotencyKey == nil || Self.validIdempotencyKey(idempotencyKey!) else { throw AgentError.invalidIdempotencyKey }
        guard FileManager.default.isExecutableFile(atPath: configuration.captureToolPlaceholderPath) else { throw AgentError.captureToolPlaceholderUnavailable }
        if let idempotencyKey, let existing = await repository.job(idempotencyKey: idempotencyKey) { return existing }
        if await repository.active() != nil { throw AgentError.captureAlreadyRunning }
        guard (SystemTools.availableSpace(at: configuration.root) ?? 0) >= configuration.minimumFreeSpaceBytes else {
            throw AgentError.insufficientStorage
        }

        let jobId = UUID()
        let captureId = UUID()
        let stem = Self.captureStem(requestedName: requestedName, captureId: captureId)
        let captures = configuration.captureDirectory
        try FileManager.default.createDirectory(
            at: captures,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: captures.path)
        let live = configuration.root.appendingPathComponent("live", isDirectory: true)
        let logs = configuration.root.appendingPathComponent("logs/jobs", isDirectory: true)
        let output = captures.appendingPathComponent("\(stem).capture_tool_placeholder")
        let liveJSON = live.appendingPathComponent("\(captureId.uuidString).ndjson")
        let logURL = logs.appendingPathComponent("\(jobId.uuidString).log")
        SystemTools.rotateLogs(in: logs, retaining: configuration.maximumJobLogs)

        FileManager.default.createFile(atPath: logURL.path, contents: nil, attributes: [.posixPermissions: 0o600])
        let log = try FileHandle(forWritingTo: logURL)
        let input = Pipe()
        let process = Process()
        process.executableURL = URL(fileURLWithPath: configuration.captureToolPlaceholderPath)
        process.arguments = [
            "start", "-o", output.path,
            "--transport=wifi,bluetooth", "--protocol=capture_session_placeholder,iap2",
            "--deviceIdPlaceholder=\(deviceIdPlaceholder)", "-v",
            "export", "-o", liveJSON.path,
            "--transport=wifi", "--layer=CaptureSessionPlaceholder Session",
        ]
        process.standardInput = input
        process.standardOutput = log
        process.standardError = log

        var job = CaptureJob(
            jobId: jobId,
            captureId: captureId,
            deviceIdPlaceholder: deviceIdPlaceholder,
            outputPath: output.path,
            liveJSONPath: liveJSON.path,
            idempotencyKey: idempotencyKey
        )
        try await repository.save(job)

        process.terminationHandler = { [weak self] process in
            Task { await self?.processEnded(jobId: jobId, exitCode: process.terminationStatus) }
        }
        do {
            try process.run()
        } catch {
            job.state = .failed
            job.lastError = error.localizedDescription
            job.endedAt = Date()
            try await repository.save(job)
            try? log.close()
            throw error
        }

        let powerAssertion = Self.startPowerAssertion()
        job.pid = process.processIdentifier
        job.startedAt = Date()
        try await Task.sleep(for: .seconds(configuration.startupReadinessSeconds))
        guard process.isRunning else {
            job.state = .failed
            job.endedAt = Date()
            job.lastError = "CAPTURE_TOOL_PLACEHOLDER exited before startup readiness was confirmed"
            try await repository.save(job)
            powerAssertion?.terminate()
            throw AgentError.jobNotRunning
        }
        job.state = .running
        try await repository.save(job)

        let runtime = Runtime(process: process, stdin: input.fileHandleForWriting, log: log, powerAssertion: powerAssertion)
        runtimes[jobId] = runtime
        runtime.monitor = Task { [weak self] in await self?.monitor(jobId: jobId) }
        return job
    }

    public func stop(jobId: UUID) async throws -> CaptureJob {
        guard var job = await repository.get(jobId) else { throw AgentError.jobNotFound }
        if [.stopRequested, .stopping, .verifying, .completed].contains(job.state) { return job }
        guard job.state == .running, let runtime = runtimes[jobId] else { throw AgentError.jobNotRunning }
        job.state = .stopRequested
        try await repository.save(job)
        try runtime.stdin.write(contentsOf: Data("stop\n".utf8))
        job.state = .stopping
        try await repository.save(job)
        let stopGraceSeconds = configuration.stopGraceSeconds
        Task { [weak runtime] in
            try? await Task.sleep(for: .seconds(stopGraceSeconds))
            guard let runtime, runtime.process.isRunning else { return }
            runtime.process.terminate()
        }
        return job
    }

    public func validateExisting(capture: URL, output: URL) -> CaptureSessionPlaceholderValidation {
        validator.validate(capture: capture, output: output)
    }

    public func devices() async throws -> RPCResponse {
        guard FileManager.default.isExecutableFile(atPath: configuration.captureToolPlaceholderPath) else { throw AgentError.captureToolPlaceholderUnavailable }
        return RPCResponse(ok: true, result: .object([
            "wifi": .array(Self.captureToolPlaceholderList(transport: "wifi", captureToolPlaceholderPath: configuration.captureToolPlaceholderPath).map(JSONValue.string)),
            "bluetooth": .array(Self.captureToolPlaceholderList(transport: "bluetooth", captureToolPlaceholderPath: configuration.captureToolPlaceholderPath).map(JSONValue.string)),
        ]))
    }

    public func preflight() async throws -> RPCResponse {
        let captureToolPlaceholderAvailable = FileManager.default.isExecutableFile(atPath: configuration.captureToolPlaceholderPath)
        let freeSpace = SystemTools.availableSpace(at: configuration.root) ?? 0
        let activeJob = await repository.active() != nil
        let ready = captureToolPlaceholderAvailable && freeSpace >= configuration.minimumFreeSpaceBytes && !activeJob
        return RPCResponse(ok: ready, result: .object([
            "captureToolPlaceholderCliAvailable": .bool(captureToolPlaceholderAvailable),
            "freeSpaceBytes": .unsignedInteger(freeSpace),
            "minimumFreeSpaceBytes": .unsignedInteger(configuration.minimumFreeSpaceBytes),
            "activeJob": .bool(activeJob),
            "wifiDevices": .array(captureToolPlaceholderAvailable ? Self.captureToolPlaceholderList(transport: "wifi", captureToolPlaceholderPath: configuration.captureToolPlaceholderPath).map(JSONValue.string) : []),
            "bluetoothDevices": .array(captureToolPlaceholderAvailable ? Self.captureToolPlaceholderList(transport: "bluetooth", captureToolPlaceholderPath: configuration.captureToolPlaceholderPath).map(JSONValue.string) : []),
        ]), error: ready ? nil : "CAPTURE_TOOL_PLACEHOLDER unavailable, insufficient free space, or a capture is already active")
    }

    private func monitor(jobId: UUID) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(configuration.pollIntervalSeconds))
            guard var job = await repository.get(jobId), job.state == .running || job.state == .stopping else { return }
            let now = Date()
            let captureBytes = Self.fileSize(job.outputPath)
            let liveBytes = Self.fileSize(job.liveJSONPath)
            let grew = captureBytes > job.activity.captureBytes || liveBytes > job.activity.liveJSONBytes
            if grew { job.activity.lastGrowthAt = now }
            job.activity.captureBytes = captureBytes
            job.activity.liveJSONBytes = liveBytes
            let events = CaptureSessionPlaceholderValidator.readCompleteLiveJSONLines(
                at: URL(fileURLWithPath: job.liveJSONPath),
                from: job.activity.liveJSONOffset
            )
            job.activity.observedLiveEvents += events.count
            job.activity.liveJSONOffset = events.nextOffset
            job.activity.sampledAt = now

            if job.activity.observedLiveEvents > 0 || grew {
                let quiet = now.timeIntervalSince(job.activity.lastGrowthAt ?? now)
                job.activity.state = quiet >= configuration.idleWarningSeconds ? .idle : .active
            } else if now.timeIntervalSince(job.startedAt ?? job.createdAt) >= configuration.noActivityWarningSeconds {
                job.activity.state = .noCaptureSessionPlaceholderActivity
            } else {
                job.activity.state = .waiting
            }
            try? await repository.save(job)
        }
    }

    private func processEnded(jobId: UUID, exitCode: Int32) async {
        guard var job = await repository.get(jobId) else { return }
        runtimes[jobId]?.monitor?.cancel()
        try? runtimes[jobId]?.stdin.close()
        try? runtimes[jobId]?.log.close()
        runtimes[jobId]?.powerAssertion?.terminate()
        runtimes[jobId] = nil
        job.exitCode = exitCode
        job.endedAt = Date()
        job.activity.state = .stopped
        job.state = .verifying
        try? await repository.save(job)

        let exports = configuration.root.appendingPathComponent("exports/\(job.captureId.uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: exports, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: exports.path)
        let output = exports.appendingPathComponent("capture_session_placeholder-session.json")
        job.finalJSONPath = output.path
        job.captureSessionPlaceholderValidation = await Task.detached { [validator] in
            validator.validate(capture: URL(fileURLWithPath: job.outputPath), output: output)
        }.value
        job.artifact = CaptureArtifactMetadata(
            byteCount: Self.fileSize(job.outputPath),
            sha256: SystemTools.sha256(of: URL(fileURLWithPath: job.outputPath)),
            completedAt: Date(),
            agentVersion: "0.1.0"
        )
        if exitCode == 0 && job.captureSessionPlaceholderValidation.state == .valid {
            job.state = .completed
        } else {
            job.state = .failed
            if job.lastError == nil {
                job.lastError = job.captureSessionPlaceholderValidation.state == .empty
                    ? "CAPTURE_TOOL_PLACEHOLDER capture contains no CaptureSessionPlaceholder Session events"
                    : "CAPTURE_TOOL_PLACEHOLDER exited with status \(exitCode); validation: \(job.captureSessionPlaceholderValidation.state.rawValue)"
            }
        }
        try? await repository.save(job)
    }

    private static func prepareDirectories(root: URL) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
        for child in ["captures", "live", "exports", "logs/jobs", "state/jobs"] {
            let directory = root.appendingPathComponent(child, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        }
    }

    private static func validDeviceIdPlaceholder(_ value: String) -> Bool {
        !value.isEmpty && value.count <= 64 && value.unicodeScalars.allSatisfy {
            CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-").contains($0)
        }
    }

    private static func validIdempotencyKey(_ value: String) -> Bool {
        !value.isEmpty && value.count <= 64 && value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_")).contains($0)
        }
    }

    private static func startPowerAssertion() -> Process? {
        guard FileManager.default.isExecutableFile(atPath: "/usr/bin/caffeinate") else { return nil }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/caffeinate")
        process.arguments = ["-i", "-m"]
        try? process.run()
        return process.isRunning ? process : nil
    }

    private static func captureToolPlaceholderList(transport: String, captureToolPlaceholderPath: String) -> [String] {
        let output = Pipe()
        let process = Process()
        process.executableURL = URL(fileURLWithPath: captureToolPlaceholderPath)
        process.arguments = ["list", "--transport=\(transport)"]
        process.standardOutput = output
        process.standardError = output
        guard (try? process.run()) != nil else { return [] }
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return [] }
        return String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            .split(whereSeparator: \.isNewline)
            .map(String.init)
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private static func captureStem(requestedName: String?, captureId: UUID) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_") )
        let clean = (requestedName ?? "CaptureSessionPlaceholder_Wireless").unicodeScalars.map { allowed.contains($0) ? Character(String($0)) : "_" }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return "\(String(clean))_\(formatter.string(from: Date()))_\(captureId.uuidString.prefix(8))"
    }

    private static func fileSize(_ path: String) -> UInt64 {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: path) else { return 0 }
        return (attributes[.size] as? NSNumber)?.uint64Value ?? 0
    }
}
