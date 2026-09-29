package com.example.adb_connection.domain.model

data class UsbMount(
    val devicePath: String,
    val mountPath: String,
    val fileSystem: String,
    val mountOptions: Set<String>
) {
    val isWritable: Boolean get() = "rw" in mountOptions && "ro" !in mountOptions
}
