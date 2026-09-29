package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.UsbMount

object UsbShellCommandBuilder {
    private const val HU_EXPORT_DIRECTORY = "HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER"
    private const val TRACE_ARCHIVE_DIR = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER"
    private const val USB_MOUNT_ROOT = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER"
    private const val TRANSFER_WORK_DIR = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER"

    fun discoverArchivesCommand(): String =
        "find $TRACE_ARCHIVE_DIR -maxdepth 1 -type f -name 'trigger_*_COREDUMP.tar.lz4' 2>/dev/null"

    // Reads /proc/mounts and emits one pipe-delimited record for every mount below the
    // configured USB root. UsbMountParser accepts only the supported one-segment layout;
    // retaining unsupported candidates lets the UI distinguish that layout from no mount.
    // Format per line: <device>|<mountpoint>|<fstype>|<options>
    // Produces no output (empty) when no such mount exists.
    fun detectUsbMountsCommand(): String =
        "awk '\$2 ~ /^${USB_MOUNT_ROOT}\\// {print \$1 \"|\"\$2 \"|\"\$3 \"|\"\$4}'" +
                " /proc/mounts 2>/dev/null"

    /**
     * Shell snippet (not a standalone command) that re-validates [usbMount] against
     * the live contents of /proc/mounts immediately before a write, assigning the
     * outcome to the `_USB_CHECK` shell variable:
     *
     *   OK                — the same device is still mounted at the same path, rw
     *   <missingReason>    — the mount path is no longer present at all. Callers pass
     *                        "USB_NOT_FOUND" when no session/job exists yet, or
     *                        "USB_DISCONNECTED" once one is already in progress
     *   USB_MOUNT_CHANGED  — a *different* device is now mounted at this path
     *   USB_NOT_WRITABLE   — same device/path, but no longer mounted rw
     *
     * Must run immediately before every write (session directory creation, worker
     * launch, final rename/commit) — never assume a mount detected earlier is still
     * valid. After a USB stick is unmounted its mount-point directory still exists as
     * a plain, now-empty directory on the underlying filesystem (commonly tmpfs), so a
     * blind `mkdir -p` / `mv` there would silently "succeed" without ever touching the
     * stick.
     */
    private fun mountValidationSnippet(usbMount: UsbMount, missingReason: String): String {
        val device = usbMount.devicePath
        val mountPath = usbMount.mountPath.replace("\\", "\\134").replace(" ", "\\040")
        return buildString {
            appendLine("_USB_LINE=\$(awk -v mp='$mountPath' '\$2==mp {print \$1\"|\"\$4; exit}' /proc/mounts 2>/dev/null)")
            appendLine("if [ -z \"\$_USB_LINE\" ]; then")
            appendLine("  _USB_CHECK='$missingReason'")
            appendLine("else")
            appendLine("  _USB_DEV=\$(printf '%s' \"\$_USB_LINE\" | cut -d'|' -f1)")
            appendLine("  _USB_OPTS=\$(printf '%s' \"\$_USB_LINE\" | cut -d'|' -f2)")
            appendLine("  if [ \"\$_USB_DEV\" != '$device' ]; then")
            appendLine("    _USB_CHECK='USB_MOUNT_CHANGED'")
            appendLine("  elif printf ',%s,' \"\$_USB_OPTS\" | grep -q ',rw,'; then")
            appendLine("    _USB_CHECK='OK'")
            appendLine("  else")
            appendLine("    _USB_CHECK='USB_NOT_WRITABLE'")
            appendLine("  fi")
            appendLine("fi")
        }
    }

