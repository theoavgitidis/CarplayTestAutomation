// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TraceMateAgent",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "TraceMateCore", targets: ["TraceMateCore"]),
        .executable(name: "tracemate-agent", targets: ["TraceMateAgent"]),
    ],
    targets: [
        .target(name: "TraceMateCore"),
        .executableTarget(name: "TraceMateAgent", dependencies: ["TraceMateCore"]),
        .testTarget(name: "TraceMateCoreTests", dependencies: ["TraceMateCore"]),
    ]
)
