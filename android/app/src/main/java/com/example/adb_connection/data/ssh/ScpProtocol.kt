package com.example.adb_connection.data.ssh

import java.io.IOException
import java.io.InputStream

internal object ScpProtocol {
    fun shellQuote(argument: String): String {
        require(argument.isNotEmpty()) { "SCP path must not be empty" }
        require(!argument.contains('\u0000') && !argument.contains('\n') && !argument.contains('\r')) {
            "SCP path contains an unsafe control character"
        }
        return "'${argument.replace("'", "'\"'\"'")}'"
    }

    fun fileName(fileName: String): String {
        require(fileName.isNotBlank() && fileName != "." && fileName != "..") { "Invalid SCP file name" }
        require(fileName.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }) {
            "SCP file name contains an unsafe character"
        }
        return fileName
    }

    fun readAck(input: InputStream) {
        when (val code = input.read()) {
            0 -> Unit
            1, 2 -> throw IOException("SCP remote error: ${readLine(input)}")
            -1 -> throw IOException("SCP connection closed while waiting for acknowledgement")
            else -> throw IOException("Invalid SCP acknowledgement byte: $code")
        }
    }

    fun readFileHeader(input: InputStream): ScpFileHeader {
        when (val code = input.read()) {
            'C'.code -> {
                val fields = readLine(input).split(' ', limit = 3)
                if (fields.size != 3 || !fields[0].matches(Regex("0[0-7]{3}"))) {
                    throw IOException("Invalid SCP file header")
                }
                val size = fields[1].toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw IOException("Invalid SCP file size")
                val fileName = try {
                    fileName(fields[2])
                } catch (e: IllegalArgumentException) {
                    throw IOException(e.message, e)
                }
                return ScpFileHeader(size, fileName)
            }
            1, 2 -> throw IOException("SCP remote error: ${readLine(input)}")
            -1 -> throw IOException("SCP connection closed while waiting for file header")
            else -> throw IOException("Unexpected SCP message: $code")
        }
    }

    fun header(size: Long, fileName: String): ByteArray {
        require(size >= 0) { "SCP file size must not be negative" }
        return "C0644 $size ${fileName(fileName)}\n".toByteArray(Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value == -1) throw IOException("SCP connection closed while reading message")
            if (value == '\n'.code) return bytes.toByteArray().toString(Charsets.UTF_8)
            if (bytes.size >= 8_192) throw IOException("SCP message is too long")
            bytes += value.toByte()
        }
    }
}

internal data class ScpFileHeader(val size: Long, val fileName: String)
