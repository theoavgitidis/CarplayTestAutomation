@preconcurrency import Network
import Foundation

public final class IdentityServer: @unchecked Sendable {
    private let identity: TraceMateIdentity
    private let listener: NWListener

    private final class StartupState: @unchecked Sendable {
        var continuation: CheckedContinuation<Void, Error>?
        var isReady = false
    }

    public init(identity: TraceMateIdentity) throws {
        self.identity = identity
        listener = try NWListener(using: .tcp, on: .any)
        let record = NetService.data(fromTXTRecord: [
            "id": Data(identity.id.uuidString.utf8),
            "protocol": Data(String(identity.protocolVersion).utf8),
        ])
        listener.service = NWListener.Service(
            name: "MAC_AGENT_DISCOVERY_NAME_PLACEHOLDER-\(identity.id.uuidString)",
            type: "MAC_AGENT_DISCOVERY_SERVICE_PLACEHOLDER",
            domain: "local.",
            txtRecord: record
        )
    }

    public func start() async throws {
        let startup = StartupState()
        listener.newConnectionHandler = { [identity] connection in
            connection.start(queue: DispatchQueue(label: "MAC_AGENT_QUEUE_LABEL_PLACEHOLDER.identity.connection"))
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let response = (try? encoder.encode(identity)) ?? Data(#"{"protocolVersion":1}"#.utf8)
            connection.send(content: response + Data([0x0A]), completion: .contentProcessed { _ in
                connection.cancel()
            })
        }
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                guard !startup.isReady else { return }
                startup.isReady = true
                startup.continuation?.resume()
                startup.continuation = nil
            case .failed(let error):
                if let continuation = startup.continuation {
                    startup.continuation = nil
                    continuation.resume(throwing: error)
                }
                FileHandle.standardError.write(Data("Identity listener failed: \(error)\n".utf8))
            case .cancelled:
                if let continuation = startup.continuation {
                    startup.continuation = nil
                    continuation.resume(throwing: CancellationError())
                }
            default:
                break
            }
        }
        try await withCheckedThrowingContinuation { continuation in
            startup.continuation = continuation
            listener.start(queue: DispatchQueue(label: "MAC_AGENT_QUEUE_LABEL_PLACEHOLDER.identity"))
        }
    }
}
