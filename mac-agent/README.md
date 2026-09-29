# TraceMate Mac Agent

Current operator and architecture documentation is maintained at [docs/mac-agent.md](../docs/mac-agent.md), [docs/api-contracts.md](../docs/api-contracts.md), and [docs/operations.md](../docs/operations.md). This package-local README is a developer reference.

The Mac agent owns Apple CAPTURE_TOOL_PLACEHOLDER CAPTURE_SESSION_PLACEHOLDER captures independently of Android SSH sessions. Android communicates over SSH by invoking the local CLI; the CLI communicates with the loopback-only agent service. Android must not connect directly to the RPC port.

When running, the agent also advertises `MAC_AGENT_DISCOVERY_NAME_PLACEHOLDER.MAC_AGENT_DISCOVERY_FQDN_PLACEHOLDER` over Bonjour. Its advertised TCP endpoint returns a newline-terminated JSON identity (`id`, `protocolVersion`) and exists only for Android to identify the Mac on its Ethernet tether. The CAPTURE_TOOL_PLACEHOLDER RPC service remains loopback-only.

## Architecture

```text
Android -- Ethernet / SSH --> tracemate-agent CLI -- localhost RPC --> CAPTURE_TOOL_PLACEHOLDER
Android -- Ethernet / SCP --> completed .capture_tool_placeholder capture
Android -- Wi-Fi --> Headunit and USB-export workflow
```

The Mac has no direct Headunit route. Android performs store-and-forward after downloading a completed capture.

## Requirements

- macOS 13 or later.
- A normal dedicated CAPTURE_TOOL_PLACEHOLDER macOS user, logged into a graphical session. Do not run the agent as root.
- Apple CAPTURE_TOOL_PLACEHOLDER installed at `CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER`, or set `TRACEMATE_CAPTURE_TOOL_PLACEHOLDER_PATH`.
- An MobileDevicePlaceholder connected through USB, unlocked, trusted, and prepared for CAPTURE_TOOL_PLACEHOLDER capture.

## Build And Test

Run on a Mac from this directory:

```bash
swift build
swift test
```

The CAPTURE_TOOL_PLACEHOLDER fixture tests run only when CAPTURE_TOOL_PLACEHOLDER and a local capture-fixture directory are available. They are skipped elsewhere. Add portable unit tests for all new behavior; do not make normal development depend on private capture files.

## Development Run

```bash
swift run tracemate-agent serve
```

In another shell:

```bash
swift run tracemate-agent health
swift run tracemate-agent capabilities
swift run tracemate-agent devices
swift run tracemate-agent preflight
swift run tracemate-agent jobs
swift run tracemate-agent start --deviceIdPlaceholder <MOBILE_DEVICE_PLACEHOLDER_DEVICE_ID_PLACEHOLDER> --name CaptureSessionPlaceholder_Wireless --requestId <REQUEST_ID>
swift run tracemate-agent status --job <JOB_UUID>
swift run tracemate-agent stop --job <JOB_UUID>
swift run tracemate-agent validate /path/to/capture.capture_tool_placeholder
```

Use `TRACEMATE_ROOT=/temporary/path` during development. Production captures are saved to `~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/` so Android can discover them for export. Agent state, validation exports, and logs remain under `~/Library/Application Support/TraceMate/` and are owner-restricted.

## CLI Contract

The service listens only on `MAC_AGENT_RPC_ENDPOINT_PLACEHOLDER`. The CLI sends newline-delimited JSON over this private TCP connection, with a 10-second control-command deadline. Commands print JSON to stdout and errors to stderr.

| Command | Purpose |
| --- | --- |
| `health` | Reports agent version and CAPTURE_TOOL_PLACEHOLDER availability. |
| `capabilities` | Reports supported transports and local agent capabilities. |
| `devices` | Returns CAPTURE_TOOL_PLACEHOLDER Wi-Fi and Bluetooth device-list output as line arrays. |
| `preflight` | Reports CAPTURE_TOOL_PLACEHOLDER availability, free space, active-job state, and discovered devices. |
| `jobs` | Lists persisted capture jobs for recovery. |
| `start --deviceIdPlaceholder ID [--name NAME] [--requestId ID]` | Starts one managed capture. The optional request ID returns the original persisted job on a retry. |
| `status --job UUID` | Returns state, activity, paths, and validation outcome. |
| `stop --job UUID` | Explicitly requests CAPTURE_TOOL_PLACEHOLDER stop; escalation occurs only for the exact owned process after the grace period. |
| `validate FILE` | Engineering diagnostic for an existing capture. |

