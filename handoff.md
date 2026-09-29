# TraceMate Handoff

> **Historical handoff.** This file records an implementation transition and includes superseded direct-Mac CAPTURE_TOOL_PLACEHOLDER behavior. Current source-backed documentation is available from the [README](README.md). Do not follow direct CAPTURE_TOOL_PLACEHOLDER/PID/`pkill` instructions in this document.

## Implemented Connectivity Foundation

- `AndroidWifiConnectionRepository` tracks two independent Android `Network` objects:
  - `headunitWifiNetwork` for `TRANSPORT_WIFI`
  - `macEthernetNetwork` for `TRANSPORT_ETHERNET`
- `ConnectivityState` exposes independent Headunit Wi-Fi and Mac Ethernet states.
- The repository does not use `ConnectivityManager.activeNetwork` or process-wide
  `bindProcessToNetwork()`.

## Settings

- Headunit settings are separate from Mac LAN settings.
- Headunit: IP, SSH port, user, encrypted password.
- Mac LAN: `macLanIp`, `macSshPort` (default 22), `macSshUser`, encrypted
  `macSshPassword`.
- Passwords are encrypted with the existing Android Keystore AES/GCM mechanism and
  separate SharedPreferences keys.
- Legacy DataStore key `mac_ssh_ip` remains a read fallback for `macLanIp` so updates
  keep an existing Mac IP.

## Network-Bound SSH

- `SshTarget` defines `HEADUNIT` and `MAC`.
- `AndroidNetworkSocketFactory` delegates JSch socket creation to
  `Network.socketFactory.createSocket(host, port)`.
- `AndroidSshRepository.executeCommand(target, ...)` obtains a network from
  `SshNetworkProvider`; if unavailable, it returns `SshNetworkUnavailableException`
  and never falls back to Android's default route.
- `AndroidWifiConnectionRepository` implements `SshNetworkProvider`:
  - `HEADUNIT` maps only to `headunitWifiNetwork`.
  - `MAC` maps only to `macEthernetNetwork`.
- Normal Headunit SSH operations in `HomeViewModel` now use `SshTarget.HEADUNIT`.
- Direct Mac operations use the Mac LAN settings and Ethernet-bound `SshTarget.MAC`:
  - reads `macLanIp`, `macSshPort`, `macSshUser`, and `macSshPassword`.
  - `Mac SSH Test` executes `sw_vers` and validates `ProductName` and `ProductVersion`.
  - CAPTURE_TOOL_PLACEHOLDER device discovery executes both `capture_tool_placeholder list --transport=wifi` and
    `capture_tool_placeholder list --transport=bluetooth` directly on the Mac.
  - CAPTURE_TOOL_PLACEHOLDER capture start, PID-based SIGINT stop, fallback `pkill` stop, and capture-file
    listing use direct Mac LAN SSH.
  - Logs identify the Mac LAN IP, configured port, and Ethernet transport.
  - These operations perform no Headunit firewall or relay checks.

## SCP Binary Transfer API

- `ScpRepository` is a separate binary transfer abstraction; `SshRepository` remains
  responsible only for shell commands.
- `download(target, remotePath, localFile, onProgress)` and
  `upload(target, localFile, remotePath, onProgress)` return a `Result` containing
  the transferred byte count and local file.
- `AndroidScpRepository` uses JSch `exec` channels with `scp -f` for downloads and
  `scp -t` for uploads, never SFTP.
- It reuses target-bound `AndroidNetworkSocketFactory`, password authentication, and
  the `SshNetworkProvider`. A missing target network fails without route fallback.
- SCP file payloads, including `.capture_tool_placeholder` files, are copied as byte streams. Progress
  reports actual transferred bytes; V1 intentionally does not support resume.
- SSH connect timeout is 10 seconds. Socket inactivity during a transfer has a
  separate 30-second stall timeout; there is no fixed total transfer deadline.
- The Android-mediated CAPTURE_TOOL_PLACEHOLDER-to-USB bridge uses this API for both transfer links.

