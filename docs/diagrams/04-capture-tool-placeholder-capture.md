# CAPTURE_TOOL_PLACEHOLDER Capture Journey

## Purpose

An CAPTURE_TOOL_PLACEHOLDER capture records information while CAPTURE_SESSION_PLACEHOLDER is used. The technician follows the instructions on the Android phone while the MobileDevicePlaceholder is connected to a Mac.

The design should make this a calm, linear checklist. The user should only see the current instruction and the next required confirmation.

## Prerequisites The User Confirms

- The MobileDevicePlaceholder is connected to the Mac by cable.
- The MobileDevicePlaceholder is unlocked and trusts the Mac.
- The MobileDevicePlaceholder is connected to the vehicle WiFi.
- Required capture profiles/tools are already installed.

The UI does not need to explain how these tools work. It should state the prerequisite in user language and allow the user to confirm or cancel.

## Guided Capture Flow

```mermaid
flowchart TD
    start["Tap Start CAPTURE_TOOL_PLACEHOLDER Capture"] --> prerequisites["Show prerequisites checklist"]
    prerequisites --> confirmPrerequisites{"User confirms?"}
    confirmPrerequisites -->|"Cancel"| ready["Return to ready state"]
    confirmPrerequisites -->|"Confirm"| findPhone["Find connected MobileDevicePlaceholder"]

    findPhone --> phoneFound{"MobileDevicePlaceholder found?"}
    phoneFound -->|"No"| macIssue["Show yellow guidance: check cable, unlock MobileDevicePlaceholder, confirm trust"]
    macIssue --> ready
    phoneFound -->|"Yes"| bluetoothOff["Ask user to turn Bluetooth off"]

    bluetoothOff --> confirmOff{"Confirmed?"}
    confirmOff -->|"Cancel"| ready
    confirmOff -->|"Yes"| startCapture["Start capture"]

    startCapture --> bluetoothOn["Ask user to turn Bluetooth on"]
    bluetoothOn --> captureSessionPlaceholder["Ask user to confirm CAPTURE_SESSION_PLACEHOLDER is active"]
    captureSessionPlaceholder --> recording["Recording active"]

    recording --> stop["User taps Stop CAPTURE_TOOL_PLACEHOLDER Capture"]
    stop --> stopConfirm{"Stop recording?"}
    stopConfirm -->|"Keep recording"| recording
    stopConfirm -->|"Stop"| save["Stop and save capture"]
    save --> result["Show success or actionable error"]
    result --> ready
```

## Screen-State Model

```mermaid
stateDiagram-v2
    [*] --> Ready
    Ready --> Prerequisites: Start CAPTURE_TOOL_PLACEHOLDER Capture
    Prerequisites --> FindingPhone: Confirm prerequisites
    Prerequisites --> Ready: Cancel
    FindingPhone --> BluetoothOff: MobileDevicePlaceholder found
    FindingPhone --> MacGuidance: MobileDevicePlaceholder not found
    MacGuidance --> Ready
    BluetoothOff --> Starting: User confirms Bluetooth off
    BluetoothOff --> Ready: Cancel
    Starting --> BluetoothOn: Recording started
    BluetoothOn --> CaptureSessionPlaceholderConfirmation: User confirms Bluetooth on
    CaptureSessionPlaceholderConfirmation --> Recording: User confirms CaptureSessionPlaceholder active
    Recording --> Stopping: Stop CAPTURE_TOOL_PLACEHOLDER Capture confirmed
    Stopping --> Result
    Result --> Ready
```

## Design Requirements

| State | Required UI |
|---|---|
| Preparation step | Short instruction, relevant context, Confirm and Cancel actions. |
| Finding or starting | Clear progress state. Do not show technical detail. |
| MobileDevicePlaceholder/Mac problem | Yellow error below the connection summary, plus a concise recovery instruction in the capture flow. |
| Recording active | Persistent, unmistakable recording state; Start disabled and Stop enabled. |
| Stop confirmation | Explain that recording will end; offer `Keep recording` and `Stop capture`. |
| Result | State whether the capture was saved. If not, state the next action. |

Suggested progress labels:

- `Checking capture prerequisites...`
- `Looking for the connected MobileDevicePlaceholder...`
- `Starting capture...`
- `Recording active`
- `Saving capture...`
