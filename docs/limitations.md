# Verification, Limitations, And Next Steps

## Verification Matrix

| Component | Command | Purpose | Constraint |
| --- | --- | --- | --- |
| Android | `.\gradlew.bat assembleDebug` | Debug build on Windows | Run from `android/`; requires Android SDK/Gradle environment. |
| Android | `.\gradlew.bat :app:testDebugUnitTest` | JVM unit tests on Windows | Run from `android/`; does not exercise hardware, Android runtime permissions, SSH, or ADB target. |
| Android | `./gradlew :app:testDebugUnitTest` | JVM unit tests on macOS/Linux | Run from `android/`; same limitations. |
| Mac agent | `swift build` | Compile Swift package | Requires macOS/Xcode Swift toolchain. |
| Mac agent | `swift test` | Swift tests | CAPTURE_TOOL_PLACEHOLDER fixture tests are enabled only where source-defined local prerequisites exist. |
| Mac agent | `sh scripts/status.sh` | Installed LaunchAgent health/jobs | Requires the installed agent and dedicated macOS user. |

Source tests cover Android parsers, status/state models, USB coordination, SSH/SCP helper behavior, Mac-agent persistence/identity/RPC framing, and selected CAPTURE_TOOL_PLACEHOLDER validation. They do not prove physical network reachability, actual CAPTURE_TOOL_PLACEHOLDER/MobileDevicePlaceholder capture, head-unit command compatibility, USB media behavior, or Android background behavior on a specific device.

## Confirmed Limitations

| Priority | Item | Status | Impact |
| --- | --- | --- | --- |
| High | Android SSH disables strict host-key checking | **Implemented controlled-environment compromise** | Susceptible to SSH man-in-the-middle attacks on an untrusted network. |
| High | Raw ADB lacks AUTH/RSA support | **Partially implemented** | ADB endpoints requiring authentication cannot be used. |
| High | Head-unit paths, tools, firewall chains, and ADB port are target-specific | **Implemented constraint** | Workflows require validation on each head-unit variant. |
| Medium | ADB opens a fresh socket/handshake for each operation | **Partially implemented** | Adds latency and connection churn; no persistent session. |
| Medium | Mac-agent active job after agent restart becomes `UNKNOWN` | **Implemented safety behavior** | Operator reconciliation is required; capture stdin ownership is deliberately not recovered. |
| Medium | CAPTURE_TOOL_PLACEHOLDER SCP bridge cannot resume | **Partially implemented** | Interrupted transfers must be explicitly restarted. |
| Medium | Android QR flow is system-mediated | **Partially implemented** | TraceMate observes network state but does not parse QR payloads itself. |
| Medium | `capabilities`, `devices`, and `preflight` are not a completed Android operator UI | **Partially implemented** | Agent supports them but Android primarily uses health/jobs/start/status/stop. |
| Low | Launcher label remains an older application name | **Implemented inconsistency** | Cosmetic mismatch between launcher and in-app branding. |
| Low | Debug logger uses shared `SimpleDateFormat` | **Known defect** | Concurrent logs may have incorrect timestamps. |

## Deprecated/Stale Material

- **Deprecated/stale:** Earlier documents that claim Android starts CAPTURE_TOOL_PLACEHOLDER with `nohup`, owns a PID, or uses `pkill` describe an obsolete workflow. The current source uses `MacAgentRepository` and the agent's scoped `stop` RPC.
- **Deprecated/stale:** Earlier handoff notes may describe implementation transitions or fixed test failures. They are not operational specifications.
- **Deprecated/stale:** Documents containing real-looking addresses, usernames, capture locations, or fixture paths are not safe configuration guidance. Current guides use placeholders.

## Prioritized Next Steps

1. Implement and validate SSH host-key pinning or TOFU before use outside a tightly controlled network.
2. Add ADB authentication support and an explicit connection/session lifecycle if target devices require it.
3. Perform physical acceptance tests for simultaneous Wi-Fi/Ethernet operation, CAPTURE_TOOL_PLACEHOLDER capture, USB export, cancellation, and recovery on supported hardware.
4. Decide and implement artifact retention/cleanup policy for Mac captures, exports, and Android snapshots.
5. Decide whether Android should expose Mac-agent preflight/device/capability data as an operator workflow.
6. Replace the connection popup's placeholder build information with a verified ADB query, then consider a separately reviewed Disable Verity workflow.

None of these planned items should be treated as currently available functionality.