    /**
     * Shell snippet that inspects one or more stderr-capture files (already-quoted
     * shell variable references, e.g. `"\$CPERR"`) for known destination-side
     * failure signatures and assigns the result to `_CLASSIFY_REASON`:
     *
     *   USB_FULL         — "No space left on device"
     *   USB_NOT_WRITABLE — "Read-only file system"
     *   USB_IO_ERROR     — "Input/output error"
     *
     * `_CLASSIFY_REASON` is left empty (`''`) when none of the given files
     * contain a recognised pattern, so callers can continue with mount
     * revalidation or tool-specific fallback logic instead of guessing.
     */
    private fun destinationErrorPatternSnippet(errorFileVars: List<String>): String {
        val fileList = errorFileVars.joinToString(" ") { "\"$it\"" }
        return buildString {
            appendLine("_CLASSIFY_REASON=''")
            appendLine("for _ERRFILE in $fileList; do")
            appendLine("  if [ -z \"\$_CLASSIFY_REASON\" ] && [ -s \"\$_ERRFILE\" ]; then")
            appendLine("    if grep -q 'No space left on device' \"\$_ERRFILE\" 2>/dev/null; then")
            appendLine("      _CLASSIFY_REASON='USB_FULL'")
            appendLine("    elif grep -q 'Read-only file system' \"\$_ERRFILE\" 2>/dev/null; then")
            appendLine("      _CLASSIFY_REASON='USB_NOT_WRITABLE'")
            appendLine("    elif grep -q 'Input/output error' \"\$_ERRFILE\" 2>/dev/null; then")
            appendLine("      _CLASSIFY_REASON='USB_IO_ERROR'")
            appendLine("    fi")
            appendLine("  fi")
            append("done")
        }
    }

    /**
     * Combines [destinationErrorPatternSnippet] with a live mount revalidation,
     * falling back to [defaultReason] only when neither identifies the cause.
     * Sets `_CLASSIFY_REASON`. Used after a `cp`/`tar`/`lz4` failure so a full
     * disk, a flipped-to-read-only mount, an I/O error, a disconnected stick, or
     * a swapped device is never mistaken for tool-level data corruption.
     */
    private fun classifyDestinationFailureSnippet(
        usbMount: UsbMount,
        errorFileVars: List<String>,
        defaultReason: String
    ): String = buildString {
        append(destinationErrorPatternSnippet(errorFileVars))
        appendLine()
        appendLine("if [ -z \"\$_CLASSIFY_REASON\" ]; then")
        append(mountValidationSnippet(usbMount, missingReason = "USB_DISCONNECTED"))
        appendLine("  if [ \"\$_USB_CHECK\" != 'OK' ]; then _CLASSIFY_REASON=\"\$_USB_CHECK\"; else _CLASSIFY_REASON='$defaultReason'; fi")
        append("fi")
    }

    fun remountReadWriteCommand(usbMount: UsbMount): String =
        "mount -o remount,rw '${usbMount.devicePath}' '${usbMount.mountPath}' 2>/dev/null" +
                " && echo REMOUNT_OK || echo REMOUNT_FAILED"

    fun ejectUsbCommand(usbMount: UsbMount): String =
        "sync && umount '${usbMount.mountPath}' 2>/dev/null && echo EJECTED || echo EJECT_FAILED"

    // Revalidates the expected device + mount path against live /proc/mounts before
    // ever calling mkdir — never creates a mount-label directory on tmpfs for a mount
    // that has disappeared or been swapped for another device. A write probe follows
    // mount validation to catch the rare case where /proc/mounts reports rw but the
    // kernel has already remounted the filesystem ro after an I/O error.
    fun createSessionDirCommand(usbMount: UsbMount): String {
        val base = usbMount.mountPath
        return buildString {
            append(mountValidationSnippet(usbMount, missingReason = "USB_NOT_FOUND"))
            appendLine("if [ \"\$_USB_CHECK\" != 'OK' ]; then echo \"FAIL:\$_USB_CHECK\"; exit 0; fi")
            // Probe actual writability — /proc/mounts may still report rw after the kernel
            // silently remounts ro on I/O error. $$ in the filename prevents a concurrent
            // caller from colliding on the same probe path.
            appendLine("PROBE='$base/.tracemate_probe_\$\$'")
            appendLine("if ! touch \"\$PROBE\" 2>/dev/null; then echo 'FAIL:USB_NOT_WRITABLE'; exit 0; fi")
            appendLine("rm -f \"\$PROBE\" 2>/dev/null")
            appendLine("TS=\$(date +%Y%m%d_%H%M%S); D='$base/$HU_EXPORT_DIRECTORY/tracemate_export_'\$TS")
            append("mkdir -p \"\$D\" && echo \"OK:\$D\" || echo \"FAIL:\$D\"")
        }
    }

