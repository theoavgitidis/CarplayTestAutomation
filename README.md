# TraceMate

TraceMate is a Kotlin/Jetpack Compose Android application with a companion Swift
macOS agent for guided diagnostics in an embedded-device test environment. It
coordinates network validation, SSH-based setup checks, raw ADB connectivity,
USB artifact export, and optional capture-file transfer through explicit,
operator-triggered workflows.

The project is intentionally structured as a portfolio-safe version of a
real-world engineering tool. Vendor-specific names, credentials, paths, device
identifiers, and internal capture tooling have been replaced with placeholders.

## Why This Project Is Interesting

- Android app built with Kotlin, Jetpack Compose, Navigation, ViewModels,
  `StateFlow`, coroutines, and DataStore.
- Raw ADB protocol implementation for TCP connect, handshake, shell execution,
  and diagnostic readout without shelling out to the desktop `adb` binary.
- SSH and SCP integration from Android using target-bound network sockets, so
  Wi-Fi and Ethernet routes can be handled independently.
- Long-running USB export coordinator designed to survive app process death and
  recover or reattach to remote work.
- Swift Package macOS agent using an explicit CLI and loopback JSON RPC service
  for managed capture jobs.
- Clear separation between UI, ViewModel coordination, repositories, command
  builders, parsers, and persistence.

## Architecture

```text
Android Compose UI
  -> ViewModels with StateFlow
  -> repositories and coordinators
  -> SSH / SCP / raw ADB / DataStore
  -> embedded test target and USB storage

macOS CLI
  -> loopback JSON RPC
  -> Swift capture controller
  -> persisted jobs and validated artifacts
```

The Android app never hides sensitive work in background automation. Operations
that modify the target state, start transfers, or export files require explicit
user action and visible progress.

## Main Capabilities

| Area | Status |
| --- | --- |
| Android connection and diagnostic workflow | Implemented |
| SSH-based setup and reachability checks | Implemented |
| Raw ADB TCP, handshake, shell, and parser tests | Implemented |
| USB artifact export with recovery snapshots | Implemented |
| Swift macOS companion agent and CLI | Implemented |
| Placeholder capture-tool bridge | Implemented |
| Persistent authenticated ADB sessions | Planned |
| Production-grade SSH host-key pinning | Planned |

## Repository Layout

```text
android/       Android Gradle project and Kotlin source
mac-agent/     Swift Package for the companion macOS agent
docs/          Architecture, operations, contracts, and diagrams
```

## Build And Test

Android:

```bash
cd android
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

macOS agent:

```bash
cd mac-agent
swift build
swift test
```

Secret scanning:

```bash
gitleaks dir . --redact --no-banner --max-archive-depth 1
```

## Notes For Reviewers

This repository is a sanitized public version. Some names and operational values
are placeholders by design. The goal is to show architecture, Kotlin/Compose
implementation, Swift agent design, protocol handling, recovery logic, and
testing approach without exposing private infrastructure.

## Draft B: Short Recruiter-Friendly README

# TraceMate

TraceMate is a portfolio-safe Android and macOS diagnostic automation project.
It demonstrates how a mobile app can guide an operator through a complex
embedded-device workflow while keeping network, command execution, persistence,
and long-running transfers isolated from the UI.

## Highlights

- Kotlin Android app using Jetpack Compose, ViewModels, `StateFlow`, coroutines,
  DataStore, and Material 3.
- Layered architecture: composables render state, ViewModels coordinate actions,
  repositories perform network and persistence work, and domain classes handle
  parsing and command construction.
- Raw ADB client for selected protocol operations, including TCP reachability,
  handshake, shell execution, and diagnostic parsing.
- SSH/SCP workflows with explicit user-triggered actions and visible progress.
- Resilient USB export flow with persisted transfer snapshots and recovery
  behavior after app restarts.
- Swift macOS companion agent with CLI commands, loopback RPC, persisted jobs,
  and unit tests.

## Tech Stack

| Layer | Technology |
| --- | --- |
| Android UI | Kotlin, Jetpack Compose, Material 3 |
| Android state | ViewModel, StateFlow, coroutines |
| Persistence | DataStore, Android Keystore-backed password storage |
| Networking | Android `Network`, SSH, SCP, raw TCP ADB |
| macOS agent | Swift Package, actors, LaunchAgent-compatible CLI |
| Testing | JUnit, coroutine tests, Swift XCTest |

## Project Structure

```text
android/       Android app
mac-agent/     Swift macOS companion agent
docs/          Architecture and operations documentation
```

## Run The Checks

```bash
cd android
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest

