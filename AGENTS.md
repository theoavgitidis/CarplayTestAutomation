# AGENTS.md

This project is an Android application written in Kotlin with Jetpack Compose. The purpose of the app is to guide and automate connection and diagnostic workflows between an Android smartphone and a vehicle head unit. The current scope includes WiFi hotspot connection via QR code, SSH-based firewall preparation, ADB connectivity checks, E-Release readout, and USB log export.

The long-term goal is to automate technical maintenance and diagnostic workflows on the head unit from the Android app. One implemented feature is copying COREDUMP trigger archives to a USB stick connected to the head unit. Other future extensions include real build-info readout (`getprop`), Disable Verity over real ADB, and potentially in-app WiFi QR scanning. Until explicit implementation instructions are given, agents should not assume final command formats, log paths, USB mount paths, or exact diagnostic procedures.

---

## Workflow order

The intended workflow is sequential. Agents must preserve this order unless the user explicitly requests a different workflow:

1. Connect the Android device to the head-unit hotspot (via native camera QR scan).
2. App auto-validates the connection: SSH → nftables firewall → ADB TCP → ADB handshake → ADB shell.
3. Copy COREDUMP archives to USB (optional).
4. Future: Disable Verity, additional ADB diagnostic commands.

---

## Architecture rules

The app follows a strict layered architecture:

```
UI (Composables) → StateFlow → ViewModels → suspend calls → Repositories / Data layer → Head unit
```

- **Composables** render state and forward user actions to ViewModels. No business logic, no network calls, no shell commands inside composables.
- **ViewModels** coordinate screen state and user actions using `StateFlow` and coroutines. Do not place network/ADB/SSH logic here directly — delegate to repositories or the coordinator.
- **Data layer** (`data/`) holds repository implementations: `AndroidSshRepository`, `AdbProtocol`, `UsbTransferRepositoryImpl`, `AndroidWifiConnectionRepository`, `SettingsRepository`.
- **Domain layer** (`domain/`) holds pure logic: models, parsers, command builders. `UsbShellCommandBuilder` is the single source of all ADB shell command strings. Do not construct ADB shell strings inline in ViewModels or repositories.
- **No DI framework.** Dependencies are wired manually in `ViewModelProvider.Factory` subclasses. `ServiceLocator` is used for the three application-scoped singletons: `WifiConnectionRepository`, `UsbTransferRepository`, `UsbTransferCoordinator`, and `TransferSnapshotStore`.

New ADB-related logic belongs in `data/adb`. New SSH-related logic belongs in `data/ssh`. Pure parsing logic belongs in `domain/`.

---

## State pattern

Every manual test button follows the same sealed-interface pattern — copy it exactly for new ones:

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

All manual diagnostic buttons (SSH Test, Prepare ADB Firewall, ADB TCP Test, ADB Handshake Test, ADB Shell Test, Mac SSH Test, SSH Firewall, CAPTURE_TOOL_PLACEHOLDER Capture, etc.) are wrapped in `if (isDebugMode)` in `HomeScreen`. New test buttons must follow this pattern. `isDebugModeEnabled` flows from `SettingsRepository` → `HomeViewModel` → `HomeScreen`.

---

## USB transfer rules

- All shell commands for USB operations are built in `UsbShellCommandBuilder` (domain layer). Never construct ADB shell strings inline in ViewModels or repositories.
- The transfer runs in `DefaultUsbTransferCoordinator` (application-scoped, not ViewModel-scoped). The coordinator runs in its own `CoroutineScope` backed by `SupervisorJob`.
- Only `requestCancel()` cancels the batch — ViewModel/Activity destruction does not.
- The coordinator persists an `ActiveTransferSnapshot` to DataStore so a killed-and-restarted app can reattach to a still-running remote worker via `attachOrRecover`.
- The foreground `UsbTransferService` is started only from the user's explicit "Begin" action.
- Each archive has two variants: normal + offline-trace (`_dlt_offlinetrace` suffix). Both are transferred per archive.
- Non-infrastructure failures trigger one automatic retry per variant before reporting failure.

