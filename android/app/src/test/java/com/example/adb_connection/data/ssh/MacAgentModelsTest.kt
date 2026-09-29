package com.example.adb_connection.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacAgentModelsTest {
    @Test
    fun `parser reads the capture job inside a successful RPC envelope`() {
        val job = parseMacAgentJob(
            """{"ok":true,"result":{"jobId":"123e4567-e89b-12d3-a456-426614174000","outputPath":"/capture.capture_tool_placeholder","state":"COMPLETED","activity":{"state":"STOPPED","captureBytes":512,"observedLiveEvents":9},"lastError":null}}"""
        )

        assertEquals("COMPLETED", job.state)
        assertEquals(512L, job.captureBytes)
        assertEquals(9, job.observedLiveEvents)
        assertNull(job.lastError)
    }

    @Test
    fun `parser reads optional artifact checksum`() {
        val job = parseMacAgentJob(
            """{"ok":true,"result":{"jobId":"123e4567-e89b-12d3-a456-426614174000","outputPath":"/capture.capture_tool_placeholder","state":"COMPLETED","activity":{},"artifact":{"sha256":"abc123"}}}"""
        )

        assertEquals("abc123", job.artifactSha256)
    }

    @Test
    fun `stopped capture is terminal for deployed agent compatibility`() {
        val job = parseMacAgentJob(
            """{"ok":true,"result":{"jobId":"123e4567-e89b-12d3-a456-426614174000","outputPath":"/capture.capture_tool_placeholder","state":"STOPPED","activity":{"state":"STOPPED"}}}"""
        )

        assertTrue(job.isTerminal)
    }

    @Test
    fun `parser reads capture jobs from a successful RPC envelope`() {
        val jobs = parseMacAgentJobs(
            """{"ok":true,"result":[{"jobId":"123e4567-e89b-12d3-a456-426614174000","outputPath":"/capture.capture_tool_placeholder","state":"RUNNING","activity":{"state":"ACTIVE","captureBytes":512,"observedLiveEvents":9}},{"jobId":"223e4567-e89b-12d3-a456-426614174000","outputPath":"/complete.capture_tool_placeholder","state":"COMPLETED","activity":{"state":"STOPPED"}}]}"""
        )

        assertEquals(2, jobs.size)
        assertEquals("RUNNING", jobs.first().state)
        assertEquals(512L, jobs.first().captureBytes)
        assertEquals("COMPLETED", jobs.last().state)
    }
}
