# TraceMate

TraceMate is a development and diagnostic system for connecting an Android phone to a vehicle head unit and, optionally, a Mac running Apple CAPTURE_TOOL_PLACEHOLDER (CAPTURE_TOOL_PLACEHOLDER) for CAPTURE_SESSION_PLACEHOLDER capture. It is for controlled engineering environments, not consumer vehicle operation or production fleet management.

The Android app is the operator-facing client. It monitors two independently selected Android networks: Wi-Fi for the head unit and Ethernet for the Mac. It uses SSH, a small raw ADB (Android Debug Bridge) client, and SCP (Secure Copy Protocol) to coordinate explicit diagnostic and export actions. The Mac agent is a per-user `launchd` LaunchAgent that owns CAPTURE_TOOL_PLACEHOLDER processes and exposes a loopback-only JSON RPC service through its CLI.

**Documentation updated: 2026-09-23.** This update corrects earlier documentation that described direct Android control of CAPTURE_TOOL_PLACEHOLDER processes. Current code uses the managed Mac-agent RPC workflow and Android-mediated SCP export. Device-specific addresses, credentials, and personal paths have been removed from public documentation.

## Status

| Capability | Responsible component | Status | Entry point |
| --- | --- | --- | --- |
| Android Compose application and navigation | Android app | **Implemented** | [Android guide](docs/android-app.md) |
| Head-unit Wi-Fi / Mac Ethernet tracking | Android app | **Implemented** | [Architecture](docs/architecture.md) |
| Head-unit SSH diagnostics and scoped ADB firewall preparation | Android app | **Implemented** | [Android guide](docs/android-app.md) |
| Raw ADB TCP, handshake, shell, and E-Release readout | Android app | **Implemented** | [Android guide](docs/android-app.md) |
| Head-unit COREDUMP export to attached USB | Android app + head unit | **Implemented** | [Operations](docs/operations.md) |
| CAPTURE_TOOL_PLACEHOLDER capture lifecycle through Mac agent | Android app + Mac agent | **Implemented** | [Mac agent](docs/mac-agent.md) |
| CAPTURE_TOOL_PLACEHOLDER artifact bridge: Mac -> Android -> head-unit USB | Android app + Mac + head unit | **Implemented** | [Operations](docs/operations.md) |
| Bonjour/mDNS Mac-agent discovery | Android app + Mac agent | **Implemented** | [Architecture](docs/architecture.md) |
| Persistent reusable ADB sessions and ADB authentication | Android app | **Planned** | [Limitations](docs/limitations.md) |
| Real build-information popup and Disable Verity | Android app | **Planned** | [Limitations](docs/limitations.md) |

## Read This First

- [Architecture](docs/architecture.md): topology, control/data flows, channels, and trust boundaries.
- [Android application](docs/android-app.md): module layout, permissions, UI, state, head-unit workflows, and storage.
- [Mac agent](docs/mac-agent.md): Swift package, LaunchAgent, CAPTURE_TOOL_PLACEHOLDER lifecycle, RPC, persistence, and discovery.
- [Operations](docs/operations.md): reproducible setup, build/test commands, operator workflows, and troubleshooting.
- [API and state contracts](docs/api-contracts.md): RPC/CLI JSON, job state diagrams, and persistence formats.
- [Limitations and verification](docs/limitations.md): verified test matrix, implementation limits, and prioritized next steps.

## Glossary

| Term | Meaning |
| --- | --- |
| ADB | Android Debug Bridge. TraceMate implements a limited raw TCP wire-protocol client instead of invoking the desktop `adb` executable. |
| CAPTURE_TOOL_PLACEHOLDER | Apple CAPTURE_TOOL_PLACEHOLDER command-line tooling used by the Mac agent to capture CAPTURE_SESSION_PLACEHOLDER activity. |
| Capture job | A persisted Mac-agent record for one CAPTURE_TOOL_PLACEHOLDER process and its output, live NDJSON, logs, validation, and terminal state. |
| Head unit | The vehicle computer targeted by the Android app's SSH, ADB, and USB-export workflows. |
| NDJSON | Newline-delimited JSON: one complete JSON record per line. The agent incrementally observes CAPTURE_TOOL_PLACEHOLDER live-export records in this form. |
| SCP | Secure Copy Protocol. Android uses it over target-bound SSH sockets for CAPTURE_TOOL_PLACEHOLDER files; it does not use SFTP. |

## Repository Layout

```text
android/                 Android Gradle project; one :app module
mac-agent/               Swift Package containing TraceMateCore and tracemate-agent
docs/                    Current system documentation and retained historical notes
AGENTS.md, CLAUDE.md     Contributor/agent guidance, not operator documentation
```

Historical documents under `docs/` and `docs/CAPTURE_TOOL_PLACEHOLDER/` that carry a **Deprecated/stale** banner are retained only as superseded references. Follow the current guides above.
