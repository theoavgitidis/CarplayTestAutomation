package com.example.adb_connection.domain.model

/** Sealed set of terminal-ish shapes a [TriggerTransferResult] can take, for compact persistence. */
enum class ResultKind { SUCCESS, FAILED, CANCELLED, ALREADY_PRESENT }

/**
 * Compact, pure (no Android dependency) summary of a [TriggerTransferResult], suitable for
 * persisting to disk as part of an [ActiveTransferSnapshot] and reconstructing later given the
 * originating [TriggerArchive]. Deliberately flat — only the fields actually needed to
 * reconstruct each result variant are present; unused fields are null for a given [kind].
 */
data class ResultSummary(
    val kind: ResultKind,
    val path: String? = null,
    val reason: FailureReason? = null,
    val message: String? = null,
    val partialOutputRemoved: Boolean? = null,
    val sourceType: SourceType? = null
)

/** Reduces a live result to its compact, persistable form. */
fun TriggerTransferResult.toSummary(): ResultSummary = when (this) {
    is TriggerTransferResult.Success -> ResultSummary(
        kind = ResultKind.SUCCESS,
        path = destinationPath,
        sourceType = sourceType
    )
    is TriggerTransferResult.Failed -> ResultSummary(
        kind = ResultKind.FAILED,
        reason = reason,
        message = message
    )
    is TriggerTransferResult.Cancelled -> ResultSummary(
        kind = ResultKind.CANCELLED,
        partialOutputRemoved = partialOutputRemoved
    )
    is TriggerTransferResult.AlreadyPresent -> ResultSummary(
        kind = ResultKind.ALREADY_PRESENT,
        path = existingPath
    )
}

/** Rehydrates a full result given the archive it belongs to (archive itself is never persisted twice). */
fun ResultSummary.toResult(archive: TriggerArchive): TriggerTransferResult = when (kind) {
    ResultKind.SUCCESS -> TriggerTransferResult.Success(
        archive,
        destinationPath = path.orEmpty(),
        sourceType = sourceType ?: SourceType.ARCHIVE_EXTRACTED_TO_USB
    )
    ResultKind.FAILED -> TriggerTransferResult.Failed(
        archive,
        reason = reason ?: FailureReason.UNKNOWN,
        message = message
    )
    ResultKind.CANCELLED -> TriggerTransferResult.Cancelled(
        archive,
        partialOutputRemoved = partialOutputRemoved ?: false
    )
    ResultKind.ALREADY_PRESENT -> TriggerTransferResult.AlreadyPresent(
        archive,
        existingPath = path.orEmpty()
    )
}
