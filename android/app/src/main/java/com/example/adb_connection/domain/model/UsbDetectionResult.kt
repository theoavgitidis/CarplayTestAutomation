package com.example.adb_connection.domain.model

sealed interface UsbDetectionResult {
    data class Writable(val mount: UsbMount) : UsbDetectionResult
    data class ReadOnly(val mount: UsbMount) : UsbDetectionResult
    data class MultipleWritableMounts(val mounts: List<UsbMount>) : UsbDetectionResult
    data class MultipleReadOnlyMounts(val mounts: List<UsbMount>) : UsbDetectionResult
    data class UnsupportedMountLayout(val candidateCount: Int) : UsbDetectionResult
    data class QueryFailed(val message: String?) : UsbDetectionResult
    data object NotFound : UsbDetectionResult
}
