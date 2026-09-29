# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew assembleDebug                  # Linux/macOS, from android/
.\gradlew.bat assembleDebug              # Windows, from android/

# Install on connected device
./gradlew installDebug                    # from android/

# All unit tests
./gradlew :app:testDebugUnitTest          # from android/

# Single test class
./gradlew :app:testDebugUnitTest --tests "com.example.adb_connection.ui.home.MacSshTestTest" # from android/
```

Always state whether the build or tests were run. If they were not, say why.

---

## Architecture

The app is a Kotlin/Compose tool for connecting an Android phone to a vehicle head unit over Wi-Fi, then automating SSH/ADB diagnostic workflows.

```
UI (Composables) → StateFlow → ViewModels → suspend calls → Repositories / Data layer → Head unit
```

**No DI framework.** Dependencies are wired manually via `ViewModelProvider.Factory` subclasses. `ServiceLocator` is used for the four application-scoped singletons: `WifiConnectionRepository`, `UsbTransferRepository`, `UsbTransferCoordinator`, and `TransferSnapshotStore`. Everything else is constructed in each factory.

**Key data-layer components:**

| Class | Responsibility |
|---|---|
| `AndroidSshRepository` | JSch password-auth SSH; 10 s connect / 15 s exec timeout; always closes session+channel in `finally`; re-throws `CancellationException` |
| `AdbProtocol` | Hand-rolled ADB wire protocol; opens a fresh TCP socket per call (no session reuse); used for shell commands, E-Release query, USB transfer status |
| `UsbTransferRepositoryImpl` | Issues ADB shell commands to launch a remote `setsid` worker script; uses a single blocking `waitForCompletion` command to await the result; cancels via `kill` on the process group |
| `DefaultUsbTransferCoordinator` | Application-scoped batch loop; owns crash-recovery snapshot; retry logic for non-infrastructure failures; runs in its own `CoroutineScope` (not tied to any ViewModel) |
| `SettingsRepository` | DataStore for booleans/strings + Android Keystore AES/GCM for the SSH password |

**Head-unit values hardcoded in the codebase** (all deliberate and target-specific):
- SSH/ADB host defaults and firewall WLAN ranges are configurable or implementation constants; do not reuse them as portable values.
- ADB port: `5555` (three separate constants — known debt)
- Archive source: `HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER`
- USB mount candidates: `HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER`, `UNSUPPORTED_USB_MOUNT_ROOT_PLACEHOLDER`, `ADDITIONAL_USB_MOUNT_ROOT_PLACEHOLDER`
- E-Release DB: `HEAD_UNIT_INVENTORY_DB_PLACEHOLDER`
- Job temp dir: `HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER`

---

## State patterns

Every manual test button follows the same pattern — copy it exactly for new ones:

```kotlin
// Sealed interface: Idle / Running / Success(...) / Error(val message: String)
// MutableStateFlow initialized to Idle
// Public fun: guard `if (state is Running) return`, then viewModelScope.launch { ... }
// try/catch(CancellationException) inside the launch body → reset to Idle, re-throw
// dismiss fun resets to Idle
// UI: LaunchedEffect(state) auto-dismiss Success/Error after N seconds
```

`MacSshTestState` in `HomeViewModel` is the most recent complete example.

---

## Debug mode

All manual diagnostic buttons (SSH Test, Prepare ADB Firewall, ADB TCP Test, ADB Handshake Test, ADB Shell Test, Mac SSH Test, SSH Firewall, CAPTURE_TOOL_PLACEHOLDER Capture, Mac Key Setup, CAPTURE_TOOL_PLACEHOLDER Copy) are wrapped in `if (isDebugMode)` in `HomeScreen`. New test buttons must follow this pattern. `isDebugModeEnabled` flows from `SettingsRepository` → `HomeViewModel` → `HomeScreen`.

---

## Settings persistence

`SettingsRepository` uses `androidx.datastore.preferences`. The SSH password is the only value encrypted (Keystore AES/GCM + SharedPreferences). All other values are plain DataStore keys. The `?: default` fallback in a DataStore flow only fires when the key is **absent** — an explicitly-stored empty string bypasses it. Validate inputs before persisting, or show `isError` on the field (see the Mac IP field in `SettingsScreen` as a reference).

---

## SSH credentials

- **Headunit SSH**: host/user/password read from `SettingsRepository`; credentials are configurable.
- **Mac SSH** (debug test only): `MAC_SSH_USER`/`MAC_SSH_PASSWORD` are `private const val` in `HomeViewModel`. Port reuses `SSH_PORT`.
- `StrictHostKeyChecking = no` is intentional for this controlled dev environment (documented in `docs/developmentAssumptions.md`).

---

## USB transfer mechanics

`UsbTransferRepositoryImpl` launches a remote shell worker via `setsid sh -c '...'` over ADB. The worker writes status to `HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/<jobId>/status`. Completion is detected by a single blocking shell command (`waitForCompletion`) that tails the status file — not a poll loop. On cancel: write a `cancel` file, then `kill -TERM -- -<PID>`, then `kill -KILL` after 2 s. Archives are staged as `.partial` directories and renamed atomically on success.

The `DefaultUsbTransferCoordinator` drives the batch loop. Each archive has two variants: normal and offline-trace (`_dlt_offlinetrace`). Non-infrastructure failures trigger one automatic retry per variant.

`ADB_COMMUNICATION_ERROR` (WiFi drop) **suspends** the batch rather than completing it: the coordinator stays `Running`, the snapshot is kept on disk with the in-flight job ID preserved, and `tileResults` for the interrupted tile is deliberately left absent so `attachOrRecover` retries it. All other infrastructure failures (USB disconnected, worker lost, etc.) complete the batch normally. On WiFi reconnect, `HomeViewModel.runAutomaticConnectionValidation` calls `attachOrRecover` after successful auto-validation.

A crash-recovery snapshot (`ActiveTransferSnapshot`) is persisted to DataStore before each launch. On process restart, `attachOrRecover` decodes the snapshot, validates the USB mount, and reattaches to or resolves the interrupted job before resuming. `tileResults` entries are sparse — only tiles with at least one recorded result appear; absent entries mean "not yet done or interrupted by WiFi loss".

After an app crash or ADB loss, `HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/` and `.partial` directories may remain on the head unit and need manual cleanup.

All shell commands for USB operations are built in `UsbShellCommandBuilder` (domain layer) — do not construct ADB shell strings inline in ViewModels or repositories.

---

## Known open issues (do not re-introduce)

- `enableAdbPort()` in `HomeViewModel` is a legacy debug button that opens port 5555 to **all** source IPs. `prepareAdbFirewall()` is the correct scoped implementation.
- `activeWifiNetworks` in `AndroidWifiConnectionRepository` is not thread-safe (plain `mutableSetOf`).
- `DebugLogger` uses a non-thread-safe `SimpleDateFormat` singleton.
- `HomeViewModel` is large (~1550 lines); refactoring is planned but not yet done — do not make it larger without good reason.
- `AdbProtocol` has no interface and is called directly by the data layer — no mock available for tests.
- ADB port `5555` is duplicated across three constants.

---

## Version constraints

Do not change AGP, Kotlin, Compose BOM, or any dependency version unless the task explicitly requires it.
