package com.example.adb_connection.data.settings

import kotlinx.coroutines.flow.Flow

interface SshHostProvider {
    val sshHost: Flow<String>
}
