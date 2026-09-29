# Setup And Operations

## Prerequisites

| Area | Required condition | Status |
| --- | --- | --- |
| Android | Android 10/API 29 or later device; app build environment | **Implemented requirement** |
| Head unit | Authorized test target with reachable SSH and ADB service, expected shell tooling, and writable USB for export | **Environment-dependent** |
| Mac agent | macOS 13+, dedicated non-root user with graphical login, CAPTURE_TOOL_PLACEHOLDER available, SSH restricted to that user | **Environment-dependent** |
| MobileDevicePlaceholder/CAPTURE_TOOL_PLACEHOLDER | Trusted, unlocked MobileDevicePlaceholder and CAPTURE_TOOL_PLACEHOLDER permissions/profiles accepted locally | **Environment-dependent** |
| Topology | Phone has head-unit Wi-Fi and Mac Ethernet simultaneously for combined CAPTURE_TOOL_PLACEHOLDER/USB work | **Implemented design; hardware validation required** |

Do not put passwords, real addresses, DEVICE_ID_PLACEHOLDERs, or captured artifacts in shell history, tickets, or documentation.

## Build And Test

Commands below are confirmed by the repository. Run Android commands from `android/`; run Mac commands in `mac-agent` on a Mac.

| Environment | Command | Purpose |
| --- | --- | --- |
| Windows | `.\gradlew.bat assembleDebug` | Build Android debug APK |
| macOS/Linux | `./gradlew assembleDebug` | Build Android debug APK |
| Windows | `.\gradlew.bat :app:testDebugUnitTest` | Android JVM unit tests |
| macOS/Linux | `./gradlew :app:testDebugUnitTest` | Android JVM unit tests |
| macOS | `swift build` | Build Swift package |
| macOS | `swift test` | Run Swift tests |
| macOS | `sh scripts/install-release.sh` | Build/install/start current-user Mac agent |
| macOS | `sh scripts/status.sh` | Print LaunchAgent state, agent health, and jobs |

`installDebug` exists through the Android application plugin but requires an authorized Android device; use it only when such a device is attached. It is not part of source-only verification.

## Android Setup

1. Build and install the debug APK on a supported Android device.
2. In Settings, enter `<HEAD_UNIT_HOST>`, `<HEAD_UNIT_SSH_PORT>`, `<HEAD_UNIT_USER>`, and the corresponding password. Configure Mac SSH values only when using Mac features.
3. Enable debug mode only for diagnostic controls. Treat debug actions as privileged because they can alter firewall state or transfer data.
4. Use the Wi-Fi screen to invoke Android's system connection/QR flow. Grant required system permissions when prompted.
5. Confirm Wi-Fi status before relying on head-unit operations. For Mac operations, enable Android Ethernet tethering, connect the Mac, and confirm the app reports Mac Ethernet available.

## Mac-Agent Setup

Run as the dedicated CAPTURE_TOOL_PLACEHOLDER user, from `mac-agent`:

```bash
sh scripts/install-release.sh
sh scripts/status.sh
```

The installer places the executable at:

```text
$HOME/Library/Application Support/TraceMate/bin/tracemate-agent
```

It installs the per-user plist at:

```text
$HOME/Library/LaunchAgents/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER.plist
```

For a development session without installation:

```bash
TRACEMATE_ROOT=<TEMPORARY_AGENT_ROOT> swift run tracemate-agent serve
```

In another terminal, use commands such as:

```bash
swift run tracemate-agent health
swift run tracemate-agent jobs
swift run tracemate-agent start --deviceIdPlaceholder <MOBILE_DEVICE_PLACEHOLDER_DEVICE_ID_PLACEHOLDER> --requestId <REQUEST_ID>
```

Set `TRACEMATE_CAPTURE_TOOL_PLACEHOLDER_PATH=<CAPTURE_TOOL_PLACEHOLDER_EXECUTABLE>` only when the CAPTURE_TOOL_PLACEHOLDER binary is not at the source default. Do not run `serve` detached through SSH; use the supplied LaunchAgent for persistent operation.

### Stop, status, and removal

Health/status are supported through `sh scripts/status.sh`. To stop the installed current-user service, use the source-defined label and plist:

```bash
launchctl bootout "gui/$(id -u)" "$HOME/Library/LaunchAgents/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER.plist"
```