cd ../mac-agent
swift test
```

This public version intentionally replaces sensitive vendor names, target
addresses, credentials, internal paths, and capture-tool details with
placeholders.

## Draft C: Engineering Case Study README

# TraceMate

TraceMate is a diagnostic workflow tool for an embedded-device lab setup. The
project combines an Android operator app and a Swift macOS companion agent to
coordinate network checks, command execution, artifact export, and capture-file
handling across multiple machines.

The public repository is sanitized: proprietary target details, internal
commands, credentials, device identifiers, paths, and capture-tool names have
been removed or replaced with placeholders.

## Problem

Embedded diagnostic workflows often span several devices and network paths:

- an Android phone used by the operator,
- a Wi-Fi-connected embedded target,
- a USB storage device attached to the target,
- and a macOS machine running a local capture utility.

Manual execution is easy to get wrong because failures can occur at any layer:
network selection, SSH access, firewall setup, ADB reachability, shell command
execution, USB mount discovery, file transfer, validation, or process recovery.

TraceMate turns that sequence into an explicit, state-driven workflow with
visible progress and recoverable long-running operations.

## Solution

The Android app owns the operator experience. It validates the selected network,
runs predefined diagnostics, starts transfers only after user confirmation, and
persists enough state to recover from app restarts.

The macOS agent owns local capture jobs. Android invokes the agent through SSH;
the CLI talks to a loopback-only JSON RPC service; the service starts, monitors,
validates, and persists jobs.

```text
Operator
  -> Android app
  -> selected Wi-Fi network
  -> embedded target diagnostics and USB export

Operator
  -> Android app
  -> selected Ethernet route
  -> macOS SSH CLI
  -> loopback agent RPC
  -> capture job lifecycle
```

## Implementation Details

- Compose screens remain presentation-only and forward events to ViewModels.
- ViewModels expose workflow state through `StateFlow`.
- Repositories own SSH, SCP, ADB, Wi-Fi/Ethernet, settings, and transfer logic.
- Domain classes contain parsers, models, result types, and shell command
  builders.
- Long-running transfer coordinators are application-scoped instead of tied to a
  screen lifecycle.
- Sensitive operations require explicit user actions and report success or
  failure in the UI.
- Tests cover parsers, command builders, transfer state, SCP behavior, ADB exit
  parsing, ViewModel state transitions, and Swift agent infrastructure.

## Build

```bash
cd android
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest

