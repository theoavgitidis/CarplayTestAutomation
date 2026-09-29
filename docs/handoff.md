# Handoff: Eliminate Poll Loop to Fix adbd Crash (IMPLEMENTED)

> **Status:** This fix was implemented. `pollUntilDone()` was replaced with a single blocking
> `waitForCompletion` command in `UsbTransferRepositoryImpl`. `AdbProtocol.executeShellCommand`
> now accepts a configurable `readTimeoutMs` parameter (pass `0` for no timeout on the wait call).
> The cancel-watcher coroutine pattern described below is live in the codebase.
> The "Tests Currently Failing" section at the bottom is also resolved.



## Problem

`carwatchdogd` on the Headunit kills `adbd` after the second USB trigger copy.
The cause is too many ADB TCP connections. `AdbProtocol.executeShellCommand` opens a
fresh TCP socket for every call — full CNXN handshake, OPEN, data, CLSE, disconnect.
During a copy job the poll loop fires once every 2 seconds, each poll is one connection.
A second job pushes the cumulative connection count past carwatchdogd's limit and adbd
is sent SIGKILL.

## Root Cause Location

`UsbTransferRepositoryImpl.kt` — `pollUntilDone()`:

```
while (true) {
    delay(2000)
    AdbProtocol.executeShellCommand(...)   // ← one TCP connection per 2 s
    if (status == RUNNING) isWorkerAlive() // ← another TCP connection per 2 s
}
```

For a 30-second transfer that is ~30 connections just for polling.
Two sequential triggers = ~60+ connections total → adbd killed.

## Fix: Replace Poll Loop with a Single Blocking Wait Command

Instead of polling from Android, issue one ADB shell command that blocks on the
Headunit side until the worker finishes, then returns the final status line.

### New shell command (add to UsbShellCommandBuilder)

```kotlin
fun buildWaitForJobCommand(statusPath: String, cancelPath: String): String = buildString {
    // Loop entirely on the Headunit. Reads status every 2 s. Exits when status
    // is no longer RUNNING or blank. Returns the final status line as stdout.
    // Also exits immediately if the cancel file appears.
    append("while true; do ")
    append("if [ -f '$cancelPath' ]; then echo CANCELLED; exit 0; fi; ")
    append("S=\$(cat '$statusPath' 2>/dev/null); ")
    append("case \"\$S\" in ")
    append("RUNNING|'') sleep 2 ;; ")
    append("*) echo \"\$S\"; exit 0 ;; ")
    append("esac; ")
    append("done")
}
```

This is one TCP connection per job, open for the full duration of the transfer.
No more per-poll TCP connections.

### Change in UsbTransferRepositoryImpl

Replace `pollUntilDone()` with a single `awaitJobDone()` call:

```kotlin
private suspend fun awaitJobDone(
    host: String,
    port: Int,
    handle: UsbTransferHandle,
    archive: TriggerArchive,
    sessionDir: String,
    usbMount: UsbMount,
    useStrategyA: Boolean,
    cancelSignal: StateFlow<Boolean>,
    onPhase: (TransferPhase) -> Unit
): TriggerTransferResult {
    val pidPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/${handle.jobId}/pid"
    val partialDir = "$sessionDir/.${archive.stem}.partial"

    // If cancelled before the blocking call starts, cancel immediately.
    if (cancelSignal.value) {
        onPhase(TransferPhase.Stopping(archive))
        return executeCancel(host, port, pidPath, handle.cancelPath, partialDir, archive)
    }

    // Single blocking ADB call — open for the full transfer duration.
    val waitResult = AdbProtocol.executeShellCommand(
        host, port,
        UsbShellCommandBuilder.buildWaitForJobCommand(handle.statusPath, handle.cancelPath)
    )

    // If cancelled while blocked, the cancel file was written by the cancel signal
    // observer (see note below), so the wait command exits with CANCELLED.
    if (!waitResult.success) {
        return TriggerTransferResult.Failed(archive, FailureReason.ADB_COMMUNICATION_ERROR, waitResult.errorMessage)
    }

    val status = waitResult.output.trim()
    return interpretFinalStatus(status, archive, sessionDir, usbMount, useStrategyA, host, port)
}
```

### Cancel while blocked

The blocking wait command exits when the cancel file appears. The cancel signal needs
to write the cancel file via a separate ADB call while the blocking call is in-flight.
Use a `coroutineScope` with two concurrent jobs:

```kotlin
// Conceptual structure — one child blocks waiting, other watches for cancel
coroutineScope {
    val waitJob = launch { /* blocking wait command */ }
    val cancelWatcher = launch {
        cancelSignal.first { it }  // suspends until true
        // write cancel file via separate ADB call
        AdbProtocol.executeShellCommand(host, port,
            "echo CANCEL_REQUESTED > '${handle.cancelPath}'")
        waitJob.join()  // wait for blocking command to exit
    }
}
```

### SHELL_READ_TIMEOUT_MS

`AdbProtocol.executeShellCommand` has a 15-second socket read timeout
(`SHELL_READ_TIMEOUT_MS = 15000`). This will kill the blocking wait after 15 s.
The timeout must be made configurable per call, or a large value used for the wait
command. The blocking wait must not time out.

The cleanest approach: add an optional `readTimeoutMs` parameter to `executeShellCommand`
with the current 15000 default, and pass `Int.MAX_VALUE` (or 0 for no timeout) for the
wait call.

## Files to Change

| File | Change |
|---|---|
| `UsbShellCommandBuilder.kt` | Add `buildWaitForJobCommand(statusPath, cancelPath)` |
| `UsbTransferRepositoryImpl.kt` | Replace `pollUntilDone()` with `awaitJobDone()` using the blocking wait + cancel watcher pattern |
| `AdbProtocol.kt` | Make `readTimeoutMs` configurable in `executeShellCommand` (default 15000, pass 0 for no timeout for the wait call) |
| `UsbShellCommandBuilderTest.kt` | Add tests for `buildWaitForJobCommand` |

## What Not to Change

- `buildJobStartScript` — worker still runs as `setsid` background job, writes status file
- `buildCancelScript` — still used for the cancel path (write cancel file + SIGTERM + SIGKILL + rm partial)
- The status file protocol (`RUNNING`, `SUCCESS:path`, `FAILED:reason`, `CANCELLED`, `ALREADY_PRESENT:path`)
- `AdbProtocol` wire protocol — no session reuse needed, just no timeout on the wait call

## Connection Count After Fix

| Operation | Before | After |
|---|---|---|
| discover archives | 1 | 1 |
| detect USB mount | 1 | 1 |
| check duplicate | 1 | 1 |
| check strategy | 1 | 1 |
| launch job | 1 | 1 |
| wait for completion | N (one per 2 s) | 1 |
| cancel (if triggered) | 1 | 1 |
| sync after transfer | 1 | 1 |
| **Total per trigger** | **7 + N** | **7 or 8** |

For 10 triggers × 30 s each at 2 s poll = 7 + 15 = 22 connections each = 220 total.
After fix: 10 × 7 = 70 connections total, regardless of transfer duration.

## Tests Currently Failing (Pre-existing, Unrelated)

```
UsbFileListingRepositoryTest > detectUsbMountCommand is shared with copy feature
UsbShellCommandBuilderTest > detectUsbMountCommand picks first result
UsbShellCommandBuilderTest > detectUsbMountCommand checks writable
```

These fail because `detectUsbMountCommand` was changed to use a write-probe approach
but the tests still expect `-writable` and `head -n 1`. Fix these separately or update
the tests to match the current implementation.
