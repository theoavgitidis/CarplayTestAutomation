# CAPTURE_TOOL_PLACEHOLDER Capture — Implementation Documentation

> **Deprecated/stale.** This document describes the retired direct-CAPTURE_TOOL_PLACEHOLDER Android workflow with manual confirmation steps, process IDs, and direct shell control. Current code uses the managed Mac-agent CLI/RPC contract. Follow [Mac agent](../mac-agent.md), [API and state contracts](../api-contracts.md), and [Operations](../operations.md). Do not use commands or lifecycle claims below as operational guidance.

## Overview

The CAPTURE_TOOL_PLACEHOLDER Capture feature controls Apple's **CAPTURE_TOOL_PLACEHOLDER (CAPTURE_TOOL_PLACEHOLDER)** CLI on a
connected Mac via the existing Mac SSH connection. It guides the user step-by-step
through a CAPTURE_SESSION_PLACEHOLDER capture, blocking on each manual action that cannot be
automated (Bluetooth toggle, CaptureSessionPlaceholder connect, capture stop) and requiring explicit
user confirmation before proceeding.

All CAPTURE_TOOL_PLACEHOLDER buttons are hidden behind the **debug mode** switch, consistent with every
other diagnostic button in the app.

---

## Files Changed

| File | What changed |
|------|-------------|
| `ui/home/HomeViewModel.kt` | Added `CaptureToolPlaceholderCaptureStep`, `CaptureToolPlaceholderCaptureState`, `_captureToolPlaceholderCaptureState` StateFlow, `_captureToolPlaceholderConfirmation` CompletableDeferred, `startCaptureToolPlaceholderCapture()`, `confirmCaptureToolPlaceholderStep()`, `cancelCaptureToolPlaceholderCapture()`, `dismissCaptureToolPlaceholderCapture()`, `executeCaptureToolPlaceholderCapture()`, `stopCaptureToolPlaceholderCaptureOnMac()`, `waitForUserConfirmation()` |
| `ui/home/HomeScreen.kt` | Added `captureToolPlaceholderCaptureState` collection, CAPTURE_TOOL_PLACEHOLDER confirmation `AlertDialog`, "CAPTURE_TOOL_PLACEHOLDER Capture" section with "Start CAPTURE_TOOL_PLACEHOLDER Capture" button inside `if (isDebugMode)`, `CaptureToolPlaceholderCaptureStateDisplay` composable |

---

## State Model

```kotlin
enum class CaptureToolPlaceholderCaptureStep {
    IDLE,
    CONFIRM_TRUST,
    LIST_DEVICES,
    CONFIRM_BT_OFF,
    STARTING_CAPTURE,
    CAPTURE_RUNNING,
    CONFIRM_BT_ON,
    CONFIRM_CAPTURE_SESSION_PLACEHOLDER_ACTIVE,
    STOPPING_CAPTURE,
    DONE
}

sealed interface CaptureToolPlaceholderCaptureState {
    Idle
    WaitingForUserConfirmation(step, message, logs)
    Running(step, logs)
    Success(logs)
    Error(message, logs)
}
```

`WaitingForUserConfirmation` is the terminal state during a blocked step — the
coroutine is suspended and only resumes when the user taps **Confirmed** or
**Cancel** in the dialog.

---

## Workflow

The `executeCaptureToolPlaceholderCapture()` coroutine runs the following steps in order. Every
manual step suspends via `waitForUserConfirmation()` which sets the state to
`WaitingForUserConfirmation` and awaits a `CompletableDeferred<Boolean>`. Tapping
"Confirmed" completes it `true`; tapping "Cancel" completes it `false` and aborts.

