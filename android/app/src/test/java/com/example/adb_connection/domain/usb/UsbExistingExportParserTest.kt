package com.example.adb_connection.domain.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbExistingExportParserTest {

    private val mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB"
    private val normalStem = "trigger_1_HU_20260701_113733_COREDUMP"
    private val offlineStem = "${normalStem}_dlt_offlinetrace"
    private val session = "$mountPath/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000"

    @Test
    fun `parses valid exports from a complete listing`() {
        val output = "$session/$normalStem\n$session/$offlineStem\nSTATUS:DONE"

        assertEquals(
            mapOf(normalStem to "$session/$normalStem", offlineStem to "$session/$offlineStem"),
            UsbExistingExportParser.parse(output, mountPath)
        )
    }

    @Test
    fun `rejects incomplete listing`() {
        assertNull(UsbExistingExportParser.parse("$session/$normalStem", mountPath))
    }

    @Test
    fun `ignores paths outside managed session format`() {
        val output = "$mountPath/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/untrusted/$normalStem\nSTATUS:DONE"

        assertEquals(emptyMap<String, String>(), UsbExistingExportParser.parse(output, mountPath))
    }
}
