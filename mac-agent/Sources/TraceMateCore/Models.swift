import Foundation

public enum JobState: String, Codable, Sendable {
    case starting = "STARTING"
    case running = "RUNNING"
    case stopRequested = "STOP_REQUESTED"
    case stopping = "STOPPING"
    case verifying = "VERIFYING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case unknown = "UNKNOWN"
}

public enum ActivityState: String, Codable, Sendable {
    case waiting = "WAITING_FOR_ACTIVITY"
    case active = "ACTIVE"
    case idle = "IDLE"
    case noCaptureSessionPlaceholderActivity = "NO_CAPTURE_SESSION_PLACEHOLDER_ACTIVITY"
    case stopped = "STOPPED"
}

public enum CaptureSessionPlaceholderValidationState: String, Codable, Sendable {
    case valid = "VALID"
    case empty = "EMPTY"
    case exportFailed = "EXPORT_FAILED"
    case corrupt = "CORRUPT"
    case unverified = "UNVERIFIED"
}

public struct CaptureSessionPlaceholderValidation: Codable, Sendable, Equatable {
    public var state: CaptureSessionPlaceholderValidationState
    public var eventCount: Int?
    public var transport = "wifi"
    public var layer = "CaptureSessionPlaceholder Session"
    public var diagnostic: String?

    public init(state: CaptureSessionPlaceholderValidationState, eventCount: Int? = nil, diagnostic: String? = nil) {
        self.state = state
        self.eventCount = eventCount
        self.diagnostic = diagnostic
    }
}

public struct CaptureActivity: Codable, Sendable {
    public var state: ActivityState = .waiting
    public var captureBytes: UInt64 = 0
    public var liveJSONBytes: UInt64 = 0
    public var observedLiveEvents: Int = 0
    public var liveJSONOffset: UInt64 = 0
    public var lastGrowthAt: Date?
    public var sampledAt: Date = Date()
}

public struct CaptureArtifactMetadata: Codable, Sendable {
    public var byteCount: UInt64
    public var sha256: String?
    public var completedAt: Date
    public var agentVersion: String
}

public struct CaptureJob: Codable, Sendable {
    public let jobId: UUID
    public let captureId: UUID
    public let deviceIdPlaceholder: String
    public let outputPath: String
    public let liveJSONPath: String
    public let idempotencyKey: String?
    public var finalJSONPath: String?
    public var state: JobState
    public var activity: CaptureActivity
    public let createdAt: Date
    public var startedAt: Date?
    public var endedAt: Date?
    public var pid: Int32?
    public var exitCode: Int32?
    public var lastError: String?
    public var captureSessionPlaceholderValidation: CaptureSessionPlaceholderValidation
    public var artifact: CaptureArtifactMetadata?

    public init(
        jobId: UUID,
        captureId: UUID,
        deviceIdPlaceholder: String,
        outputPath: String,
        liveJSONPath: String,
        idempotencyKey: String? = nil
    ) {
        self.jobId = jobId
        self.captureId = captureId
        self.deviceIdPlaceholder = deviceIdPlaceholder
        self.outputPath = outputPath
        self.liveJSONPath = liveJSONPath
        self.idempotencyKey = idempotencyKey
        self.state = .starting
        self.activity = CaptureActivity()
        self.createdAt = Date()
        self.captureSessionPlaceholderValidation = CaptureSessionPlaceholderValidation(state: .unverified)
        self.artifact = nil
    }
}

public struct AgentConfiguration: Sendable {
    public var captureToolPlaceholderPath: String
    public var root: URL
    public var captureDirectory: URL
    public var host: String
    public var port: UInt16
    public var pollIntervalSeconds: Double
    public var idleWarningSeconds: Double
    public var noActivityWarningSeconds: Double
    public var validationTimeoutSeconds: Double
    public var stopGraceSeconds: Double
    public var startupReadinessSeconds: Double
    public var minimumFreeSpaceBytes: UInt64
    public var maximumJobLogs: Int