## Mac To Android SCP Download

- `MacScpDownloadRepository` is the first consumer of `ScpRepository`. It downloads a
  Mac file directly to Android over `SshTarget.MAC` and Ethernet; it does not upload
  anything to the Headunit.
- The existing `Browse CAPTURE_TOOL_PLACEHOLDER Captures` Mac-LAN workflow is the source selection UI.
  A selected `.capture_tool_placeholder` file can be downloaded to Android through the `Download selected
  capture to Android` action.
- Before transfer it obtains the source size with target-bound SSH and attempts a
  Mac `shasum -a 256`; an unavailable remote checksum does not block the download.
- The destination is app-private storage at
  `files/capture-tool-placeholder-transfer/<job-id>/<name>.ready`. SCP writes first to
  `<name>.download`, which is atomically renamed only after source/local byte counts
  and, when available, SHA-256 match.
- Available app storage is checked before SCP starts. Failure or cancellation removes
  the job directory, so no incomplete file is exposed as complete.
- `onProgress` exposes actual `transferredBytes` and `totalBytes` from SCP.

## CAPTURE_TOOL_PLACEHOLDER To USB Bridge

- `CaptureToolPlaceholderUsbBridgeRepository` combines the two independently target-bound SCP links into
  one Android-mediated path: `Mac .capture_tool_placeholder -> Android .download -> Android .ready ->
  Headunit USB .partial -> Headunit USB .capture_tool_placeholder`.
- Android is the SCP client on both links. Mac download uses `SshTarget.MAC` over
  Ethernet; Headunit upload uses `SshTarget.HEADUNIT` over Wi-Fi. There is no direct
  Mac-to-Headunit connection and no relay use.
- The existing Browse CAPTURE_TOOL_PLACEHOLDER Captures selection exposes `Bridge selected capture(s) to
  USB`. Selected files are processed sequentially in a single running job.
- States are `PREPARING`, `QUERYING_MAC_FILE`, `DOWNLOADING_FROM_MAC`,
  `VERIFYING_ANDROID_COPY`, `PREPARING_HEADUNIT_USB`, `UPLOADING_TO_HEADUNIT`,
  `VERIFYING_USB_COPY`, `FINALIZING`, `COMPLETED`, `FAILED`, and `CANCELLED`.
  The active phase is visible in the debug UI and failure messages retain its phase.
- Headunit upload targets `<usb>/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/<name>.capture_tool_placeholder.partial`. Its byte count and
  SHA-256 are checked against the verified Android ready file before an atomic `mv`
  finalizes `<name>.capture_tool_placeholder`. Failed or cancelled Headunit uploads best-effort delete the
  USB `.partial`; failed Mac downloads clean their Android `.download` job directory.
- The bridge is the only CAPTURE_TOOL_PLACEHOLDER capture export path. Other non-CAPTURE_TOOL_PLACEHOLDER Headunit trace
  functions remain unchanged.

## Intentionally Unchanged

- Headunit SSH preparation for Android Debug Bridge access on port `5555` remains
  independent of CAPTURE_TOOL_PLACEHOLDER transfer functionality.
- Mac SSH settings remain configurable for the direct Android-to-Mac Ethernet link.

## Verification

- Historical validation claim; commands are maintained in [Operations](docs/operations.md) and must be run from `android/`.
- Relevant unit tests include `ConnectivityStateTest` and `SshTargetTest`.
- `MacSshTestTest` covers `sw_vers` response validation and error-status mapping,
  including an unavailable Ethernet target network.
- `SshTargetTest` verifies that the MAC target is selected independently from the
  Headunit Wi-Fi target.
- `ScpProtocolTest` covers SCP acknowledgements, remote errors, file-header parsing,
  safe shell quoting, and unsafe filename rejection without a live SSH server.

## Next Safe Step

Validate the bridge on a physical device with both the Mac Ethernet network and the
Headunit Wi-Fi available, plus a writable Headunit USB medium. Confirm cancellation
removes Android `.download` files and Headunit USB `.partial` files.
