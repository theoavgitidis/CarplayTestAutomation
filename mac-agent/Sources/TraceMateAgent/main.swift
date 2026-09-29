import Foundation
import TraceMateCore

@main
enum TraceMateAgentMain {
    static func main() async {
        do {
            var configuration = AgentConfiguration.production()
            if let root = ProcessInfo.processInfo.environment["TRACEMATE_ROOT"] {
                configuration.root = URL(fileURLWithPath: root, isDirectory: true)
            }
            if let capture_tool_placeholder = ProcessInfo.processInfo.environment["TRACEMATE_CAPTURE_TOOL_PLACEHOLDER_PATH"] {
                configuration.captureToolPlaceholderPath = capture_tool_placeholder
            }

            let arguments = Array(CommandLine.arguments.dropFirst())
            guard let command = arguments.first else { throw CLIError.usage }
            if command == "serve" {
                let controller = try CaptureController(configuration: configuration)
                let server = try RPCServer(controller: controller, configuration: configuration)
                let identity = try TraceMateIdentity.loadOrCreate(root: configuration.root)
                let identityServer = try IdentityServer(identity: identity)
                try await identityServer.start()
                print("TraceMate agent listening on \(configuration.host):\(configuration.port)")
                print("TraceMate discovery identity: \(identity.id.uuidString)")
                try await server.run(keepingAlive: identityServer)
                return
            }

            if command == "validate" {
                guard arguments.count >= 2 else { throw CLIError.usage }
                let capture = URL(fileURLWithPath: arguments[1])
                let output = FileManager.default.temporaryDirectory.appendingPathComponent("tracemate-\(UUID().uuidString).json")
                let result = CaptureSessionPlaceholderValidator(captureToolPlaceholderPath: configuration.captureToolPlaceholderPath).validate(capture: capture, output: output)
                try printJSON(result)
                return
            }

            let request = try makeRequest(arguments)
            let response = try await RPCClient(host: configuration.host, port: configuration.port).send(request)
            try printJSON(response)
            if !response.ok { exit(1) }
        } catch {
            FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
            exit(2)
        }
    }

    private static func makeRequest(_ arguments: [String]) throws -> RPCRequest {
        guard let command = arguments.first else { throw CLIError.usage }
        var values: [String: String] = [:]
        var index = 1
        while index < arguments.count {
            let key = arguments[index]
            guard key.hasPrefix("--"), index + 1 < arguments.count else { throw CLIError.usage }
            values[String(key.dropFirst(2))] = arguments[index + 1]
            index += 2
        }
        return RPCRequest(command: command, arguments: values)
    }

    private static func printJSON<T: Encodable>(_ value: T) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .iso8601
        print(String(decoding: try encoder.encode(value), as: UTF8.self))
    }
}

enum CLIError: LocalizedError {
    case usage
    var errorDescription: String? {
        "Usage: tracemate-agent serve | health | jobs | start --deviceIdPlaceholder ID [--name NAME] | status --job UUID | stop --job UUID | validate FILE"
    }
}