    fun checkStrategyACommand(extractedDirPath: String): String =
        "[ -d '$extractedDirPath' ] && echo YES || echo NO"

    fun checkArchiveExistsCommand(archivePath: String): String =
        "[ -f '$archivePath' ] && echo YES || echo NO"

    fun checkDuplicateCommand(usbMount: UsbMount, stem: String): String =
        "find '${usbMount.mountPath}/$HU_EXPORT_DIRECTORY' -mindepth 2 -maxdepth 2 -type d -name '$stem' 2>/dev/null | head -n 1"

    fun listExistingExportDirectoriesCommand(usbMount: UsbMount): String {
        val base = "${usbMount.mountPath}/$HU_EXPORT_DIRECTORY"
        return "if [ ! -d '$base' ]; then echo 'STATUS:DONE'; " +
                "elif find '$base' -mindepth 2 -maxdepth 2 -type d -name 'trigger_*' -print 2>/dev/null; " +
                "then echo 'STATUS:DONE'; fi"
    }

    // Blocks until the worker process (identified by PID in pidPath) exits, then reads and
    // returns the final status written to statusPath. Used instead of repeated polling so
    // that a single ADB connection stays open for the entire transfer duration.
    fun blockingWaitAndReadStatusCommand(pidPath: String, statusPath: String): String = buildString {
        appendLine("PID=\$(cat '$pidPath' 2>/dev/null)")
        appendLine("while kill -0 \"\$PID\" 2>/dev/null; do sleep 1; done")
        append("cat '$statusPath' 2>/dev/null")
    }

    fun buildCancelScript(jobDir: String, pidPath: String, cancelPath: String, partialDir: String, statusPath: String): String = buildString {
        appendLine("PID=\$(cat '$pidPath' 2>/dev/null)")
        appendLine("if [ -z \"\$PID\" ]; then echo 'CANCEL_FAILED:PID_UNAVAILABLE'; exit 1; fi")
        appendLine("echo CANCEL_REQUESTED > '$cancelPath'")
        appendLine("kill -TERM -- -\$PID 2>/dev/null")
        appendLine("sleep 2")
        appendLine("if kill -0 \"\$PID\" 2>/dev/null; then kill -KILL -- -\$PID 2>/dev/null; fi")
        appendLine("if kill -0 \"\$PID\" 2>/dev/null; then echo 'CANCEL_FAILED:WORKER_STILL_RUNNING'; exit 1; fi")
        appendLine("if ! rm -rf '$partialDir' 2>/dev/null; then echo 'CANCEL_FAILED:PARTIAL_CLEANUP_FAILED'; exit 1; fi")
        // Defense-in-depth: the worker's own `trap ... EXIT` cleans up its FIFO/error
        // temporaries on a TERM it can catch, but SIGKILL is uncatchable, so this
        // external cancel path removes any leftover scratch files itself. The status
        // and pid files inside jobDir are intentionally left alone.
        appendLine("rm -f '$jobDir'/*.err '$jobDir'/.lz4fifo 2>/dev/null")
        // Write CANCELLED so the blocking-wait command reads a definitive terminal status.
        appendLine("echo 'CANCELLED' > '$statusPath' || { echo 'CANCEL_FAILED:STATUS_WRITE_FAILED'; exit 1; }")
        append("echo CANCEL_DONE")
    }

    /**
     * Best-effort, safety-first cleanup run once after a transfer batch completes.
     * Uses `rmdir` — which only succeeds on an *empty* directory — rather than
     * `rm -rf`, so a session directory or the `TraceMate` directory that still
     * contains any successful export (or anything else) is never removed. Revalidates
     * the mount first so a coincidentally-empty directory belonging to a different,
     * newly-swapped device is never touched either.
     */
    fun cleanupEmptySessionAndTraceMateDirsCommand(sessionDir: String, usbMount: UsbMount): String {
        val traceMateDir = "${usbMount.mountPath}/$HU_EXPORT_DIRECTORY"
        return buildString {
            append(mountValidationSnippet(usbMount, missingReason = "USB_DISCONNECTED"))
            appendLine("if [ \"\$_USB_CHECK\" != 'OK' ]; then echo 'CLEANUP_SKIPPED'; exit 0; fi")
            appendLine("rmdir '$sessionDir' 2>/dev/null")
            appendLine("rmdir '$traceMateDir' 2>/dev/null")
            append("echo CLEANUP_DONE")
        }
    }