Android invokes an absolute installed CLI path, parses JSON, binds Mac SSH/SCP sockets to Ethernet, and treats a lost start response as unknown until reconciled with `jobs`. SSH host-key pinning is not currently implemented; see [Limitations](../docs/limitations.md).

`sh scripts/install-release.sh` builds and installs the release binary and LaunchAgent for the current user. `sh scripts/status.sh` prints the LaunchAgent, health, and persisted jobs. Both must be run locally as the dedicated CAPTURE_TOOL_PLACEHOLDER user. The first service startup creates the stable discovery UUID at `~/Library/Application Support/TraceMate/identity.json`; do not remove it unless intentionally reprovisioning the Mac.

## Capture Semantics

Only one TraceMate-managed job may be active or unresolved at once. CAPTURE_TOOL_PLACEHOLDER stdout/stderr are retained per job. The agent monitors capture growth and complete live CaptureSessionPlaceholder NDJSON records without repeatedly rereading the entire file.

Job states: `STARTING`, `RUNNING`, `STOP_REQUESTED`, `STOPPING`, `VERIFYING`, `COMPLETED`, `FAILED`, `UNKNOWN`.

Activity states: `WAITING_FOR_ACTIVITY`, `ACTIVE`, `IDLE`, `NO_CAPTURE_SESSION_PLACEHOLDER_ACTIVITY`, `STOPPED`.

File growth is an activity signal only. A capture is `COMPLETED` only after CAPTURE_TOOL_PLACEHOLDER exits successfully and this command yields at least one event:

```bash
capture_tool_placeholder export -i capture.capture_tool_placeholder -o capture_session_placeholder-session.json --transport=wifi --layer="CaptureSessionPlaceholder Session"
```

An `EMPTY` or failed validation result preserves the original capture for diagnostics. Android must not declare a timeout or idle period to be a failed capture, and must not transfer while a job is `STOPPING` or `VERIFYING`.

## Recovery And Security

- A restarted agent marks formerly active jobs `UNKNOWN`, since it cannot recover CAPTURE_TOOL_PLACEHOLDER stdin ownership. Unknown jobs block new starts until an operator reconciles them.
- Android restores the stored job ID and queries `status` or `jobs` after UI recreation, process death, or Ethernet recovery. It never silently starts a replacement capture.
- Restrict macOS Remote Login to the CAPTURE_TOOL_PLACEHOLDER user, pin the SSH host key on Android, and never put credentials, keys, or full DEVICE_ID_PLACEHOLDERs in logs.
- The agent accepts only validated DEVICE_ID_PLACEHOLDERs and server-generated paths. Do not add arbitrary shell or filesystem commands.
- Keep the RPC listener on loopback. The dedicated-user deployment model is part of the local authorization boundary.

## Production Deployment

For complete first-time setup of a monitorless Mac, see
[InitialHeadlessSetup.md](docs/InitialHeadlessSetup.md). It covers the
dedicated CAPTURE_TOOL_PLACEHOLDER user, automatic-login/FileVault tradeoff, restricted Remote
Login, CAPTURE_TOOL_PLACEHOLDER/MobileDevicePlaceholder permissions, agent installation, LaunchAgent installation,
AC power settings, Android verification, and reboot testing.

Do not launch `tracemate-agent serve &` from an ordinary SSH command. Use the supplied per-user LaunchAgent with the label `MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER`; the Android app can explicitly issue `launchctl kickstart -k gui/$(id -u)/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER` after a failed health check.

## Backlog

### Required Before Operational Deployment

- [x] Add CAPTURE_TOOL_PLACEHOLDER Wi-Fi/Bluetooth device discovery and readiness/preflight commands.
- [x] Confirm CAPTURE_TOOL_PLACEHOLDER startup readiness before reporting `RUNNING`.
- [x] Add source metadata, free-space checks, SHA-256, bounded log rotation, and resource-conflict diagnostics.
- [x] Add capture-start idempotency and capability/version discovery.
- [x] Add release-install and LaunchAgent installation/status tooling.
- [x] Add an active-capture power assertion, such as `caffeinate`.

The remaining portable-test, hardware, operator, Android-integration, and acceptance work is tracked in
[MacAgentManualTodo.md](../docs/MacAgentManualTodo.md).

### Android Integration

The current Android implementation covers Ethernet-bound Mac SSH/SCP, persisted capture
recovery, start/status/stop UI, staged SCP, and USB forwarding. Remaining contract and
deployment validation is tracked in [MacAgentManualTodo.md](../docs/MacAgentManualTodo.md).

## Acceptance Checks

The required physical acceptance checks are tracked in
[MacAgentManualTodo.md](../docs/MacAgentManualTodo.md).
