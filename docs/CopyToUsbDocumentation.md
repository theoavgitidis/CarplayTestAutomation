# Copy to USB — Implementation Documentation

> **Historical implementation detail.** The current, source-backed operator guidance is [Operations](operations.md); architecture and recovery behavior are in [Android application](android-app.md) and [API and state contracts](api-contracts.md). This long-form document is retained for implementation history and should not be used to derive target-specific paths or shell commands for another head unit.

## Overview

The Copy to USB feature discovers COREDUMP trigger archives on the head unit,
lets the user select which ones to export, and transfers each selected archive
to a USB stick connected to the head unit. All filesystem work runs remotely on
the head unit via ADB shell commands; the Android phone never touches the files
directly.

A companion **Show USB Files** screen lets the user browse the exported
TraceMate directory tree without starting a transfer.

> **Architecture update (Prompt 2 — transfer ownership moved out of the ViewModel).**
> The transfer batch loop, cancellation, and remote-job recovery described below no
> longer live in `UsbCopyViewModel`. They were moved to an application-scoped
> `UsbTransferCoordinator` (`DefaultUsbTransferCoordinator`, `data.usb`) that runs in
> its own `CoroutineScope`, plus a foreground `UsbTransferService` (`data.usb`) started
> only from the user's explicit "Begin" action. This means screen-off, activity
> recreation, and ViewModel destruction no longer cancel or duplicate an in-flight
> transfer. `UsbCopyViewModel` now only discovers/selects archives and delegates
> start/cancel to the coordinator, observing its `StateFlow<UsbTransferCoordinatorState>`.
> The coordinator persists a compact `ActiveTransferSnapshot` (via
> `ActiveTransferSnapshotCodec` / `TransferSnapshotStore`) so a killed-and-recreated
> process can reattach to (or resolve) a still-running remote worker instead of
> blindly relaunching it. The shell commands, remote job lifecycle, cancellation
> script, and status/failure classification described in the rest of this document
> are unchanged — only *who* drives them changed. See "Architecture Reference" below
> for the updated class table.

---

## Source Discovery

### Source path

Archives are discovered under `HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER` at depth 1 only.

Shell command built by `UsbShellCommandBuilder.discoverArchivesCommand()`:

```
find HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER -maxdepth 1 -type f -name 'trigger_*_COREDUMP.tar.lz4' 2>/dev/null
```

### Valid archive pattern

Only files matching `trigger_*_COREDUMP.tar.lz4` are accepted. The trigger
number is extracted from the stem by `TriggerArchiveParser.parseDiscoveryOutput()`.

### What is excluded

| Excluded | Reason |
|----------|--------|
| JSON files | The `-name` filter only matches `.tar.lz4`; JSON files are never returned by the `find` command |
| Offline-trace archives | The `_COREDUMP` infix is required; offline-trace stems do not match |
| Files at depths > 1 | `-maxdepth 1` limits the search to the immediate directory |

### Tile model

Each archive found produces exactly one `TriggerArchive`:

```kotlin
data class TriggerArchive(
    val triggerNumber: Int,   // parsed from stem
    val stem: String,         // e.g. "trigger_1_HU_20260701_113733_COREDUMP"
    val archivePath: String,  // full path to the .tar.lz4 file
    val extractedDirectoryPath: String  // path to pre-extracted dir, if present
) {
    val displayLabel: String get() = "T$triggerNumber"  // "T1", "T2", …
}
```

Each archive is shown as a single tile in the `UsbCopyScreen` grid, labelled
`T1`, `T2`, and so on using `TriggerArchive.displayLabel`.

---

## Transfer Behavior

### Strategy selection

Before launching the background job,
`UsbTransferRepositoryImpl.checkStrategyA()` tests whether the pre-extracted
directory (`archive.extractedDirectoryPath`) exists:

```
[ -d '<extractedDirectoryPath>' ] && echo YES || echo NO
```

| Strategy | Condition | Action |
|----------|-----------|--------|
| **A** — copy existing directory | Pre-extracted directory present | `cp -a <srcDir>/. <partial>/` |
| **B** — extract archive | No pre-extracted directory | `lz4 -dc -- <archive> \| tar -xf` to a temp file, then extract |

In both cases the `.tar.lz4` archive itself is **not** copied. JSON files are
never sourced. Only the extracted directory content reaches the USB stick.

### Output location

All output is written under:

```
<USB_MOUNT>/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_<YYYYMMDD_HHMMSS>/
```

