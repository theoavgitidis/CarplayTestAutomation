package com.example.adb_connection.data.ssh

import android.net.Network

enum class SshTarget {
    HEADUNIT,
    MAC
}

fun interface SshNetworkProvider {
    fun networkFor(target: SshTarget): Network?
}

class SshNetworkUnavailableException(target: SshTarget) : IllegalStateException(
    "Required network for SSH target $target is unavailable"
)
