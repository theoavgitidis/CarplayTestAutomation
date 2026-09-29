import Foundation

public actor JobRepository {
    private let stateDirectory: URL
    private var jobs: [UUID: CaptureJob] = [:]

    public init(root: URL) throws {
        stateDirectory = root.appendingPathComponent("state/jobs", isDirectory: true)
        try FileManager.default.createDirectory(at: stateDirectory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: stateDirectory.path)
        jobs = try Self.load(from: stateDirectory)
    }

    public func all() -> [CaptureJob] {
        jobs.values.sorted { $0.createdAt > $1.createdAt }
    }

    public func get(_ id: UUID) -> CaptureJob? { jobs[id] }

    public func active() -> CaptureJob? {
        jobs.values.first { [.starting, .running, .stopRequested, .stopping, .verifying, .unknown].contains($0.state) }
    }

    public func job(idempotencyKey: String) -> CaptureJob? {
        jobs.values.first { $0.idempotencyKey == idempotencyKey }
    }

    public func save(_ job: CaptureJob) throws {
        jobs[job.jobId] = job
        let data = try Self.encoder.encode(job)
        let destination = stateDirectory.appendingPathComponent("\(job.jobId.uuidString).json")
        try data.write(to: destination, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
    }

    private static func load(from stateDirectory: URL) throws -> [UUID: CaptureJob] {
        var loaded: [UUID: CaptureJob] = [:]
        let files = try FileManager.default.contentsOfDirectory(at: stateDirectory, includingPropertiesForKeys: nil)
        for file in files where file.pathExtension == "json" {
            if let data = try? Data(contentsOf: file), let job = try? Self.decoder.decode(CaptureJob.self, from: data) {
                var recovered = job
                if [.starting, .running, .stopRequested, .stopping, .verifying].contains(recovered.state) {
                    recovered.state = .unknown
                    recovered.lastError = "Agent restarted; CAPTURE_TOOL_PLACEHOLDER stdin ownership cannot be recovered"
                }
                loaded[recovered.jobId] = recovered
            }
        }
        return loaded
    }

    private static let encoder: JSONEncoder = {
        let value = JSONEncoder()
        value.outputFormatting = [.prettyPrinted, .sortedKeys]
        value.dateEncodingStrategy = .iso8601
        return value
    }()

    private static let decoder: JSONDecoder = {
        let value = JSONDecoder()
        value.dateDecodingStrategy = .iso8601
        return value
    }()
}