The session directory is created by
`UsbShellCommandBuilder.createSessionDirCommand()` before transfers begin.
`UsbTransferRepositoryImpl.createSessionDir()` returns a `UsbSessionDirResult`
(`Success(path)` / `Failed(reason, message)`) parsed from the `OK:<path>` /
`FAIL:<token>` response.

### Mount revalidation before writes

Once a USB stick is detected, its mount info can go stale — the stick may be
unplugged, swapped for another device at the same mount point, or remounted
read-only. If that mount point directory still exists on the underlying
filesystem (commonly tmpfs) after the stick is gone, a blind `mkdir`/`mv`
there would silently "succeed" while writing to internal storage instead of
the actual USB stick.

To prevent this, `UsbShellCommandBuilder` builds a single reusable shell
snippet (`mountValidationSnippet`) that re-reads `/proc/mounts` and checks the
expected device path is still mounted at the expected mount path with `rw`.
It runs immediately before every write:

1. **Session directory creation** (`createSessionDirCommand`) — before `mkdir`
2. **Worker launch** (`buildJobStartScript`) — before the job directory is created and the worker is `setsid`-started
3. **Final rename/commit** (`buildStrategyAWorker` / `buildStrategyBWorker`) — immediately before the `.partial` → final `mv`

The snippet reports one of:

| Token | Meaning |
|-------|---------|
| `USB_NOT_FOUND` | No session exists yet and the mount path is absent (used only at step 1) |
| `USB_DISCONNECTED` | A session/job is already in progress and the mount path is now absent (steps 2–3) |
| `USB_MOUNT_CHANGED` | A different device is now mounted at the same path |
| `USB_NOT_WRITABLE` | The expected device is still mounted at the expected path, but not `rw` |

These tokens flow back through the existing `FAIL:`/`ERROR:`/`FAILED:` status
conventions and are mapped to `FailureReason` values by the shared
`UsbTransferRepositoryImpl.parseFailureReason()` function, so the UI can show
a specific message instead of a generic failure. On revalidation failure
during a worker run, the `.partial` staging directory is removed before the
failure is reported — a stale mount never leaves a half-written directory
behind that could be mistaken for a valid export.

`createSessionDirCommand()` never runs `mkdir` on a mount-label directory
without first confirming the mount is live — it validates before creating
any part of the `HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_.../` path.

### cp/tar/lz4 stderr capture and failure classification

`cp`, `tar`, and `lz4` stderr is never discarded to `/dev/null`. Each worker
redirects it to a dedicated file under the job directory
(`$jobDir/cp.err` for Strategy A; `$jobDir/tar.err` and `$jobDir/lz4.err` for
Strategy B), `cat`s the file on failure — which flows into `worker.log`
because the worker runs under `setsid sh -c '...' > worker.log 2>&1 &` — and
then classifies the failure using
`UsbShellCommandBuilder.classifyDestinationFailureSnippet()`, in this order:

1. **Known destination-side stderr signatures** (`destinationErrorPatternSnippet`):
   - `"No space left on device"` → `USB_FULL`
   - `"Read-only file system"` → `USB_NOT_WRITABLE`
   - `"Input/output error"` → `USB_IO_ERROR`
2. **Live mount revalidation** (reusing `mountValidationSnippet`) — only if no
   stderr signature matched: `USB_DISCONNECTED` (mount gone) or
   `USB_MOUNT_CHANGED` (different device now at the same path).
3. **Caller-supplied default** — only if the mount is still healthy and no
   known signature matched: `CP_FAILED` for Strategy A, or (Strategy B only)
   the sentinel `_UNRESOLVED`, which triggers step 4.
4. **Archive integrity test** (Strategy B only, destination-side causes ruled
   out): runs `lz4 -t '<archivePath>'` against the *source* archive.
   - Test fails → `LZ4_DECOMPRESS_FAILED` (the archive itself is corrupt).
   - Test passes but `lz4`'s own exit code from the extraction pipeline was
     non-zero and not the benign SIGPIPE code 141 → `LZ4_DECOMPRESS_FAILED`
     (lz4 genuinely failed independently of the archive or destination).
   - Otherwise → `TAR_EXTRACT_FAILED` (a genuine tar-only failure).

A destination write failure (full disk, read-only, I/O error, disconnected,
or swapped mount) is therefore never mislabelled as archive/tool corruption —
the archive integrity test only runs once destination-side causes are ruled
out. The existing lz4 exit-code-141 (SIGPIPE-on-clean-pipe-close) exemption
is unchanged and still applies only to the pipeline's own exit-code check.

### Worker.log diagnostic enrichment