    public init(
        captureToolPlaceholderPath: String = "CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER",
        root: URL,
        captureDirectory: URL? = nil,
        host: String = "127.0.0.1",
        port: UInt16 = 47_652,
        pollIntervalSeconds: Double = 2,
        idleWarningSeconds: Double = 30,
        noActivityWarningSeconds: Double = 30,
        validationTimeoutSeconds: Double = 60,
        stopGraceSeconds: Double = 15,
        startupReadinessSeconds: Double = 1,
        minimumFreeSpaceBytes: UInt64 = 1_073_741_824,
        maximumJobLogs: Int = 100
    ) {
        self.captureToolPlaceholderPath = captureToolPlaceholderPath
        self.root = root
        self.captureDirectory = captureDirectory ?? root.appendingPathComponent("captures", isDirectory: true)
        self.host = host
        self.port = port
        self.pollIntervalSeconds = pollIntervalSeconds
        self.idleWarningSeconds = idleWarningSeconds
        self.noActivityWarningSeconds = noActivityWarningSeconds
        self.validationTimeoutSeconds = validationTimeoutSeconds
        self.stopGraceSeconds = stopGraceSeconds
        self.startupReadinessSeconds = startupReadinessSeconds
        self.minimumFreeSpaceBytes = minimumFreeSpaceBytes
        self.maximumJobLogs = maximumJobLogs
    }

    public static func production() -> AgentConfiguration {
        let home = FileManager.default.homeDirectoryForCurrentUser
        let base = home
            .appendingPathComponent("Library/Application Support/TraceMate", isDirectory: true)
        return AgentConfiguration(
            root: base,
            captureDirectory: home.appendingPathComponent("CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER", isDirectory: true)
        )
    }
}

public struct TraceMateIdentity: Codable, Sendable, Equatable {
    public static let protocolVersion = 1

    public let id: UUID
    public let protocolVersion: Int

    public init(id: UUID, protocolVersion: Int = TraceMateIdentity.protocolVersion) {
        self.id = id
        self.protocolVersion = protocolVersion
    }

    public static func loadOrCreate(root: URL) throws -> TraceMateIdentity {
        let file = root.appendingPathComponent("identity.json")
        if FileManager.default.fileExists(atPath: file.path) {
            return try JSONDecoder().decode(TraceMateIdentity.self, from: Data(contentsOf: file))
        }

        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let identity = TraceMateIdentity(id: UUID())
        try JSONEncoder().encode(identity).write(to: file, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        return identity
    }
}

public struct RPCRequest: Codable, Sendable {
    public var command: String
    public var arguments: [String: String] = [:]

    public init(command: String, arguments: [String: String] = [:]) {
        self.command = command
        self.arguments = arguments
    }
}

public struct RPCResponse: Codable, Sendable {
    public var ok: Bool
    public var result: JSONValue?
    public var error: String?

    public init(ok: Bool, result: JSONValue? = nil, error: String? = nil) {
        self.ok = ok
        self.result = result
        self.error = error
    }
}

public enum JSONValue: Codable, Sendable {
    case string(String), unsignedInteger(UInt64), integer(Int64), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    public init(from decoder: Decoder) throws {
        let box = try decoder.singleValueContainer()
        if box.decodeNil() { self = .null }
        else if let value = try? box.decode(Bool.self) { self = .bool(value) }
        else if let value = try? box.decode(UInt64.self) { self = .unsignedInteger(value) }
        else if let value = try? box.decode(Int64.self) { self = .integer(value) }
        else if let value = try? box.decode(Double.self) { self = .number(value) }
        else if let value = try? box.decode(String.self) { self = .string(value) }
        else if let value = try? box.decode([String: JSONValue].self) { self = .object(value) }
        else { self = .array(try box.decode([JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var box = encoder.singleValueContainer()
        switch self {
        case .string(let value): try box.encode(value)
        case .unsignedInteger(let value): try box.encode(value)
        case .integer(let value): try box.encode(value)
        case .number(let value): try box.encode(value)
        case .bool(let value): try box.encode(value)
        case .object(let value): try box.encode(value)
        case .array(let value): try box.encode(value)
        case .null: try box.encodeNil()
        }
    }
}