---

## Build and validation

Build and validation use the existing Gradle project setup.

- Run these commands from `android/`.
- Windows: `.\gradlew.bat assembleDebug`
- macOS/Linux: `./gradlew assembleDebug`
- Run unit tests: `./gradlew :app:testDebugUnitTest`
- Single test class: `./gradlew :app:testDebugUnitTest --tests "com.example.adb_connection.ui.home.MacSshTestTest"`
- Use a 120-second timeout for build and test commands; report a timeout explicitly and continue with narrower verification when feasible.

Agents must not change Gradle, Android Gradle Plugin, Kotlin, Compose BOM, or any dependency versions unless the task explicitly requires it. After changing Kotlin, Compose, manifest, or resource files, validate with the debug build command. Always state whether the build or tests were run. If they were not run, state why.

---

## Code style

- Follow standard Kotlin and Jetpack Compose conventions.
- Composables must be kept small and must not mix UI layout with business logic, network access, shell execution, or persistence.
- State must be exposed to the UI through ViewModels.
- Long-running work (SSH commands, ADB commands, network operations, firewall changes, USB transfers) must not block the main thread. Use coroutines on appropriate dispatchers.
- Do not add large refactorings unless explicitly requested.
- Do not add features, error handling, or abstractions beyond what the task requires.
- Write no comments unless the WHY is non-obvious.

---

## Security rules

- Agents must treat firewall changes, ADB access, shell commands, and log extraction as sensitive operations.
- The app must not silently execute destructive or irreversible commands without clear user action.
- Any operation that modifies head-unit state must provide visible progress, success, and failure feedback.
- Do not add hidden background behaviour that opens ports, disables security mechanisms, exports logs, or executes ADB commands without the user explicitly triggering the workflow.
- Do not concatenate untrusted user input directly into shell commands. Inputs (IP addresses, ports, paths, filenames) must be validated before use.
- The app must prefer predefined commands for known workflows instead of allowing arbitrary command execution from the UI.
- Secrets, passwords, private keys, tokens, and internal credentials must not be hardcoded into the repository (except deliberate device-specific constants documented in `developmentAssumptions.md`).

---

## Testing expectations

- For SSH: represent success, failure, timeout, authentication failure, and unreachable host states.
- For ADB: handle successful connection, connection refused, timeout, wrong IP/port, and device unauthorized.
- For USB transfer: handle missing USB, unavailable mount, permission errors, insufficient storage, command failure, and partial transfers after crash.
- Do not assume success because a command was sent — check result output.
- `AdbProtocol` has no interface and is called directly from the data layer; it cannot be mocked in unit tests. Design tests accordingly.

---

## Known open issues — do not re-introduce

- `enableAdbPort()` in `HomeViewModel` is a legacy debug button that opens port 5555 to all source IPs. `prepareAdbFirewall()` is the correct scoped implementation.
- `DebugLogger` uses a non-thread-safe `SimpleDateFormat` singleton.
- `HomeViewModel` is large (~1550 lines); refactoring is planned — do not make it larger without good reason.
- ADB port `5555` is hardcoded in three places (`HomeViewModel`, `UsbCopyViewModel`, `DefaultUsbTransferCoordinator`).

---

## Summary of agent rules

Agents working on this repository must:

1. Inspect the relevant existing files before editing.
2. Identify the smallest set of files required for the requested change and avoid unrelated modifications.
3. After finishing a change, summarize: which files changed, what behavior was added, how to test it manually, and which build/test command to run.
4. If a limitation remains, state it directly.

The most important rule: **keep the project safe, reviewable, and incremental.** Every new feature should make the workflow clearer rather than hiding complexity. UI code must remain clean, command execution must remain isolated, state must be visible, failures must be handled explicitly, and security-sensitive operations must require intentional user action.
