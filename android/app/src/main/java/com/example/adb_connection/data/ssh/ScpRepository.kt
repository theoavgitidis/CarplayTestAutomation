package com.example.adb_connection.data.ssh

import java.io.File

/** SCP-only binary file transfer API. Shell commands remain the responsibility of [SshRepository]. */
interface ScpRepository {
    suspend fun download(
        target: SshTarget,
        remotePath: String,
        localFile: File,
        onProgress: (ScpTransferProgress) -> Unit = {}
    ): Result<ScpTransferResult>

    suspend fun upload(
        target: SshTarget,
        localFile: File,
        remotePath: String,
        onProgress: (ScpTransferProgress) -> Unit = {}
    ): Result<ScpTransferResult>
}

data class ScpConnectionConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String
)

fun interface ScpConnectionConfigProvider {
    suspend fun configFor(target: SshTarget): ScpConnectionConfig
}

data class ScpTransferProgress(val transferredBytes: Long, val totalBytes: Long)

data class ScpTransferResult(val transferredBytes: Long, val localFile: File)