`UsbShellCommandBuilder.readWorkerLogTailCommand(jobId)` builds a
`tail -c <n> .../worker.log` command. After a failed transfer,
`UsbTransferRepositoryImpl.waitForCompletion()` makes a **best-effort**
extra ADB call to read this tail and appends the last few non-blank lines to
`TriggerTransferResult.Failed.message` via the pure, independently-testable
`appendWorkerLogDiagnostic()` function. If the read fails for any reason (ADB
error, missing log, blank output), the original failure result is returned
unchanged — this diagnostic is purely additive and never masks or replaces
the classified `FailureReason`. The pure status-token → `FailureReason`
mapping (`parseFailureReason()`/`mapStatus()`) remains unaffected and
independently testable without ADB.

### Partial-directory protocol

Each archive is written to a staging directory named
`.<stem>.partial` inside the session directory before being published:

1. Worker creates `<sessionDir>/.<stem>.partial/`
2. Files are copied or extracted into it
3. A late duplicate check runs against the HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER tree
4. On success the worker renames `.<stem>.partial` → `<sessionDir>/<stem>` via `mv`

The rename is atomic from the USB filesystem's perspective. A partial directory
is only visible to the file-listing feature if the transfer is interrupted after
creation but before renaming; `.partial` entries are filtered at the shell level
by `listTraceMateFilesCommand` and never shown to the user.

### Worker cleanup guarantee (trap/finally-style)

Both worker scripts (`buildStrategyAWorker` / `buildStrategyBWorker`) install a
shell `trap '...' EXIT` immediately after declaring their scratch-file variables,
before any other work begins:

```sh
_COMMITTED=0
trap 'rm -f "$CPERR" 2>/dev/null; if [ "$_COMMITTED" != "1" ]; then rm -rf "$PARTIAL" 2>/dev/null; fi' EXIT
```