The repository does not provide an uninstall script, so removal of the installed binary, plist, state, captures, and logs is an **operator-managed cleanup** decision. Preserve artifacts needed for diagnosis before removal.

## End-To-End Operator Workflow

### Head-unit diagnostics and COREDUMP export

1. Connect Android to the authorized head-unit Wi-Fi.
2. Let automatic validation report SSH, firewall, TCP, handshake, and shell outcomes. Resolve the first failure before continuing.
3. Confirm E-Release if needed; lack of a value means the ADB/database query did not complete, not that a release is known.
4. Attach a writable USB device to the head unit.
5. Open Copy to USB, wait for archive and USB discovery, select archives, and explicitly choose Begin.
6. Keep the foreground notification visible; use Stop to request cancellation. Do not assume leaving the screen cancels work.
7. Review the terminal result and browse USB files. Eject/remount actions are available in the USB UI where the head unit supports them.

### CAPTURE_TOOL_PLACEHOLDER capture and artifact export

1. Ensure the Mac agent is installed and healthy, CAPTURE_TOOL_PLACEHOLDER is accessible, and the MobileDevicePlaceholder has completed local trust/permission setup.
2. Keep Android connected to head-unit Wi-Fi and Mac Ethernet. Use discovery or configured recovery settings to identify the Mac.
3. Start a capture from the Android CAPTURE_TOOL_PLACEHOLDER flow with a valid MobileDevicePlaceholder DEVICE_ID_PLACEHOLDER. The agent returns a persisted job ID.
4. Observe capture state. `IDLE` or `NO_CAPTURE_SESSION_PLACEHOLDER_ACTIVITY` is diagnostic status, not successful completion.
5. Request Stop explicitly. Wait for `COMPLETED`; `FAILED` retains the artifact and diagnostics for analysis.
6. Select completed capture artifacts and explicitly start the bridge. Android stages and verifies the file before sending it to head-unit USB.
7. Review bridge completion. An `INTERRUPTED` bridge requires explicit restart because SCP resume is not implemented.

## Troubleshooting

| Symptom | Grounded checks and response |
| --- | --- |
| Head-unit SSH cannot connect | Confirm selected Wi-Fi, configured host/port/user/password, head-unit SSH availability, and firewall. The app will not route this through Ethernet/default networking. |
| ADB TCP/handshake/shell fails | Confirm scoped firewall preparation and target ADB service. `AUTH` means the raw client cannot authenticate; connection reuse and RSA support are not implemented. |
| E-Release unavailable | ADB shell, `sqlite3`, expected database/table, or an eligible record may be unavailable. The app intentionally shows no value rather than guessing. |
| No writable USB | Check physical media, head-unit mount visibility, free space, and permissions. Do not manually substitute an unverified mount path in the app. |
| COREDUMP transfer halted after Wi-Fi loss | Restore Wi-Fi and allow connection validation; the coordinator may inspect/reattach to the remote worker. Other worker/mount failures finish with a visible result rather than silently retrying. |
| Mac unavailable | Confirm Ethernet selection, mDNS availability on Android 13+, or configured fallback address, then SSH availability. The agent RPC port must remain loopback-only. |
| Mac agent health fails | Run `sh scripts/status.sh` locally. The Android client can issue a scoped LaunchAgent kickstart after failed health, but it cannot fix CAPTURE_TOOL_PLACEHOLDER/MobileDevicePlaceholder permission prompts. |
| Start rejected | Inspect `jobs`; active or `UNKNOWN` jobs block a new start. Reconcile an unknown job on the Mac rather than starting a replacement. |
| CAPTURE_TOOL_PLACEHOLDER capture fails validation | Inspect the persisted job log and final validation state. `EMPTY` means CAPTURE_TOOL_PLACEHOLDER export contained no CaptureSessionPlaceholder Session events; it is not a successful capture. |
| CAPTURE_TOOL_PLACEHOLDER bridge fails/cancels | Verify both target networks remain available, storage on Android and USB, and SHA-256 tools on the head unit. Partial destinations are cleaned best effort; inspect USB before restarting. |

## Retention

The agent limits retained job logs by its configuration default. It persists job records and capture artifacts until an operator removes them. Android transfer snapshots persist across process death. Exact retention policies for capture artifacts, exports, and Android snapshots are **not implemented as configurable policies**; establish local operational retention requirements before deployment.