    fun buildWorkerScriptContent(
        jobId: String,
        archive: TriggerArchive,
        usbMount: UsbMount,
        sessionDir: String,
        useStrategyA: Boolean
    ): String {
        val jobDir = "$TRANSFER_WORK_DIR/$jobId"
        val statusPath = "$jobDir/status"
        val cancelPath = "$jobDir/cancel"
        return if (useStrategyA) {
            buildStrategyAWorker(
                stem = archive.stem,
                srcDir = archive.extractedDirectoryPath,
                sessionDir = sessionDir,
                usbMount = usbMount,
                jobDir = jobDir,
                statusPath = statusPath,
                cancelPath = cancelPath
            )
        } else {
            buildStrategyBWorker(
                stem = archive.stem,
                archivePath = archive.archivePath,
                jobDir = jobDir,
                sessionDir = sessionDir,
                usbMount = usbMount,
                statusPath = statusPath,
                cancelPath = cancelPath
            )
        }
    }

    fun buildJobPrepareScript(jobId: String, usbMount: UsbMount): String {
        val jobDir = "$TRANSFER_WORK_DIR/$jobId"
        return buildString {
            append(mountValidationSnippet(usbMount, missingReason = "USB_DISCONNECTED"))
            appendLine("if [ \"\$_USB_CHECK\" != 'OK' ]; then echo \"ERROR:\$_USB_CHECK\"; exit 1; fi")
            appendLine("JOBDIR='$jobDir'")
            appendLine("mkdir -p \"\$JOBDIR\" || { echo 'ERROR:JOB_DIR_CREATE_FAILED'; exit 1; }")
            append("echo JOBDIR_READY")
        }
    }

    fun buildJobLaunchScript(jobId: String): String {
        val jobDir = "$TRANSFER_WORK_DIR/$jobId"
        val workerScript = "$jobDir/worker.sh"
        val workerLog = "$jobDir/worker.log"
        val pidPath = "$jobDir/pid"
        val statusPath = "$jobDir/status"
        return buildString {
            appendLine("echo 'RUNNING' > '$statusPath'")
            appendLine("chmod +x '$workerScript'")
            appendLine("setsid sh '$workerScript' </dev/null >'$workerLog' 2>&1 &")
            appendLine("BGPID=\$!")
            appendLine("echo \$BGPID > '$pidPath'")
            append("echo \"STARTED:\$BGPID\"")
        }
    }

