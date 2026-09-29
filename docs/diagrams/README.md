# TraceMate UI Redesign Handoff

> **Design proposal, not implementation documentation.** These diagrams describe an intended future user experience. For implemented behavior and workflows, use the current [documentation index](../../README.md).

## Purpose

TraceMate is an Android app that guides a technician through collecting diagnostic material from a vehicle head unit. The new UI should make this feel like a clear, guided workflow rather than a collection of technical tools.

This folder is the design starting point. It describes the intended user experience, not the technical implementation behind it.

## Product In One Sentence

Connect the phone to the vehicle WiFi, confirm that the system is ready, optionally record an CAPTURE_TOOL_PLACEHOLDER capture, and export traces or captures to a USB stick.

## Read These In Order

1. [01-system-overview.md](01-system-overview.md) explains the people, devices, and high-level purpose.
2. [02-navigation-and-journeys.md](02-navigation-and-journeys.md) shows the app structure and primary routes.
3. [03-home-and-status.md](03-home-and-status.md) defines the most important screen and its status rules.
4. [04-capture_tool_placeholder-capture.md](04-capture_tool_placeholder-capture.md) describes the guided MobileDevicePlaceholder/CaptureSessionPlaceholder capture journey.
5. [05-usb-export.md](05-usb-export.md) describes trace export, CAPTURE_TOOL_PLACEHOLDER export, and USB file management.

## Design Direction

- The user should see one understandable task at a time.
- The app may perform connection checks automatically, but must describe them in plain language.
- Technical names, command output, logs, IP addresses, protocol names, and internal diagnostics do not belong in the normal UI.
- Every state-changing action must show visible progress, success, or an actionable failure message.
- A user must deliberately start or stop capture and export operations.

## Important UI Change

The existing app contains developer and diagnostic controls. They are not part of the future product UI and must not be designed:

- All logs
- SSH Test
- Prepare ADB Firewall
- ADB TCP Test
- ADB Handshake Test
- ADB Shell Test
- Ethernet-Tethering pruefen
- Ethernet-Tethering oeffnen
- Mac SSH Test
- Check Agent
- Start Agent
- Stop Active Mac CAPTURE_TOOL_PLACEHOLDER Capture

The system can still make the necessary checks automatically. The redesigned UI should only tell the user whether the system is ready and what they need to do next.