(Strategy B's trap additionally removes `$FIFO`, `$TARERR`, `$LZ4ERR`, and `$LZ4TESTERR`.)

This guarantees, on **every** exit path — a normal `exit 0`/`exit 1` in any
branch above, or the script being terminated by an uncaught signal such as the
`SIGTERM` sent by the cancel script — that:

* The `cp.err` / `tar.err` / `lz4.err` / `lz4_test.err` scratch files and the
  Strategy B FIFO are always removed. They have already been surfaced into
  `worker.log` via `cat` before this point, so no diagnostic information is lost.
* The `.partial` staging directory is removed **unless** `_COMMITTED` was set to
  `1`, which only happens immediately after the final `mv "$PARTIAL" "$FINAL"`
  succeeds — so a completed export is never touched, and every failure path
  (including a `mv` failure itself, i.e. `MV_RENAME_FAILED`) reliably cleans up
  the partial directory without needing a bespoke `rm -rf` at that call site.

The trap is defense-in-depth on top of the existing explicit `rm -rf "$PARTIAL"`
calls in each failure branch — those remain for immediate/early cleanup and
test clarity, while the trap is the guarantee of last resort. It cannot help
against `SIGKILL` (uncatchable), which is why `buildCancelScript` performs its
own external cleanup — see "Remote process termination" below.

### Exact duplicate detection

Duplicate detection runs in two places:

1. **Pre-launch** (in `UsbTransferRepositoryImpl.transferArchive()`): calls
   `checkDuplicate()`, which searches:
   ```
    find '<usbMount>/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER' -mindepth 2 -maxdepth 2 -type d -name '<stem>'
   ```
   If a match is found, `TriggerTransferResult.AlreadyPresent` is returned
   immediately without launching a remote job.

2. **Post-copy** (inside the remote worker script): a second `find` runs after
   the copy finishes and before the `mv` rename. If another process raced to
   create the same stem directory, the partial output is removed and
   `ALREADY_PRESENT:<path>` is written to the status file.

The `ArchiveTileState.isAlreadyOnUsb` flag is set during discovery and disables
selection for archives already detected on the USB.

### Post-batch empty-directory cleanup

After every archive in a transfer batch has been processed,
`DefaultUsbTransferCoordinator.runBatch()` (formerly `UsbCopyViewModel.performTransfer()`
prior to Prompt 2's ownership move) calls
`UsbTransferRepository.cleanupEmptySessionDir()`, which runs
`UsbShellCommandBuilder.cleanupEmptySessionAndTraceMateDirsCommand()`:

```sh
# (mount revalidation snippet — skips entirely if the mount is gone/changed)
rmdir '<sessionDir>' 2>/dev/null
rmdir '<usbMount>/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER' 2>/dev/null
echo CLEANUP_DONE
```

This uses `rmdir` — which only succeeds on an **empty** directory — rather than
`rm -rf`, so a session directory or the `HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER` directory that still holds
even one successful export (or anything else) is never removed. The mount is
revalidated first so a coincidentally-empty directory belonging to a different,
newly-swapped device is never touched either. The whole operation is
best-effort: any ADB failure is caught and logged in
`UsbTransferRepositoryImpl.cleanupEmptySessionDir()` and never surfaces as a
batch failure to the user.

---

## Cancellation

### User action

During a transfer the Begin button changes to a Stop button
(`UsbCopyScreen` / `UsbCopyViewModel.requestCancel()`, which forwards to
`UsbTransferCoordinator.requestCancel()`). Pressing Stop flips the coordinator's
internal cancel-requested flag (persisted as part of the `ActiveTransferSnapshot`
so it survives process death too). The Back gesture is blocked by
`BackHandler(enabled = uiState is UsbCopyUiState.Transferring)` while a
transfer is active; Back does not cancel.

### Remote process termination

`UsbTransferRepositoryImpl.waitForCompletion()` (invoked from `awaitTransfer()`)
watches the cancel signal at the top of every poll cycle. When cancellation is
requested it calls `UsbTransferRepositoryImpl.executeCancel()`,
which runs `UsbShellCommandBuilder.buildCancelScript()` on the head unit:

```sh
echo CANCEL_REQUESTED > '<cancelPath>'
kill -TERM -- -$(cat '<pidPath>' 2>/dev/null) 2>/dev/null
sleep 2
kill -KILL -- -$(cat '<pidPath>' 2>/dev/null) 2>/dev/null
rm -rf '<partialDir>' 2>/dev/null
rm -f '<jobDir>'/*.err '<jobDir>'/.lz4fifo 2>/dev/null
echo 'CANCELLED' > '<statusPath>'
echo CANCEL_DONE
```

The process group is signalled (note the `-` before the PID) so child processes
are also terminated. The partial output directory is removed. `CANCELLED` is
written directly to the status file by this external script — it does not rely
on the worker reaching its own `if [ -f "$CANCELFILE" ]` checkpoint, so
cancellation is reported even if the worker is killed mid-`cp`/mid-`tar`. The
`*.err` and FIFO scratch files are also removed here as a defense-in-depth
measure: the worker's own `trap ... EXIT` normally cleans these up on a
catchable `SIGTERM`, but the subsequent `SIGKILL` is uncatchable, so this
external cleanup guarantees they never linger after a forced kill. The status
and pid files inside the job directory are intentionally left untouched. The
presence of `CANCEL_DONE` in the output determines
`TriggerTransferResult.Cancelled.partialOutputRemoved`.

### What happens to each archive

| Archive position | Outcome |
|-----------------|---------|
| Completed before Stop | `TriggerTransferResult.Success` or `AlreadyPresent` — result is preserved |
| Currently transferring | `TriggerTransferResult.Cancelled(partialOutputRemoved = true/false)` |
| Queued after the cancelled archive | Not started; counted as `notStarted` in `TransferProgressState` |

Transfer stops after the first `Cancelled` result; the batch loop in
`DefaultUsbTransferCoordinator.runBatch()` breaks out on `Cancelled`.

### Automatic retry on non-infrastructure failure

For each variant (normal and offline-trace), if the first attempt returns
`TriggerTransferResult.Failed` and `FailureReason.isInfrastructure()` is `false`
(i.e. the failure is not due to ADB connectivity loss, USB disconnection, or a
changed USB mount), `runBatch()` immediately retries the variant once. If the
retry also fails, the failure is recorded. Infrastructure failures and cancellation
are never retried.

### WiFi disconnect — batch suspend and resume

`ADB_COMMUNICATION_ERROR` is treated differently from all other infrastructure
failures because it means the network channel was lost while the remote worker
**may still be running** on the head unit. The batch behaviour differs by failure
type:

| `FailureReason` | Classification | Batch outcome |
|---|---|---|
| `ADB_COMMUNICATION_ERROR` | Infrastructure — reconnectable | Batch **suspended**; snapshot kept on disk; coordinator stays `Running` |
| `WORKER_LOST`, `USB_DISCONNECTED`, `USB_NOT_FOUND`, etc. | Infrastructure — not reconnectable | Batch **completed** (the user must see and dismiss the result) |
| All others | Non-infrastructure | Variant retried once; batch continues |

When `ADB_COMMUNICATION_ERROR` is the aggregate result for a tile:

1. The tile result is **not** recorded in `tileResults` inside the snapshot — the
   absent entry tells `attachOrRecover` to retry the tile rather than treat it as
   already done.
2. The in-flight job ID (`lastSnapshot?.currentJob`) **is** preserved in the new
   snapshot, so `attachOrRecover` can call `inspectJob` after reconnection instead
   of blindly relaunching the worker.
3. `snapshotStore.clear()` and the `Completed` state transition are **skipped**.
   The coordinator remains in `Running` so the UI stays in transfer-progress state.
4. On WiFi reconnect, `HomeViewModel.runAutomaticConnectionValidation` (triggered by
   `observeNetworkForAutoValidation`) calls `usbTransferCoordinator.attachOrRecover(host)`
   after successful validation, which resumes the batch from the preserved snapshot.
   `UsbTransferService`'s null-intent restart covers the case where the process was
   also killed.

---


## Timeouts

### No total timeout

There is no ceiling on how long a transfer may run. The wait in
`UsbTransferRepositoryImpl.waitForCompletion()` continues indefinitely until the
remote status file reports
`SUCCESS`, `FAILED`, `ALREADY_PRESENT`, `CANCELLED`, or an unexpected value.
The no-total-timeout behavior is verified by the coordinator/ViewModel tests
covering long-running transfers.

### Short-command timeouts

Individual ADB shell commands used for detection, status polling, and
directory checking are short-lived by design and rely on the ADB protocol's
own connection timeout. No explicit application-level timeout is imposed on
these commands.

### Overlay auto-dismiss

The result overlay (`UsbCopyUiState.Completed`) auto-dismisses after 15 seconds.
The timer is started by `UsbCopyViewModel.startAutoDismissTimer()` when the
overlay appears and cancelled by `dismissCompleted()` if the user taps Done
first.

---

## Manual Validation To-Do

- [ ] Start a large export, disable the phone's Wi-Fi long enough for the blocking ADB wait to fail, then restore the connection without reopening the Copy to USB screen. Confirm the foreground notification remains visible during the outage and the coordinator resumes or reaches a visible terminal result automatically.
- [ ] Start an export, press Stop while `cp`, `tar`, or `lz4` is actively writing, and verify on the head unit that the recorded worker PID/process group is no longer alive before the app displays "Copy stopped." Verify the `.partial` directory is removed.
- [ ] Disconnect ADB before pressing Stop. Confirm the app does not report cancellation as successful, retains the recovery snapshot, and shows a recoverable communication failure rather than silently continuing or claiming cleanup succeeded.
- [ ] Request Stop and immediately force-stop/swipe away the app. Restart it and confirm the persisted cancellation intent prevents the remote worker from resuming copying.
- [ ] Start a transfer, kill the app while the worker is active, then alter the USB device path or replace the stick at the same mount label. Confirm recovery refuses to resume and reports `USB_MOUNT_CHANGED`.
- [ ] Start a transfer, kill the app while the worker is active, then restore the exact same USB device and mount. Confirm recovery reattaches to the existing job rather than launching a duplicate worker.

---

## User Interface

### Copy screen — portrait layout

`UsbCopyScreen` detects orientation via `BoxWithConstraints`:
`isLandscape = maxWidth > maxHeight`.

In portrait:
- USB status text at the top
- `ArchiveTileGrid` (a `LazyVerticalGrid` with `GridCells.Adaptive(minSize = 80.dp)`)
  fills available vertical space
- Begin button pinned at the bottom

### Copy screen — landscape layout

In landscape, the content splits into two columns:
- Left column (weight 1): the tile grid
- Right column (weight 0.35): USB status, selection count, and Begin button

### Archive tile

Each tile is a `Card` with a 1:1 aspect ratio. Tapping a tile calls
`UsbCopyViewModel.toggleSelection()`. Tiles with `isAlreadyOnUsb = true` are
visually dimmed and cannot be selected. The Begin button is enabled only when
at least one selectable tile is selected and a USB mount is present.

### Transferring state

During transfer the tile grid stays visible; tiles for archives not selected
are hidden. Above or beside the grid (depending on orientation):
- `operationLabel(phase)` — current operation string from the current
  `TransferPhase`: `"Checking T1"`, `"Copying existing T1 folder"`,
  `"Extracting T1"`, or `"Stopping T1"`
- `LinearProgressIndicator` driven by `TransferProgressState.fraction`
  (counts fully completed archives only; the in-progress archive is shown
  via an indeterminate `CircularProgressIndicator`)
- Count text: `"<processed> / <totalSelected> (<percent>%)"`
- Stop button

### Result overlay — progress categories

`TransferProgressState` carries five counters:

| Field | Meaning |
|-------|---------|
| `succeeded` | `TriggerTransferResult.Success` |
| `alreadyPresent` | `TriggerTransferResult.AlreadyPresent` — not an error |
| `failed` | `TriggerTransferResult.Failed` |
| `cancelled` | `TriggerTransferResult.Cancelled` |
| `notStarted` | `totalSelected - processed` — queued items never started |

The overlay headline is produced by `headline(TransferProgressState)`:

| Condition | Headline |
|-----------|---------|
| Any cancelled or not-started | `"Copy stopped"` |
| All failed, none succeeded | `"Copy failed"` |
| Mix of success and failure | `"Copy completed with errors"` |
| All success or already-present | `"Copy completed"` |

### Show USB Files screen

`UsbFilesScreen` opens from the **Show USB Files** button on HomeScreen and
navigates to route `usb_files`. It loads the HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER directory tree via
`UsbFilesViewModel`, which calls `UsbTransferRepository.listUsbFiles()` on
init. Manual refresh is available via the Refresh icon in the top bar.

#### USB files — portrait layout

Each row (`FileRowPortrait`) shows:
- A type badge (`FILE` or `DIR`) and the entry name on the first line
- The relative path (indented, below the name) when the path differs from the
  name alone

#### USB files — landscape layout

At `maxWidth > 480.dp` (detected per-row via `BoxWithConstraints`),
`FileRowLandscape` displays four columns: type badge, name, relative path,
and human-readable size — all on a single line with `TextOverflow.Ellipsis`.

The list is a `LazyColumn` keyed by `UsbFileEntry.relativePath`.

---

## Failure Behavior

### USB missing at discovery time

`UsbTransferRepositoryImpl.detectUsbMount()` runs
`UsbShellCommandBuilder.detectUsbMountCommand()`. If no writable mount is
found the method returns `null`. In `UsbCopyViewModel.performDiscovery()` a
null mount results in `UsbCopyUiState.Ready(usbMount = null)`, which disables
the Begin button and shows "No writable USB stick detected."

For the file-listing flow, `UsbListingResult.UsbNotConnected` is returned and
the screen shows `UsbFilesUiState.UsbNotConnected`.

### USB removed during transfer

The poll command (`readStatusCommand`) will fail or return blank output if the
USB disappears. A failed ADB poll returns
`TriggerTransferResult.Failed(reason = FailureReason.ADB_COMMUNICATION_ERROR)`.
The ViewModel does not crash; the failing archive is recorded as Failed and the
loop continues with the next archive.

### USB removed during file listing

`UsbFileListingParser.parse()` checks for the `STATUS:DONE` sentinel at the
end of the command output. If the sentinel is absent,
`UsbListingResult.UsbRemovedDuringListing` is returned with whatever entries
were parsed before truncation. The file-listing screen shows a banner
("USB removed during listing — showing partial results") and renders the
partial entry list.

### ADB connection loss

`AdbProtocol.executeShellCommand()` returns a result with `success = false`
when the TCP connection is lost or the ADB handshake fails. Behaviour differs by
context:

- **During discovery:** exception thrown → `UsbCopyUiState.DiscoveryFailed`
- **During transfer (blocking wait):** `TriggerTransferResult.Failed(FailureReason.ADB_COMMUNICATION_ERROR)`
  → coordinator **suspends** the batch (does not complete it), preserves the
  snapshot and in-flight job ID, stays `Running`; `attachOrRecover` resumes once
  WiFi is restored (see "WiFi disconnect — batch suspend and resume" above).
- **During file listing:** `UsbListingResult.AdbCommandFailed` → `UsbFilesUiState.AdbError`.

### Extraction failure (Strategy B)

The Strategy B worker script emits one of these failure tokens to the status
file if extraction fails. `LZ4_DECOMPRESS_FAILED`/`TAR_EXTRACT_FAILED` are now
only reached after captured `tar`/`lz4` stderr and a live mount check have
both ruled out a destination-side cause (see "cp/tar/lz4 stderr capture and
failure classification" above):

| Status file token | `FailureReason` |
|-------------------|----------------|
| `FAILED:LZ4_UNAVAILABLE` | `LZ4_UNAVAILABLE` |
| `FAILED:LZ4_DECOMPRESS_FAILED` | `LZ4_DECOMPRESS_FAILED` |
| `FAILED:TAR_EXTRACT_FAILED` | `TAR_EXTRACT_FAILED` |
| `FAILED:USB_FULL` | `USB_FULL` |
| `FAILED:USB_NOT_WRITABLE` | `USB_NOT_WRITABLE` |
| `FAILED:USB_IO_ERROR` | `USB_IO_ERROR` |
| `FAILED:USB_DISCONNECTED` | `USB_DISCONNECTED` |
| `FAILED:USB_MOUNT_CHANGED` | `USB_MOUNT_CHANGED` |

`waitForCompletion()` reads the token via `parseFailureReason()` and returns
`TriggerTransferResult.Failed`.

### Copy failure (Strategy A)

If `cp` fails in the Strategy A worker, its captured stderr (`cp.err`) is
classified the same way before falling back to `CP_FAILED`:

| Status file token | `FailureReason` |
|-------------------|----------------|
| `FAILED:PARTIAL_DIR_CREATE_FAILED` | `PARTIAL_DIR_CREATE_FAILED` |
| `FAILED:CP_FAILED` | `CP_FAILED` |
| `FAILED:MV_RENAME_FAILED` | `MV_RENAME_FAILED` |
| `FAILED:USB_FULL` | `USB_FULL` |
| `FAILED:USB_NOT_WRITABLE` | `USB_NOT_WRITABLE` |
| `FAILED:USB_IO_ERROR` | `USB_IO_ERROR` |
| `FAILED:USB_DISCONNECTED` | `USB_DISCONNECTED` |
| `FAILED:USB_MOUNT_CHANGED` | `USB_MOUNT_CHANGED` |

### Mount revalidation failure

Emitted by the shared mount-revalidation snippet at each of its three call
sites (see "Mount revalidation before writes" above). The prefix differs by
call site (`FAIL:` from `createSessionDirCommand`, `ERROR:` from the outer
job-start script, `FAILED:<token>` from the worker status file) but all three
resolve to the same `FailureReason` via `parseFailureReason()`:

| Token | `FailureReason` |
|-------|----------------|
| `USB_NOT_FOUND` | `USB_NOT_FOUND` |
| `USB_DISCONNECTED` | `USB_DISCONNECTED` |
| `USB_NOT_WRITABLE` | `USB_NOT_WRITABLE` |
| `USB_MOUNT_CHANGED` | `USB_MOUNT_CHANGED` |
| `JOB_DIR_CREATE_FAILED` | `JOB_DIR_CREATE_FAILED` |

### Cancellation cleanup failure

`buildCancelScript()` removes the partial directory with `rm -rf` and then
emits `CANCEL_DONE`. If `CANCEL_DONE` is absent in the command output,
`executeCancel()` sets `partialOutputRemoved = false` in the returned
`TriggerTransferResult.Cancelled`. The screen still transitions to the result
overlay; no crash occurs. The partial directory may remain on the USB.

### Already-present result

`TriggerTransferResult.AlreadyPresent` is returned in three cases:

1. Pre-launch duplicate check (`checkDuplicate()`) finds the stem directory on
   the USB before the job starts.
2. The remote worker's post-copy duplicate check finds a race-created duplicate
   and writes `ALREADY_PRESENT:<path>` to the status file.
3. `waitForCompletion()` reads `ALREADY_PRESENT:<path>` from the status file.

In all three cases the archive is counted as `alreadyPresent` in
`TransferProgressState`, **not** as `failed`. It does not affect the Begin
button, but tiles discovered as duplicates at discovery time have
`ArchiveTileState.isAlreadyOnUsb = true` and cannot be selected.

---

## Architecture Reference

### Key classes

| Class | Package | Responsibility |
|-------|---------|----------------|
| `UsbCopyScreen` | `ui.usbcopy` | Selection, transfer progress, and result overlay |
| `UsbCopyViewModel` | `ui.usbcopy` | Discovers/selects archives; delegates start/cancel to the coordinator and observes its state |
| `UsbCopyUiState` | `ui.usbcopy` | Sealed UI state: `Idle`, `Discovering`, `DiscoveryFailed`, `Ready`, `Transferring`, `Completed` |
| `UsbTransferCoordinator` / `DefaultUsbTransferCoordinator` | `domain.usb` / `data.usb` | Application-scoped batch loop, cancellation, and crash-recovery reattachment (owns what `UsbCopyViewModel` used to own pre-Prompt-2) |
| `UsbTransferCoordinatorState` | `domain.model` | Sealed coordinator state: `Idle`, `Running`, `Completed` |
| `UsbTransferService` | `data.usb` | Foreground `connectedDevice` service; started only from the user's "Begin" action, hosts the coordinator's ongoing notification |
| `ActiveTransferSnapshot` / `ActiveTransferSnapshotCodec` | `domain.model` / `domain.usb` | Compact, pure-text-encoded snapshot of an in-progress batch; `tileResults` only contains entries for tiles with at least one recorded result (absent = not yet done or WiFi-loss interrupted) |
| `TransferSnapshotStore` / `DataStoreTransferSnapshotStore` | `data.usb` | Persists the encoded snapshot (DataStore-backed; no Android dependency in the interface) |
| `TransferProgressState` | `domain.model` | Counters: `totalSelected`, `processed`, `succeeded`, `alreadyPresent`, `failed`, `cancelled` |
| `ArchiveTileState` | `domain.model` | Per-archive tile state including selection and final result |
| `UsbFilesScreen` | `ui.usbfiles` | Browse-only USB file listing, route `usb_files` |
| `UsbFilesViewModel` | `ui.usbfiles` | Loads and refreshes `UsbListingResult`; no copy logic |
| `UsbFilesUiState` | `ui.usbfiles` | Sealed states for the listing screen |
| `UsbTransferRepository` | `data.usb` | Interface for all ADB USB operations, split into `prepareTransfer`/`launchTransfer`/`awaitTransfer`/`inspectJob` so launch and reattach are separate operations |
| `FailureClassification` / `FailureReason.isInfrastructure()` | `domain.usb` | Classifies failures as infrastructure (stop the batch) vs. transient (eligible for retry) |
| `UsbTransferRepositoryImpl` | `data.usb` | Implementation; each method issues one or more `AdbProtocol.executeShellCommand()` calls |
| `UsbShellCommandBuilder` | `domain.usb` | Builds all shell command strings; contains no state |
| `UsbFileListingParser` | `domain.usb` | Parses `stat`-formatted listing output into `List<UsbFileEntry>` |
| `TriggerArchiveParser` | `domain.usb` | Parses `find` discovery output; validates paths and stems |
| `TriggerArchive` | `domain.model` | One discovered archive (stem, paths, display label) |
| `TriggerTransferResult` | `domain.model` | Sealed result: `Success`, `Failed`, `Cancelled`, `AlreadyPresent` |
| `TransferPhase` | `domain.model` | Sealed phase: `Checking`, `CopyingExisting`, `Extracting`, `Stopping` |
| `UsbMount` | `domain.model` | Wraps the mount path string |
| `UsbSessionDirResult` | `domain.model` | Sealed result for session directory creation: `Success(path)` / `Failed(reason, message)` |
| `UsbListingResult` | `domain.model` | Sealed listing result for the file browser |
| `UsbFileEntry` | `domain.model` | One listed file or directory (name, relative path, type, size) |

### ADB port

All operations use port `5555` (constant `ADB_PORT` in both
`DefaultUsbTransferCoordinator` and `UsbFilesViewModel`).

### Remote job lifecycle

```
launchRemoteJob()
  └─ buildJobStartScript() → setsid sh -c '<worker>' &
       writes STARTED:<pid> to stdout
       creates HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/<jobId>/status = "RUNNING"

awaitTransfer()  (UsbTransferRepositoryImpl.waitForCompletion())
  └─ loop:
       check cancelSignal → executeCancel() if true
       delay(2 000 ms)
       readStatusCommand() → cat HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/<jobId>/status
       parse: SUCCESS / FAILED / ALREADY_PRESENT / CANCELLED / RUNNING

inspectJob()  (used only on service/process recreation, via UsbShellCommandBuilder.inspectJobCommand())
  └─ single round trip: kill -0 <pid> (ALIVE:0/1) + cat status (STATUS:<token>)
       ALIVE:1              → JobInspection.Alive       (coordinator reattaches via awaitTransfer)
       ALIVE:0, terminal status → JobInspection.Terminal(result) (consumed once, queue continues)
       ALIVE:0, no/RUNNING status → JobInspection.Lost  (WORKER_LOST, never relaunched blindly)
```

### File listing pipeline

```
UsbFilesViewModel.fetchFiles()
  └─ UsbTransferRepository.listUsbFiles()
       └─ detectUsbMount()                   → UsbMount or UsbNotConnected
       └─ checkTraceMateDirectoryCommand()   → YES / NO
       └─ listTraceMateFilesCommand()        → stat pipe-delimited lines + STATUS:DONE
       └─ UsbFileListingParser.parse()       → ParseResult(entries, complete)
```
