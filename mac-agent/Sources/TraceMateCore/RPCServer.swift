@preconcurrency import Network
import Foundation

public final class RPCServer: @unchecked Sendable {
    private let controller: CaptureController
    private let configuration: AgentConfiguration
    private let listener: NWListener
    private var retainedObject: AnyObject?

    public init(controller: CaptureController, configuration: AgentConfiguration) throws {
        self.controller = controller
        self.configuration = configuration
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(
            host: NWEndpoint.Host(configuration.host),
            port: NWEndpoint.Port(rawValue: configuration.port)!
        )
        listener = try NWListener(using: parameters)
    }

    public func run(keepingAlive object: AnyObject? = nil) async throws {
        retainedObject = object
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.stateUpdateHandler = { state in
            if case .failed(let error) = state {
                FileHandle.standardError.write(Data("Listener failed: \(error)\n".utf8))
                exit(1)
            }
        }
        listener.start(queue: DispatchQueue(label: "MAC_AGENT_QUEUE_LABEL_PLACEHOLDER.rpc"))
        try await Task.sleep(for: .seconds(365 * 24 * 60 * 60))
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: DispatchQueue(label: "MAC_AGENT_QUEUE_LABEL_PLACEHOLDER.connection"))
        receiveLine(connection) { [weak self] data, error in
            guard let self, let data, error == nil else { connection.cancel(); return }
            Task {
                let response = await self.handle(data)
                let encoder = JSONEncoder()
                encoder.outputFormatting = [.sortedKeys]
                let encoded = (try? encoder.encode(response)) ?? Data(#"{"ok":false,"error":"Encoding failure"}"#.utf8)
                connection.send(content: encoded + Data([0x0A]), completion: .contentProcessed { _ in connection.cancel() })
            }
        }
    }

    private func receiveLine(_ connection: NWConnection, buffer: Data = Data(), completion: @escaping @Sendable (Data?, Error?) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { [weak self] data, _, _, error in
            guard let self, error == nil, let data else { completion(nil, error); return }
            let combined = buffer + data
            guard combined.count <= RPCLineFraming.maximumLineBytes else { completion(nil, URLError(.dataLengthExceedsMaximum)); return }
            if let line = RPCLineFraming.line(from: combined) {
                completion(line, nil)
            } else {
                self.receiveLine(connection, buffer: combined, completion: completion)
            }
        }
    }

    private func handle(_ data: Data) async -> RPCResponse {
        do {
            let request = try JSONDecoder().decode(RPCRequest.self, from: data)
            switch request.command {
            case "health":
                return RPCResponse(ok: true, result: .object([
                    "status": .string("ok"),
                    "agentVersion": .string("0.1.0"),
                    "captureToolPlaceholderCliPath": .string(configuration.captureToolPlaceholderPath),
                    "captureToolPlaceholderCliAvailable": .bool(FileManager.default.isExecutableFile(atPath: configuration.captureToolPlaceholderPath)),
                    "supports": .array([.string("capabilities"), .string("devices"), .string("preflight"), .string("start"), .string("status"), .string("stop")]),
                ]))
            case "capabilities":
                return RPCResponse(ok: true, result: .object([
                    "agentVersion": .string("0.1.0"),
                    "captureToolPlaceholderCliAvailable": .bool(FileManager.default.isExecutableFile(atPath: configuration.captureToolPlaceholderPath)),
                    "caffeinateAvailable": .bool(FileManager.default.isExecutableFile(atPath: "/usr/bin/caffeinate")),
                    "transports": .array([.string("wifi"), .string("bluetooth")]),
                ]))
            case "devices":
                return try await controller.devices()
            case "preflight":
                return try await controller.preflight()
            case "start":
                guard let deviceIdPlaceholder = request.arguments["deviceIdPlaceholder"] else { return RPCResponse(ok: false, error: "Missing deviceIdPlaceholder") }
                let job = try await controller.start(
                    deviceIdPlaceholder: deviceIdPlaceholder,
                    requestedName: request.arguments["name"],
                    idempotencyKey: request.arguments["requestId"]
                )
                return RPCResponse(ok: true, result: try Self.jsonValue(job))
            case "status":
                guard let text = request.arguments["job"], let id = UUID(uuidString: text) else {
                    return RPCResponse(ok: false, error: "Invalid job ID")
                }
                guard let job = await controller.status(jobId: id) else { return RPCResponse(ok: false, error: "Job not found") }
                return RPCResponse(ok: true, result: try Self.jsonValue(job))
            case "stop":
                guard let text = request.arguments["job"], let id = UUID(uuidString: text) else {
                    return RPCResponse(ok: false, error: "Invalid job ID")
                }
                return RPCResponse(ok: true, result: try Self.jsonValue(try await controller.stop(jobId: id)))
            case "jobs":
                return RPCResponse(ok: true, result: try Self.jsonValue(await controller.listJobs()))
            default:
                return RPCResponse(ok: false, error: "Unknown command: \(request.command)")
            }
        } catch {
            return RPCResponse(ok: false, error: error.localizedDescription)
        }
    }

    private static func jsonValue<T: Encodable>(_ value: T) throws -> JSONValue {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return try JSONDecoder().decode(JSONValue.self, from: encoder.encode(value))
    }
}

public struct RPCClient: Sendable {
    public let host: String
    public let port: UInt16
    public let timeout: TimeInterval

    public init(host: String = "127.0.0.1", port: UInt16 = 47_652, timeout: TimeInterval = 10) {
        self.host = host
        self.port = port
        self.timeout = timeout
    }

    public func send(_ request: RPCRequest) async throws -> RPCResponse {
        let connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        return try await withCheckedThrowingContinuation { continuation in
            final class State: @unchecked Sendable {
                let lock = NSLock()
                var resumed = false
                func once(_ operation: () -> Void) { lock.lock(); defer { lock.unlock() }; if !resumed { resumed = true; operation() } }
            }
            let state = State()
            connection.stateUpdateHandler = { status in
                switch status {
                case .ready:
                    do {
                        let body = try JSONEncoder().encode(request)
                        connection.send(content: body + Data([0x0A]), completion: .contentProcessed { error in
                            if let error { state.once { continuation.resume(throwing: error) } }
                        })
                        Self.receiveLine(connection) { data, error in
                            if let error { state.once { continuation.resume(throwing: error) }; return }
                            guard let data else { state.once { continuation.resume(throwing: URLError(.badServerResponse)) }; return }
                            do {
                                let response = try JSONDecoder().decode(RPCResponse.self, from: data)
                                state.once { continuation.resume(returning: response) }
                            } catch { state.once { continuation.resume(throwing: error) } }
                            connection.cancel()
                        }
                    } catch { state.once { continuation.resume(throwing: error) } }
                case .failed(let error): state.once { continuation.resume(throwing: error) }
                default: break
                }
            }
            connection.start(queue: DispatchQueue(label: "MAC_AGENT_QUEUE_LABEL_PLACEHOLDER.client"))
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                state.once {
                    connection.cancel()
                    continuation.resume(throwing: URLError(.timedOut))
                }
            }
        }
    }

    private static func receiveLine(_ connection: NWConnection, buffer: Data = Data(), completion: @escaping @Sendable (Data?, Error?) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { data, _, _, error in
            guard error == nil, let data else { completion(nil, error); return }
            let combined = buffer + data
            guard combined.count <= RPCLineFraming.maximumLineBytes else { completion(nil, URLError(.dataLengthExceedsMaximum)); return }
            if let line = RPCLineFraming.line(from: combined) {
                completion(line, nil)
            } else {
                receiveLine(connection, buffer: combined, completion: completion)
            }
        }
    }
}