```
1. CONFIRM_TRUST
   ├─ Manual: Prerequisites dialog
   │    • MobileDevicePlaceholder connected by USB/Lightning
   │    • MobileDevicePlaceholder unlocked and "Trust This Computer" confirmed
   │    • MOBILE_DIAGNOSTIC_PROFILE_PLACEHOLDER Mode + Bluetooth Logging profiles installed
   │    • CAPTURE_TOOL_SUPPORT_PACKAGE_PLACEHOLDER installed on the Mac
   └─ User must confirm before proceeding

2. LIST_DEVICES  (automated)
   ├─ SSH to Mac: capture_tool_placeholder list --transport=wifi
   ├─ SSH to Mac: capture_tool_placeholder list --transport=bluetooth
   ├─ Parse DEVICE_ID_PLACEHOLDER from output (hex pattern match)
   └─ Error if no DEVICE_ID_PLACEHOLDER found

3. CONFIRM_BT_OFF
   ├─ Manual: "Turn Bluetooth OFF on the MobileDevicePlaceholder"
   └─ User confirms

4. STARTING_CAPTURE  (automated)
   ├─ SSH to Mac:
   │    mkdir -p ~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER
   │    nohup capture_tool_placeholder start -o <timestamped.capture_tool_placeholder>
   │      --transport=wifi,bluetooth
   │      --protocol=capture_session_placeholder,iap2
   │      --deviceIdPlaceholder=<DEVICE_ID_PLACEHOLDER>
   │      -v
   │    > /tmp/capture_tool_placeholder_capture.log 2>&1 &
   │    echo CAPTURE_TOOL_PLACEHOLDER_PID=$!
   └─ PID captured from stdout for later SIGINT

5. CAPTURE_RUNNING → CONFIRM_BT_ON
   ├─ Manual: "Turn Bluetooth ON + connect to vehicle + start CaptureSessionPlaceholder"
   └─ User confirms

6. CONFIRM_CAPTURE_SESSION_PLACEHOLDER_ACTIVE
   ├─ Manual: "Confirm CAPTURE_SESSION_PLACEHOLDER is active"
   └─ User confirms

7. STOPPING_CAPTURE  (automated)
   ├─ SSH to Mac: kill -INT <PID>
   │    (fallback: pkill -INT -f 'capture_tool_placeholder start')
   └─ Success state set; file at ~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/
```

---

## Suspend/Resume Pattern

```kotlin
// Suspend the coroutine until the user confirms or cancels
private suspend fun waitForUserConfirmation(
    step: CaptureToolPlaceholderCaptureStep, message: String, logs: List<String>
): Boolean {
    val deferred = CompletableDeferred<Boolean>()
    _captureToolPlaceholderConfirmation = deferred
    _captureToolPlaceholderCaptureState.value = WaitingForUserConfirmation(step, message, logs)
    val confirmed = deferred.await()   // coroutine suspends here
    _captureToolPlaceholderConfirmation = null
    return confirmed
}

// Called by the UI "Confirmed" button
fun confirmCaptureToolPlaceholderStep() { _captureToolPlaceholderConfirmation?.complete(true) }

// Called by the UI "Cancel" button or dialog dismiss
fun cancelCaptureToolPlaceholderCapture() {
    _captureToolPlaceholderConfirmation?.complete(false)
    _captureToolPlaceholderCaptureState.value = Idle
}
```

This means the entire multi-step workflow is a single `viewModelScope.launch` block
with no timers, polling, or callback chains — each manual step simply `await()`s
the user.

---

## SSH Connection

CAPTURE_TOOL_PLACEHOLDER commands use the direct Android-to-Mac SSH connection bound to the configured
Mac Ethernet network. The Mac IP, SSH port, user name, and password are read from
the Mac LAN settings, which are also used by **Mac SSH Test**. If the address or
port is missing or invalid, the workflow fails before any SSH call.

### Ethernet Preparation

Before using **Mac SSH Test**, CAPTURE_TOOL_PLACEHOLDER capture, or CAPTURE_TOOL_PLACEHOLDER-to-USB transfer:

1. Enable **Ethernet tethering** on the Android device.
2. Connect the Mac to the Android device using the Android-compatible Ethernet adapter
   and Ethernet cable.