    // .partial staging: writes to a hidden dir, then atomic rename on success — prevents
    // incomplete exports from appearing as valid directories if the transfer is interrupted.
    private fun buildStrategyAWorker(
        stem: String,
        srcDir: String,
        sessionDir: String,
        usbMount: UsbMount,
        jobDir: String,
        statusPath: String,
        cancelPath: String
    ): String {
        val partial = "$sessionDir/.$stem.partial"
        val final = "$sessionDir/$stem"
        val cpErr = "$jobDir/cp.err"
        return buildString {
            appendLine("STATUSFILE='$statusPath'")
            appendLine("CANCELFILE='$cancelPath'")
            appendLine("PARTIAL='$partial'")
            appendLine("FINAL='$final'")
            appendLine("CPERR='$cpErr'")
            appendLine("_COMMITTED=0")
            // trap/finally-style cleanup: runs on every exit path (normal completion,
            // any `exit` call above, or an uncaught terminating signal such as the
            // SIGTERM sent by the cancel script). Always removes the cp stderr
            // scratch file; only removes the .partial staging dir when the final
            // rename has not been committed, so a completed export is never touched.
            appendLine("trap 'rm -f \"\$CPERR\" 2>/dev/null; if [ \"\$_COMMITTED\" != \"1\" ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; fi' EXIT")
            appendLine("mkdir -p \"\$PARTIAL\" || { echo 'FAILED:PARTIAL_DIR_CREATE_FAILED' > \"\$STATUSFILE\"; exit 1; }")
            // Capture cp's stderr instead of discarding it — needed to classify a
            // failure (full disk / read-only / I-O error) instead of guessing.
            appendLine("cp -a '$srcDir/.' \"\$PARTIAL/\" 2>\"\$CPERR\" || cp -r '$srcDir/.' \"\$PARTIAL/\" 2>\"\$CPERR\"")
            appendLine("CPCODE=\$?")
            appendLine("if [ \$CPCODE -ne 0 ]; then")
            // Surface the captured stderr in worker.log for manual diagnosis — this
            // "cat" is unredirected so it flows into worker.log via the outer
            // "setsid sh -c '...' > worker.log 2>&1" redirection in buildJobStartScript.
            appendLine("  cat \"\$CPERR\" 2>/dev/null")
            append(classifyDestinationFailureSnippet(usbMount, listOf("\$CPERR"), defaultReason = "CP_FAILED"))
            appendLine()
            appendLine("  rm -rf \"\$PARTIAL\" 2>/dev/null")
            appendLine("  echo \"FAILED:\$_CLASSIFY_REASON\" > \"\$STATUSFILE\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("if [ -f \"\$CANCELFILE\" ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; echo 'CANCELLED' > \"\$STATUSFILE\"; exit 0; fi")
            appendLine("FOUND=\$(find '${usbMount.mountPath}/$HU_EXPORT_DIRECTORY' -mindepth 2 -maxdepth 2 -type d -name '$stem' 2>/dev/null | head -n 1)")
            appendLine("if [ -n \"\$FOUND\" ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; echo \"ALREADY_PRESENT:\$FOUND\" > \"\$STATUSFILE\"; exit 0; fi")
            // Revalidate immediately before the final rename/commit — the mount may
            // have disappeared, changed device, or flipped back to ro during the copy.
            append(mountValidationSnippet(usbMount, missingReason = "USB_DISCONNECTED"))
            appendLine("if [ \"\$_USB_CHECK\" != 'OK' ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; echo \"FAILED:\$_USB_CHECK\" > \"\$STATUSFILE\"; exit 1; fi")
            appendLine("mv \"\$PARTIAL\" \"\$FINAL\" || { echo 'FAILED:MV_RENAME_FAILED' > \"\$STATUSFILE\"; exit 1; }")
            appendLine("_COMMITTED=1")
            appendLine("sync")
            append("echo \"SUCCESS:\$FINAL\" > \"\$STATUSFILE\"")
        }
    }

    private fun buildStrategyBWorker(
        stem: String,
        archivePath: String,
        jobDir: String,
        sessionDir: String,
        usbMount: UsbMount,
        statusPath: String,
        cancelPath: String
    ): String {
        val partial = "$sessionDir/.$stem.partial"
        val final = "$sessionDir/$stem"
        val fifo = "$jobDir/.lz4fifo"
        val tarErr = "$jobDir/tar.err"
        val lz4Err = "$jobDir/lz4.err"
        val lz4TestErr = "$jobDir/lz4_test.err"
        return buildString {
            appendLine("STATUSFILE='$statusPath'")
            appendLine("CANCELFILE='$cancelPath'")
            appendLine("PARTIAL='$partial'")
            appendLine("FINAL='$final'")
            appendLine("FIFO='$fifo'")
            appendLine("TARERR='$tarErr'")
            appendLine("LZ4ERR='$lz4Err'")
            appendLine("LZ4TESTERR='$lz4TestErr'")
            appendLine("_COMMITTED=0")
            // trap/finally-style cleanup: runs on every exit path (normal completion,
            // any `exit` call above, or an uncaught terminating signal such as the
            // SIGTERM sent by the cancel script). Always removes the FIFO and the
            // tar/lz4 stderr scratch files; only removes the .partial staging dir
            // when the final rename has not been committed, so a completed export
            // is never touched.
            appendLine("trap 'rm -f \"\$FIFO\" \"\$TARERR\" \"\$LZ4ERR\" \"\$LZ4TESTERR\" 2>/dev/null; if [ \"\$_COMMITTED\" != \"1\" ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; fi' EXIT")
            appendLine("[ -f '$archivePath' ] || { echo 'FAILED:SOURCE_ARCHIVE_MISSING' > \"\$STATUSFILE\"; exit 1; }")
            appendLine("mkdir -p \"\$PARTIAL\" || { echo 'FAILED:PARTIAL_DIR_CREATE_FAILED' > \"\$STATUSFILE\"; exit 1; }")
            appendLine("command -v lz4 >/dev/null 2>&1 || { rm -rf \"\$PARTIAL\" 2>/dev/null; echo 'FAILED:LZ4_UNAVAILABLE' > \"\$STATUSFILE\"; exit 1; }")
            // Stream lz4 directly into tar — never materialise the full .tar on tmpfs.
            // PIPESTATUS is bash-only; use a fifo in the job dir (on tmpfs) so mkfifo
            // never touches the USB mount where the operation is not permitted.
            appendLine("mkfifo \"\$FIFO\" 2>/dev/null || { rm -rf \"\$PARTIAL\" 2>/dev/null; echo 'FAILED:FIFO_CREATE_FAILED' > \"\$STATUSFILE\"; exit 1; }")
            // Capture lz4's and tar's stderr instead of discarding it — needed to
            // classify a failure (full disk / read-only / I-O error / corrupt
            // archive) instead of guessing.
            appendLine("lz4 -dc -- '$archivePath' > \"\$FIFO\" 2>\"\$LZ4ERR\" & LZ4PID=\$!")
            appendLine("tar -x -C \"\$PARTIAL\" < \"\$FIFO\" 2>\"\$TARERR\"; TARCODE=\$?")
            appendLine("wait \$LZ4PID; LZ4CODE=\$?")
            appendLine("rm -f \"\$FIFO\" 2>/dev/null")
            // lz4 exits 141 (SIGPIPE) when tar closes the FIFO after successfully reading
            // all data. That is not an error on its own — a genuine pipeline failure is
            // either lz4 exiting with any other non-zero code, or tar itself failing.
            appendLine("PIPELINE_FAILED=0")
            appendLine("if [ \$LZ4CODE -ne 0 ] && [ \$LZ4CODE -ne 141 ]; then PIPELINE_FAILED=1; fi")
            appendLine("if [ \$TARCODE -ne 0 ]; then PIPELINE_FAILED=1; fi")
            appendLine("if [ \$PIPELINE_FAILED -eq 1 ]; then")
            // Surface captured stderr in worker.log for manual diagnosis — these "cat"
            // calls are unredirected so they flow into worker.log via the outer
            // "setsid sh -c '...' > worker.log 2>&1" redirection in buildJobStartScript.
            appendLine("  cat \"\$TARERR\" 2>/dev/null")
            appendLine("  cat \"\$LZ4ERR\" 2>/dev/null")
            append(classifyDestinationFailureSnippet(usbMount, listOf("\$TARERR", "\$LZ4ERR"), defaultReason = "_UNRESOLVED"))
            appendLine()
            // Destination looks healthy (no space/permission/I-O/mount issue) — only now
            // check whether the source archive itself is corrupt. A destination write
            // failure must never be mislabelled as tar/lz4 data corruption.
            appendLine("  if [ \"\$_CLASSIFY_REASON\" = '_UNRESOLVED' ]; then")
            appendLine("    if lz4 -t -- '$archivePath' >\"\$LZ4TESTERR\" 2>&1; then")
            // Source archive is intact. If lz4 still exited abnormally during the
            // pipeline (not just SIGPIPE), that is a genuine independent lz4 failure;
            // otherwise the failure is tar's alone.
            appendLine("      if [ \$LZ4CODE -ne 0 ] && [ \$LZ4CODE -ne 141 ]; then")
            appendLine("        _CLASSIFY_REASON='LZ4_DECOMPRESS_FAILED'")
            appendLine("      else")
            appendLine("        _CLASSIFY_REASON='TAR_EXTRACT_FAILED'")
            appendLine("      fi")
            appendLine("    else")
            appendLine("      cat \"\$LZ4TESTERR\" 2>/dev/null")
            appendLine("      _CLASSIFY_REASON='LZ4_DECOMPRESS_FAILED'")
            appendLine("    fi")
            appendLine("  fi")
            appendLine("  rm -rf \"\$PARTIAL\" 2>/dev/null")
            appendLine("  echo \"FAILED:\$_CLASSIFY_REASON\" > \"\$STATUSFILE\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("if [ -f \"\$CANCELFILE\" ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; echo 'CANCELLED' > \"\$STATUSFILE\"; exit 0; fi")
            appendLine("if [ -d \"\$PARTIAL/$stem\" ]; then mv \"\$PARTIAL/$stem\" \"\$PARTIAL.tmp\" && rm -rf \"\$PARTIAL\" && mv \"\$PARTIAL.tmp\" \"\$PARTIAL\"; fi")
            appendLine("FOUND=\$(find '${usbMount.mountPath}/$HU_EXPORT_DIRECTORY' -mindepth 2 -maxdepth 2 -type d -name '$stem' 2>/dev/null | head -n 1)")
            appendLine("if [ -n \"\$FOUND\" ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; echo \"ALREADY_PRESENT:\$FOUND\" > \"\$STATUSFILE\"; exit 0; fi")
            // Revalidate immediately before the final rename/commit — the mount may
            // have disappeared, changed device, or flipped back to ro during extraction.
            append(mountValidationSnippet(usbMount, missingReason = "USB_DISCONNECTED"))
            appendLine("if [ \"\$_USB_CHECK\" != 'OK' ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; echo \"FAILED:\$_USB_CHECK\" > \"\$STATUSFILE\"; exit 1; fi")
            appendLine("mv \"\$PARTIAL\" \"\$FINAL\" || { echo 'FAILED:MV_RENAME_FAILED' > \"\$STATUSFILE\"; exit 1; }")
            appendLine("_COMMITTED=1")
            appendLine("sync")
            append("echo \"SUCCESS:\$FINAL\" > \"\$STATUSFILE\"")
        }
    }

    fun checkWorkerAliveCommand(pidPath: String): String =
        "PID=\$(cat '$pidPath' 2>/dev/null); [ -n \"\$PID\" ] && kill -0 \$PID 2>/dev/null && echo ALIVE || echo DEAD"

    /**
     * Single-round-trip inspection of a (possibly orphaned) job used only during
     * service/process-recreation recovery — never during a normal live transfer, which
     * instead holds one long blocking ADB call open via [blockingWaitAndReadStatusCommand].
     * Prints exactly two lines:
     *   ALIVE:1|0   — whether the worker's PID is still running
     *   STATUS:...  — the raw contents of the job's status file (may be blank)
     */
    fun inspectJobCommand(jobId: String): String {
        val jobDir = "$TRANSFER_WORK_DIR/$jobId"
        return buildString {
            appendLine("PID=\$(cat '$jobDir/pid' 2>/dev/null)")
            appendLine("if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then echo ALIVE:1; else echo ALIVE:0; fi")
            append("echo \"STATUS:\$(cat '$jobDir/status' 2>/dev/null)\"")
        }
    }

    // Best-effort diagnostics read: returns the tail of worker.log so a failed
    // TriggerTransferResult can carry a concise reason beyond the bare status
    // token (e.g. the captured cp/tar/lz4 stderr emitted via `cat` in the worker).
    fun readWorkerLogTailCommand(jobId: String, maxBytes: Int = 4000): String =
        "tail -c $maxBytes '$TRANSFER_WORK_DIR/$jobId/worker.log' 2>/dev/null"

    fun generateJobId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * Lists a shallow directory view for the USB mount or one of its subdirectories.
     *
     * Each entry is emitted as one line in the stable pipe-delimited format:
     *
     *   <TYPE>|<SIZE>|<ABSOLUTE_PATH>
     *
     * - TYPE : "regular file" or "directory" (from stat %F)
     * - SIZE : byte count ("0" for directories)
     * - PATH : absolute path
     *
     * The pipe character `|` is used as the delimiter because it cannot appear
     * in a Linux file path and requires no shell escaping.
     *
     * "STATUS:DONE" is emitted only when `find` exits with code 0 (via `&&`).
     * If the USB is removed mid-listing, `find` fails and the sentinel is absent,
     * allowing the caller to distinguish a complete listing from a truncated one.
     *
     * .partial directories (named .<stem>.partial, used as transfer staging areas)
     * are **pruned** by `-name '.*\.partial' -prune` so neither the directory
     * itself nor any of its contents appear in the output.  They represent
     * incomplete transfers and must never appear as successful exports.
     *
     * maxDepth defaults to 4: export-dir/(session-dir)/(stem-dir)/(files) is three
     * levels below HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER, so depth 4 covers the full export tree without
     * recursing into the entire USB volume.
     */
    fun deleteAllTraceMateFilesCommand(usbMount: UsbMount): String {
        val base = "${usbMount.mountPath}/$HU_EXPORT_DIRECTORY"
        return buildString {
            // A mount-point directory remains after USB removal, so never delete until the
            // originally detected device is confirmed to still be mounted read-write.
            append(mountValidationSnippet(usbMount, missingReason = "USB_DISCONNECTED"))
            appendLine("if [ \"\$_USB_CHECK\" != 'OK' ]; then echo \"TRACEMATE_DELETE:FAILED:\$_USB_CHECK\"; exit 0; fi")
            appendLine("TARGET='$base'")
            appendLine("if [ ! -e \"\$TARGET\" ]; then echo 'TRACEMATE_DELETE:ALREADY_ABSENT'; exit 0; fi")
            appendLine("if [ -L \"\$TARGET\" ] || [ ! -d \"\$TARGET\" ]; then echo 'TRACEMATE_DELETE:FAILED:TARGET_INVALID'; exit 0; fi")
            appendLine("if ! rm -rf -- \"\$TARGET\"; then echo 'TRACEMATE_DELETE:FAILED:REMOVE_FAILED'; exit 0; fi")
            appendLine("if ! sync; then echo 'TRACEMATE_DELETE:FAILED:SYNC_FAILED'; exit 0; fi")
            append(mountValidationSnippet(usbMount, missingReason = "USB_DISCONNECTED"))
            appendLine("if [ \"\$_USB_CHECK\" != 'OK' ]; then echo \"TRACEMATE_DELETE:FAILED:\$_USB_CHECK\"; exit 0; fi")
            append("if [ -e \"\$TARGET\" ]; then echo 'TRACEMATE_DELETE:FAILED:POSTCONDITION_FAILED'; else echo 'TRACEMATE_DELETE:SUCCESS'; fi")
        }
    }

    fun listTraceMateDirectoryCommand(
        usbMount: UsbMount,
        relativeDirectory: String
    ): String {
        require(
            relativeDirectory.isEmpty() ||
                (
                    !relativeDirectory.startsWith("/") &&
                        relativeDirectory
                            .split("/")
                            .all { segment ->
                                segment.isNotBlank() &&
                                    segment != "." &&
                                    segment != ".." &&
                                    segment.all { char ->
                                        char.isLetterOrDigit() ||
                                            char == '_' ||
                                            char == '-' ||
                                            char == '.' ||
                                            char == ' '
                                    }
                            }
                    )
        ) { "Unsafe USB directory path: $relativeDirectory" }

        val mountPath = usbMount.mountPath
        val targetDirectory = if (relativeDirectory.isEmpty()) mountPath else "$mountPath/$relativeDirectory"

        return buildString {
            append("[ -d '$targetDirectory' ] || { ")
            append("echo 'STATUS:NOT_FOUND'; exit 0; }; ")

            append("find '$targetDirectory' ")
            append("-mindepth 1 -maxdepth 1 ")
            append("\\( -name '*.partial' -o -name '.*.partial' \\) -prune -o ")
            append("\\( -type f -o -type d \\) ")
            append("-exec stat -c '%F|%s|%n' '{}' \\; ")
            append("2>/dev/null && echo 'STATUS:DONE'")
        }
    }

    fun listTraceMateFilesCommand(usbMount: UsbMount, maxDepth: Int = 4): String {
        val base = "${usbMount.mountPath}/$HU_EXPORT_DIRECTORY"
        return "find '$base' -mindepth 1 -maxdepth $maxDepth " +
            "-name '.*\\.partial' -prune -o " +
            "\\( -type f -o -type d \\) " +
            "-exec stat -c '%F|%s|%n' '{}' \\; 2>/dev/null && echo STATUS:DONE"
    }
}
