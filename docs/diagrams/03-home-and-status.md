# Home And Status

## Role Of The Home Screen

The Home screen answers three questions immediately:

1. Am I connected to the correct vehicle WiFi?
2. Is the system ready to use?
3. What can I do now?

The screen should not expose technical connection steps. Internally, the app performs several checks; the user sees a single understandable readiness state.

## Recommended Content Hierarchy

```mermaid
flowchart TD
    home["Home screen"] --> connection["Connection summary"]
    connection --> wifiName["Current vehicle WiFi network"]
    connection --> eRelease["E-Release"]
    connection --> status["Readiness, progress, or error message"]

    home --> capture["CAPTURE_TOOL_PLACEHOLDER Capture controls"]
    capture --> start["Start CAPTURE_TOOL_PLACEHOLDER Capture"]
    capture --> stop["Stop CAPTURE_TOOL_PLACEHOLDER Capture"]

    home --> usb["USB actions"]
    usb --> traces["Export Traces to USB"]
    usb --> captureToolPlaceholderFiles["Export CAPTURE_TOOL_PLACEHOLDER Captures"]
    usb --> files["Show USB Files"]

    home --> settings["Settings access"]
```

## Connection Summary States

```mermaid
stateDiagram-v2
    [*] --> NotConnected

    NotConnected: WiFi: Not connected
    NotConnected --> Checking: Phone joins vehicle WiFi

    Checking: WiFi: Connected network name
    Checking --> Ready: System is ready
    Checking --> ConnectionError: System cannot be used

    Ready: WiFi: Connected network name
    Ready --> Checking: Network changes

    ConnectionError: WiFi: Connected network name or unavailable
    ConnectionError --> Checking: Retry or network changes
    ConnectionError --> NotConnected: WiFi disconnects
```

## Status And Error Rules

Place the status area directly below the WiFi network and E-Release. This is the first place a technician looks when an action is unavailable.

| State | Color | Meaning | Design requirement |
|---|---|---|---|
| Ready | Green | The relevant workflow can continue. | Keep it concise; do not dominate the screen. |
| In progress | Neutral or brand color | The app is checking or processing. | Show a visible progress indicator and plain-language label. |
| Mac-related issue | Yellow / amber | The vehicle connection is usable, but an CAPTURE_TOOL_PLACEHOLDER/Mac/MobileDevicePlaceholder step needs attention. | Explain the user action needed. |
| Any other issue | Red | A required connection or action failed. | Explain what happened and give a next step. |

Examples of appropriate copy:

- Green: `Ready to export traces.`
- Neutral: `Checking vehicle connection...`
- Yellow: `No MobileDevicePlaceholder found. Check the cable, unlock the MobileDevicePlaceholder, and confirm trust.`
- Red: `The vehicle connection is not ready. Check the selected WiFi network and try again.`
- Red: `E-Release could not be read.`

Do not show internal error names, command output, or protocol terminology in these messages.

## CAPTURE_TOOL_PLACEHOLDER Button Availability

Both buttons must always be visible. Their enabled states are exact opposites.

```mermaid
stateDiagram-v2
    [*] --> ReadyToStart
    ReadyToStart: Start enabled
    ReadyToStart --> Preparing: User starts capture

    Preparing: Start disabled
    Preparing --> Capturing: Capture begins
    Preparing --> ReadyToStart: User cancels or preparation fails

    Capturing: Start disabled
    Capturing --> Stopping: User chooses Stop CAPTURE_TOOL_PLACEHOLDER Capture

    Stopping: Start disabled
    Stopping --> ReadyToStart: Capture saved or stop result shown
```

Stopping should show a confirmation dialog because it ends the active recording. The dialog must offer a clear choice to keep recording or stop the capture.