3. Wait for the Mac to receive its Ethernet IP address, then enter that current address
   in **Settings -> Mac LAN SSH -> Mac LAN-IP-Adresse**.
4. Keep Android connected to the Headunit hotspot over Wi-Fi at the same time. Ethernet
   is used only for direct Mac SSH/SCP; Headunit operations continue to use Wi-Fi.

Pressing **Mac SSH Test** displays this preparation checklist. The test starts only
after the user confirms that Ethernet tethering is active and the Mac is connected.

---

## CAPTURE_TOOL_PLACEHOLDER CLI Commands Used

| Command | Purpose |
|---------|---------|
| `capture_tool_placeholder list --transport=wifi` | Discover connected MobileDevicePlaceholders (Wi-Fi capture) |
| `capture_tool_placeholder list --transport=bluetooth` | Discover connected MobileDevicePlaceholders (Bluetooth capture) |
| `nohup capture_tool_placeholder start -o <file> --transport=wifi,bluetooth --protocol=capture_session_placeholder,iap2 --deviceIdPlaceholder=<DEVICE_ID_PLACEHOLDER> -v > /tmp/capture_tool_placeholder_capture.log 2>&1 & echo CAPTURE_TOOL_PLACEHOLDER_PID=$!` | Start capture in background, return PID |
| `kill -INT <PID>` | Stop capture gracefully (equivalent to `stop` + Enter in interactive mode) |
| `pkill -INT -f 'capture_tool_placeholder start'` | Fallback stop if PID was not captured |

The capture file is always saved under `~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/` with a timestamp
suffix, e.g. `CaptureSessionPlaceholder_Wireless_20260724_143022.capture_tool_placeholder`.

---

## UI

### Button

Shown inside `if (isDebugMode)` in `HomeScreen`, below the existing Mac SSH Test
button, separated by a `HorizontalDivider` and a "CAPTURE_TOOL_PLACEHOLDER Capture" section label.

```
[ Start CAPTURE_TOOL_PLACEHOLDER Capture ]   (spinner while Running or WaitingForUserConfirmation)
  <step label>
  <log block>
  [ Dismiss ]           (only on Success or Error)
```

### Confirmation Dialog

An `AlertDialog` is shown whenever `captureToolPlaceholderCaptureState is WaitingForUserConfirmation`.
The dialog title reflects the current step; the body is the instruction text passed
by the workflow. There is no auto-dismiss — the user must explicitly tap
**Confirmed** or **Cancel**.

```kotlin
confirmButton = { TextButton { Text("Confirmed") } }  → viewModel.confirmCaptureToolPlaceholderStep()
dismissButton = { TextButton { Text("Cancel") } }     → viewModel.cancelCaptureToolPlaceholderCapture()
onDismissRequest                                       → viewModel.cancelCaptureToolPlaceholderCapture()
```

---

## Known Limitations

- **Mac IP is manual** — the user must configure the Mac's IP address in Settings
  before using CAPTURE_TOOL_PLACEHOLDER Capture. If the Mac is on WLAN its IP can change between
  sessions. See `docs/CAPTURE_TOOL_PLACEHOLDER/PlansMacCom.md` for a discussion of mDNS auto-discovery
  as a future improvement.
- **LAN vs. WLAN** — auto-discovery via mDNS only works if the Mac's network
  interface is bridged into `hsbr0` on the head unit. If the Mac is on a separate
  subnet, manual IP entry is the only option. Run `bridge link show` on the head
  unit to check.
- **CAPTURE_TOOL_PLACEHOLDER PID** — the PID is parsed from `echo CAPTURE_TOOL_PLACEHOLDER_PID=$!` in the SSH output. If the
  SSH session delivers partial output the PID may be lost; the fallback
  `pkill -INT -f 'capture_tool_placeholder start'` handles this case.
- **Capture file path** — the timestamp in the filename is expanded by the Mac
  shell at launch time. The exact path is not fed back to the Android app; the
  user must look in `~/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/` on the Mac.
