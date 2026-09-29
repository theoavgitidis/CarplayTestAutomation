package com.example.adb_connection.domain.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMountParserTest {

    // ── Empty / blank output ──────────────────────────────────────────────────────

    @Test
    fun `empty output returns empty list`() {
        assertTrue(UsbMountParser.parse("").isEmpty())
    }

    @Test
    fun `blank-only output returns empty list`() {
        assertTrue(UsbMountParser.parse("   \n\n  ").isEmpty())
    }

    // ── Single rw mount ───────────────────────────────────────────────────────────

    @Test
    fun `single rw mount is parsed correctly`() {
        val output = "/dev/sdb1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/SWUP|exfat|rw,relatime"
        val mounts = UsbMountParser.parse(output)
        assertEquals(1, mounts.size)
        val m = mounts[0]
        assertEquals("/dev/sdb1", m.devicePath)
        assertEquals("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/SWUP", m.mountPath)
        assertEquals("exfat", m.fileSystem)
        assertTrue("rw must be in mountOptions", "rw" in m.mountOptions)
        assertTrue(m.isWritable)
    }

    // ── Single ro mount ───────────────────────────────────────────────────────────

    @Test
    fun `single ro mount is parsed correctly`() {
        val output = "/dev/sdb1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E|vfat|ro,relatime"
        val mounts = UsbMountParser.parse(output)
        assertEquals(1, mounts.size)
        assertFalse("ro mount must not be writable", mounts[0].isWritable)
        assertTrue("ro must be in mountOptions", "ro" in mounts[0].mountOptions)
    }

    @Test
    fun `escaped mount label is decoded`() {
        val output = "/dev/sdb1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/My\\040USB|exfat|rw,relatime"

        val mount = UsbMountParser.parse(output).single()

        assertEquals("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/My USB", mount.mountPath)
    }

    @Test
    fun `vold device source is accepted`() {
        val output = "/dev/block/vold/public:8,1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|vfat|rw"

        val mount = UsbMountParser.parse(output).single()

        assertEquals("/dev/block/vold/public:8,1", mount.devicePath)
    }

    @Test
    fun `unsupported proc mounts escape is rejected`() {
        val output = "/dev/sdb1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB\\001|exfat|rw"

        assertTrue(UsbMountParser.parse(output).isEmpty())
    }

    // ── Multiple mounts ───────────────────────────────────────────────────────────

    @Test
    fun `multiple valid mounts are all parsed`() {
        val output = """
            /dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB1|exfat|rw,relatime
            /dev/sdb1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB2|vfat|ro,relatime
        """.trimIndent()
        val mounts = UsbMountParser.parse(output)
        assertEquals(2, mounts.size)
    }

    // ── isWritable derivation ─────────────────────────────────────────────────────

    @Test
    fun `isWritable is true when rw is present`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|exfat|rw,uid=0"
        assertTrue(UsbMountParser.parse(output)[0].isWritable)
    }

    @Test
    fun `isWritable is false when only ro is present`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|exfat|ro,uid=0"
        assertFalse(UsbMountParser.parse(output)[0].isWritable)
    }

    @Test
    fun `isWritable is false when mount options are contradictory`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|exfat|ro,rw"
        assertFalse(UsbMountParser.parse(output)[0].isWritable)
    }

    // ── Mount path validation ─────────────────────────────────────────────────────

    @Test
    fun `mount point outside mnt media is rejected`() {
        val output = "/dev/sda1|UNSUPPORTED_USB_MOUNT_ROOT_PLACEHOLDER/user/USB|exfat|rw"
        assertTrue("Must reject UNSUPPORTED_USB_MOUNT_ROOT_PLACEHOLDER", UsbMountParser.parse(output).isEmpty())
    }

    @Test
    fun `nested mount point is rejected`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB/sub|exfat|rw"
        assertTrue("Nested mount point must be rejected", UsbMountParser.parse(output).isEmpty())
    }

    @Test
    fun `diagnostics report rejected mount candidates`() {
        val result = UsbMountParser.parseWithDiagnostics(
            "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB/sub|exfat|rw"
        )

        assertTrue(result.mounts.isEmpty())
        assertEquals(1, result.rejectedCandidateCount)
    }

    @Test
    fun `parent tmpfs mnt media itself is rejected`() {
        val output = "/dev/tmpfs|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER|tmpfs|rw"
        assertTrue("Parent HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER must be rejected", UsbMountParser.parse(output).isEmpty())
    }

    @Test
    fun `path traversal in mount point is rejected`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/../etc|exfat|rw"
        assertTrue("Path traversal must be rejected", UsbMountParser.parse(output).isEmpty())
    }

    // ── Device path validation ────────────────────────────────────────────────────

    @Test
    fun `device path with shell metacharacters is rejected`() {
        val output = "/dev/sda1;rm -rf /|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|exfat|rw"
        assertTrue("Unsafe device path must be rejected", UsbMountParser.parse(output).isEmpty())
    }

    @Test
    fun `blank device path is rejected`() {
        val output = "|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|exfat|rw"
        assertTrue("Blank device path must be rejected", UsbMountParser.parse(output).isEmpty())
    }

    // ── Malformed lines ───────────────────────────────────────────────────────────

    @Test
    fun `line with fewer than 4 pipe-separated fields is rejected`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|exfat"
        assertTrue("Incomplete line must be rejected", UsbMountParser.parse(output).isEmpty())
    }

    @Test
    fun `line with blank filesystem is rejected`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB||rw"
        assertTrue("Blank fstype must be rejected", UsbMountParser.parse(output).isEmpty())
    }

    // ── Mixed valid and invalid ───────────────────────────────────────────────────

    @Test
    fun `valid lines are kept and invalid lines are dropped`() {
        val output = """
            /dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB1|exfat|rw
            not-a-valid-line
            /dev/sdb1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB2|vfat|ro
        """.trimIndent()
        val mounts = UsbMountParser.parse(output)
        assertEquals(2, mounts.size)
    }

    // ── Stale directory scenario ──────────────────────────────────────────────────

    @Test
    fun `stale directory with no mount entry produces empty list`() {
        // No line in /proc/mounts for HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/SWUP → empty output from awk
        assertTrue(UsbMountParser.parse("").isEmpty())
    }

    // ── mountOptions set parsing ──────────────────────────────────────────────────

    @Test
    fun `options are split on comma into a set`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|exfat|rw,relatime,uid=0,gid=0"
        val options = UsbMountParser.parse(output)[0].mountOptions
        assertTrue("rw", "rw" in options)
        assertTrue("relatime", "relatime" in options)
        assertTrue("uid=0", "uid=0" in options)
    }

    @Test
    fun `single option is stored in set correctly`() {
        val output = "/dev/sda1|HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB|exfat|rw"
        val options = UsbMountParser.parse(output)[0].mountOptions
        assertEquals(setOf("rw"), options)
    }
}