cd ../mac-agent
swift build
swift test
```

## What This Demonstrates

- Practical Android architecture for a stateful diagnostic tool.
- Careful handling of multiple network routes on Android.
- Protocol-level thinking through a small raw ADB client.
- Robustness patterns for long-running work, retries, cancellation, and
  recovery.
- Cross-platform design using Kotlin on Android and Swift on macOS.
- Security-conscious public release hygiene through placeholders and secret
  scanning.

## Recommended Main README Direction

For most job applications, use Draft A as the main README. It gives enough
technical depth for engineering reviewers without making the first page too
long. Draft B is better if you want a compact GitHub profile project, and Draft
C is strongest when applying to embedded, mobile infrastructure, developer
tools, or platform engineering roles.
Build And Test
Android:
cd android
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
macOS agent:
cd mac-agent
swift build
swift test
Secret scanning:
gitleaks dir . --redact --no-banner --max-archive-depth 1
Notes For Reviewers
This repository is a sanitized public version. Some names and operational values
are placeholders by design. The goal is to show architecture, Kotlin/Compose
implementation, Swift agent design, protocol handling, recovery logic, and
testing approach without exposing private infrastructure.
Draft B: Short Recruiter-Friendly README
TraceMate
TraceMate is a portfolio-safe Android and macOS diagnostic automation project.
It demonstrates how a mobile app can guide an operator through a complex
embedded-device workflow while keeping network, command execution, persistence,
and long-running transfers isolated from the UI.
Highlights
Kotlin Android app using Jetpack Compose, ViewModels, StateFlow, coroutines,
DataStore, and Material 3.
Layered architecture: composables render state, ViewModels coordinate actions,
repositories perform network and persistence work, and domain classes handle
parsing and command construction.
Raw ADB client for selected protocol operations, including TCP reachability,
handshake, shell execution, and diagnostic parsing.
SSH/SCP workflows with explicit user-triggered actions and visible progress.
Resilient USB export flow with persisted transfer snapshots and recovery
behavior after app restarts.
Swift macOS companion agent with CLI commands, loopback RPC, persisted jobs,
and unit tests.
Tech Stack
Layer	Technology
Android UI	Kotlin, Jetpack Compose, Material 3
Android state	ViewModel, StateFlow, coroutines
Persistence	DataStore, Android Keystore-backed password storage
Networking	Android Network, SSH, SCP, raw TCP ADB
macOS agent	Swift Package, actors, LaunchAgent-compatible CLI
Testing	JUnit, coroutine tests, Swift XCTest

Project Structure
android/       Android app
mac-agent/     Swift macOS companion agent
docs/          Architecture and operations documentation
Run The Checks
cd android
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest

cd ../mac-agent
swift test
This public version intentionally replaces sensitive vendor names, target
addresses, credentials, internal paths, and capture-tool details with
placeholders.
Draft C: Engineering Case Study README
TraceMate
TraceMate is a diagnostic workflow tool for an embedded-device lab setup. The
project combines an Android operator app and a Swift macOS companion agent to
coordinate network checks, command execution, artifact export, and capture-file
handling across multiple machines.
The public repository is sanitized: proprietary target details, internal
commands, credentials, device identifiers, paths, and capture-tool names have
been removed or replaced with placeholders.
Problem
Embedded diagnostic workflows often span several devices and network paths:
an Android phone used by the operator,
a Wi-Fi-connected embedded target,
a USB storage device attached to the target,
and a macOS machine running a local capture utility.
Manual execution is easy to get wrong because failures can occur at any layer:
network selection, SSH access, firewall setup, ADB reachability, shell command
execution, USB mount discovery, file transfer, validation, or process recovery.
TraceMate turns that sequence into an explicit, state-driven workflow with
visible progress and recoverable long-running operations.
Solution
The Android app owns the operator experience. It validates the selected network,
runs predefined diagnostics, starts transfers only after user confirmation, and
persists enough state to recover from app restarts.
The macOS agent owns local capture jobs. Android invokes the agent through SSH;
the CLI talks to a loopback-only JSON RPC service; the service starts, monitors,
validates, and persists jobs.
Operator
  -> Android app
  -> selected Wi-Fi network
  -> embedded target diagnostics and USB export

Operator
  -> Android app
  -> selected Ethernet route
  -> macOS SSH CLI
  -> loopback agent RPC
  -> capture job lifecycle
Implementation Details
Compose screens remain presentation-only and forward events to ViewModels.
ViewModels expose workflow state through StateFlow.
Repositories own SSH, SCP, ADB, Wi-Fi/Ethernet, settings, and transfer logic.
Domain classes contain parsers, models, result types, and shell command
builders.
Long-running transfer coordinators are application-scoped instead of tied to a
screen lifecycle.
Sensitive operations require explicit user actions and report success or
failure in the UI.
Tests cover parsers, command builders, transfer state, SCP behavior, ADB exit
parsing, ViewModel state transitions, and Swift agent infrastructure.
Build
cd android
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest

cd ../mac-agent
swift build
swift test
What This Demonstrates
Practical Android architecture for a stateful diagnostic tool.
Careful handling of multiple network routes on Android.
Protocol-level thinking through a small raw ADB client.
Robustness patterns for long-running work, retries, cancellation, and
recovery.
Cross-platform design using Kotlin on Android and Swift on macOS.
Security-conscious public release hygiene through placeholders and secret
scanning.
