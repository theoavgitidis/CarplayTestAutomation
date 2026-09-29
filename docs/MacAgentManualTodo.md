# Mac Agent Manual To-Do

> **Verification checklist.** This list contains hardware and operational validation items, not a specification of currently missing features. Current implementation contracts are in [Mac agent](mac-agent.md), [API and state contracts](api-contracts.md), and [Limitations](limitations.md).

These tasks require a physical Mac, an MobileDevicePlaceholder, CAPTURE_TOOL_PLACEHOLDER, a vehicle, or an operator decision. They cannot be completed or validated reliably from source code alone.

## Mac And MobileDevicePlaceholder Setup

- Create the dedicated non-root CAPTURE_TOOL_PLACEHOLDER macOS user and restrict Remote Login to that user.
- Install CAPTURE_TOOL_PLACEHOLDER and CAPTURE_TOOL_SUPPORT_PACKAGE_PLACEHOLDER at `CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER` (or configure `TRACEMATE_CAPTURE_TOOL_PLACEHOLDER_PATH`).
- Connect, unlock, and trust the MobileDevicePlaceholder over USB.
- Install required diagnostic profiles and resolve every macOS, CAPTURE_TOOL_PLACEHOLDER, and MobileDevicePlaceholder permission prompt before headless use.
- Install the agent with `sh mac-agent/scripts/install-release.sh`, then verify it with `sh mac-agent/scripts/status.sh`.
- Configure AC power, login behavior, and FileVault versus automatic-login policy as described in `mac-agent/docs/InitialHeadlessSetup.md`.
- With the Mac fully headless, connect a previously untrusted MobileDevicePlaceholder and verify that the user can unlock it, confirm "Trust This Computer" on the MobileDevicePlaceholder only, and that CAPTURE_TOOL_PLACEHOLDER discovers it without any Mac-side input.

## CAPTURE_TOOL_PLACEHOLDER And Vehicle Validation

- Run the portable macOS test suite: `cd mac-agent && swift test`.
- Record real `capture_tool_placeholder list --transport=wifi` and `capture_tool_placeholder list --transport=bluetooth` output. Confirm the agent's line-preserving discovery response is suitable for the CAPTURE_TOOL_PLACEHOLDER version in use.
- Run a capture and confirm CAPTURE_TOOL_PLACEHOLDER remains alive through the startup-readiness interval.
- Capture representative vehicle sessions to tune `ACTIVE`, `IDLE`, and `NO_CAPTURE_SESSION_PLACEHOLDER_ACTIVITY` thresholds.
- Compare final agent validation event counts with direct `capture_tool_placeholder export` output.
- Test immediate CAPTURE_TOOL_PLACEHOLDER exit, normal stop, forced stop escalation, agent restart recovery, and a capture that contains no CaptureSessionPlaceholder activity on real hardware.
- Exercise RPC control-command deadlines against an unavailable or stalled local agent.

## Android And Network Acceptance

- Verify Android reaches the Mac over Ethernet while retaining the Headunit Wi-Fi network.
- Verify the LaunchAgent restarts the agent after an unexpected process exit without Android polling (`RunAtLoad` and `KeepAlive`).
- With the agent stopped but Mac SSH available, start an CAPTURE_TOOL_PLACEHOLDER capture from Android and verify Android restarts the agent once, reports that recovery, and starts the capture.
- With Mac SSH unavailable, start an CAPTURE_TOOL_PLACEHOLDER capture from Android and verify it reports the failure without claiming the agent was recovered.
- Verify Android SSH host-key pinning before operational deployment. The current general SSH policy must be reviewed rather than assumed secure.
- Verify a complete `.capture_tool_placeholder` SCP download to Android and USB export to the Headunit with source size and SHA-256 checks.
- Verify transfer cancellation removes Android staging files and Headunit USB partial files.
- Verify duplicate and lost-response start behavior using a persisted `requestId` once Android sends that optional argument.
- Update the Android client/UI to consume `devices`, `preflight`, and `capabilities` if those commands are intended for operator use. The current client continues to use the existing `health`, `jobs`, `start`, `status`, and `stop` contract.

## Durable CAPTURE_TOOL_PLACEHOLDER Capture Control

The managed Mac-agent contract is implemented: it persists an agent-owned job ID, capture ID, paths, PID while live, output metadata, terminal state, and validation result. Android persists the selected job ID and uses status/jobs reconciliation rather than direct PID control.

- Validate app backgrounding, Android process death, app reopen, Mac unreachable, normal stop/finalization, and completion on real hardware.
- Confirm the expected Android UI behavior for an agent-recovered `UNKNOWN` job; the source intentionally blocks a replacement start until an operator reconciles it.
- Decide whether a foreground Android capture-monitoring notification is required for multi-hour captures. The Mac-owned capture remains independent of Android process lifetime.

## Operational Decisions

- Decide the minimum free-space threshold for expected capture sizes; the agent currently defaults to 1 GiB.
- Decide the job-log retention count; the agent currently defaults to 100 logs.
- Decide whether the Android UI should expose the new `devices`, `preflight`, and `capabilities` RPC commands outside debug mode.
- Decide whether password SSH authentication remains acceptable after host-key pinning or should be replaced with a managed key.
