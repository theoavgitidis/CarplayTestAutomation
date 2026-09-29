# Navigation And Primary Journeys

## App Navigation

Home is the operational center of the app. WiFi Setup and Settings are top-level destinations. Export and file-management pages are reached from Home because they are only useful when the vehicle connection is ready.

```mermaid
flowchart LR
    Home[Home] <--> Wifi[WiFi Setup]
    Home <--> Settings[Settings]
    Home --> TraceExport[Export Traces to USB]
    Home --> CaptureToolPlaceholderCapture[CAPTURE_TOOL_PLACEHOLDER Capture flow]
    Home --> CaptureToolPlaceholderExport[Export CAPTURE_TOOL_PLACEHOLDER Captures]
    Home --> UsbFiles[Files on USB]

    TraceExport --> Home
    CaptureToolPlaceholderCapture --> Home
    CaptureToolPlaceholderExport --> Home
    UsbFiles --> Home
```

## First-Time Or Reconnection Journey

```mermaid
flowchart TD
    Start[Open TraceMate] --> Connected{Connected to vehicle WiFi?}
    Connected -->|No| Wifi[Open WiFi Setup]
    Wifi --> Scan[Scan vehicle WiFi QR code]
    Scan --> Join[Phone joins vehicle WiFi]
    Join --> Home[Return to Home]

    Connected -->|Yes| Home
    Home --> Check[App checks whether the vehicle connection is ready]
    Check --> Ready{Ready?}
    Ready -->|Yes| Details[Show WiFi network and E-Release]
    Details --> Actions[Enable main actions]
    Ready -->|No| Error[Show clear red connection error]
    Error --> Wifi
```

## What Is Always Available

| Area | Availability |
|---|---|
| Home | Always available. |
| WiFi Setup | Always available. |
| Settings | Always available. |
| Start/Stop CAPTURE_TOOL_PLACEHOLDER Capture | Always visible; enabled state depends on capture state and readiness. |
| Export Traces to USB | Available only after a successful vehicle readiness check. |
| Export CAPTURE_TOOL_PLACEHOLDER Captures | Available only after a successful vehicle readiness check. |
| Files on USB | Available only after a successful vehicle readiness check. |

The bottom navigation may contain Home, WiFi Setup, and Settings. Do not make technical diagnostics or export pages permanent bottom-navigation destinations.
