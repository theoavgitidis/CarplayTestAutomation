# Android Application

## Status And Build Identity

**Implemented.** The Android Gradle project has one `:app` module. Its namespace and application ID are `com.example.adb_connection`; current source sets minimum SDK 29, target/compile SDK 36, and version `1.0`. The launcher label remains an older name, while the in-app product title is TraceMate.

## Source Layout

```text
android/app/src/main/java/com/example/adb_connection/
  data/adb/       raw ADB protocol and exit-code parsing
  data/ssh/       JSch SSH, SCP, Mac agent/discovery, CAPTURE_TOOL_PLACEHOLDER bridge
  data/usb/       head-unit USB transfer repository and snapshots
  data/wifi/      ConnectivityManager-backed Wi-Fi/Ethernet selection
  data/settings/  DataStore and Keystore-backed credentials
  domain/         models, parsers, and USB command builder
  service/        foreground USB and CAPTURE_TOOL_PLACEHOLDER-transfer services
  ui/             Compose screens, navigation, ViewModels, factories
```

`UsbShellCommandBuilder` is the single source of ADB shell strings for the COREDUMP USB workflow. The CAPTURE_TOOL_PLACEHOLDER-to-USB bridge uses SSH/SCP command construction in its dedicated SSH data layer.

## UI And State Ownership

| Screen/route | Purpose | Status |
| --- | --- | --- |
| `home` | Connection status, E-Release, capture controls, and diagnostics | **Implemented** |
| `wifi_setup` | Wi-Fi state and system-mediated QR/camera entry point | **Implemented** |
| `settings` | Head-unit and Mac connection settings, debug and navigation settings | **Implemented** |
| `usb_copy` | COREDUMP discovery, selection, export, cancel, and recovery UI | **Implemented** |
| `usb_files` | Browse-only head-unit USB export listing | **Implemented** |
| `capture_tool_placeholder_captures` | Mac-agent capture and completed-artifact workflow | **Implemented** |

The bottom bar exposes Home, Wi-Fi, and Settings unless automatic navigation is enabled. USB and CAPTURE_TOOL_PLACEHOLDER routes are reached from Home and appear as debug navigation targets in debug mode. Screen composables render `StateFlow` state; ViewModels coordinate work. Manual diagnostic actions are gated by the persisted debug-mode setting.

## Permissions And Storage

The manifest declares network-state, Wi-Fi-state/change, location, nearby-Wi-Fi-device, and foreground connected-device service permissions, in addition to Internet access. Runtime permission prompts and platform restrictions vary by Android version; the code relies on Android system networking rather than managing Wi-Fi credentials itself.

Two non-exported foreground services exist:

- `UsbTransferService` keeps a user-started COREDUMP batch visible and running.
- `CaptureToolPlaceholderTransferService` keeps a user-started Mac-to-USB artifact bridge visible and running.

Settings use DataStore. Head-unit and Mac SSH passwords use AES-GCM with an Android Keystore key and encrypted `SharedPreferences` values. CAPTURE_TOOL_PLACEHOLDER downloads are staged beneath app-private files storage and use `.download` then `.ready` names so incomplete data is not exposed as complete.

## Networking

`AndroidWifiConnectionRepository` maintains selected Wi-Fi and Ethernet `Network` objects separately. `SshTarget.HEADUNIT` maps to Wi-Fi and `SshTarget.MAC` maps to Ethernet. A missing target network is an error, not permission to fall back to the default route. SSID may be unavailable or redacted when Android location-related access is unavailable.

Mac discovery is implemented through Android mDNS parsing for `MAC_AGENT_DISCOVERY_FQDN_PLACEHOLDER` on Android 13+. Discovery validates the Mac identity endpoint and can persist the discovered address. A configured Mac address remains a recovery fallback.

## Head-Unit Workflows

### SSH and firewall

**Implemented.** JSch executes head-unit diagnostics and firewall commands with password authentication. The preferred ADB preparation checks/adds tagged nftables rules in expected head-unit chains and verifies them. Exact chains and address ranges are device-specific implementation constants and must be reviewed against the target head unit before use.

### ADB

**Partially implemented.** `AdbProtocol` implements TCP connection, `CNXN`, shell stream open/read/close, and shell exit-marker parsing. It opens a new socket for each call, supports no ADB RSA authentication, does not multiplex streams, and does not verify inbound packet checksums. The app can therefore report TCP, handshake, shell, and E-Release results but is not an ADB session manager.

E-Release retrieval is **Implemented**: it first reads the `logical_block` table schema through ADB shell, then selects an eligible `e_release` using the discovered timestamp column or an ID fallback. It displays no result if ADB or the expected head-unit database/tooling is unavailable.

### COREDUMP USB export

**Implemented.** The app discovers recognized trigger archives, detects a writable USB mount, transfers normal and offline-trace variants, and shows results. A head-unit worker is started in its own process group; it stages into `.partial` directories and atomically publishes completed output. Non-infrastructure variant failures receive one automatic retry. The app persists `ActiveTransferSnapshot` data and reattaches or resolves a remote job after restart where possible.

The workflow depends on the target head unit exposing the expected archive layout, shell tools, ADB service, and writable USB mount. It is not a general Android file-transfer feature.

### CAPTURE_TOOL_PLACEHOLDER artifact bridge

**Implemented.** Android downloads Mac `.capture_tool_placeholder` data via Ethernet-bound SCP, verifies it, then uploads it via Wi-Fi-bound SCP to the head-unit USB. It checks size and SHA-256 and atomically finalizes the destination. SCP V1 has no resume support; recovery converts an in-progress bridge snapshot to `INTERRUPTED` and requires explicit restart.

## Error Handling

UI actions use explicit idle/running/success/error state patterns. Cancellation is rethrown after state cleanup. Connection loss and unavailable target networks produce actionable failures instead of default-route fallback. COREDUMP exports classify mount, storage, tool, worker, ADB, cancellation, and already-present outcomes. See [Operations](operations.md) for symptoms and operator response.

## Known Gaps

- **Planned:** ADB RSA/AUTH and connection reuse.
- **Planned:** Real `getprop` build information in the connection popup.
- **Planned:** Disable Verity was removed because a prior implementation reported artificial success.
- **Partially implemented:** Wi-Fi QR handling invokes Android system UI and observes connection state; TraceMate does not parse QR contents itself.
- **Deprecated/stale:** Do not follow legacy documents that describe direct `nohup capture_tool_placeholder`, PID parsing, or broad `pkill` from Android. Current capture control uses the Mac agent.
