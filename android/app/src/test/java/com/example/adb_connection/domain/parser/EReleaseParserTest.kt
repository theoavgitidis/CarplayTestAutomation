package com.example.adb_connection.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EReleaseParserTest {

    // ── discoverTimestampColumn — known columns ──────────────────────────────────

    @Test
    fun `discovers last_changed from real headunit schema`() {
        val pragma = """
            0|id|INTEGER|0||1
            1|partition_path|VARCHAR|1||0
            2|last_changed|DATETIME|0|CURRENT_TIMESTAMP|0
            3|logical_block_id|UNSIGNED TINYINT|1||0
            4|part_number|VARCHAR[10]|0||0
            5|supplier_id|UNSIGNED SMALLINT|0|270|0
            6|sw_version_year|UNSIGNED TINYINT|0||0
            7|sw_version_week|UNSIGNED TINYINT|0||0
            8|sw_version_patchlevel|UNSIGNED TINYINT|0||0
            9|state_flags|SMALLINT|1||0
            10|programming_user_id|VARCHAR[32]|0|'00000000000000000000000000000000'|0
            11|programming_year|UNSIGNED TINYINT|0|0|0
            12|programming_month|UNSIGNED TINYINT|0|0|0
            13|programming_day|UNSIGNED TINYINT|0|0|0
            14|logical_block_state|UNSIGNED TINYINT|0|3|0
            15|build_number|varchar(8)|0||0
            16|e_release|varchar(8)|0||0
            17|system_revision|varchar(64)|0||0
            18|ota_installation|UNSIGNED TINYINT|0|0|0
        """.trimIndent()
        assertEquals("last_changed", EReleaseParser.discoverTimestampColumn(pragma))
    }

    @Test
    fun `discovers updated_at as timestamp column`() {
        val pragma = """
            0|id|INTEGER|0||1
            1|logical_block_id|INTEGER|0||0
            2|e_release|TEXT|0||0
            3|build_number|TEXT|0||0
            4|updated_at|TEXT|0||0
            5|logical_block_state|INTEGER|0||0
        """.trimIndent()
        assertEquals("updated_at", EReleaseParser.discoverTimestampColumn(pragma))
    }

    @Test
    fun `discovers created_at when updated_at is absent`() {
        val pragma = """
            0|id|INTEGER|0||1
            1|logical_block_id|INTEGER|0||0
            2|e_release|TEXT|0||0
            3|created_at|TEXT|0||0
        """.trimIndent()
        assertEquals("created_at", EReleaseParser.discoverTimestampColumn(pragma))
    }

    @Test
    fun `discovers install_date column`() {
        val pragma = """
            0|id|INTEGER|0||1
            1|logical_block_id|INTEGER|0||0
            2|e_release|TEXT|0||0
            3|install_date|TEXT|0||0
        """.trimIndent()
        assertEquals("install_date", EReleaseParser.discoverTimestampColumn(pragma))
    }

    @Test
    fun `discovers timestamp column`() {
        val pragma = """
            0|id|INTEGER|0||1
            1|e_release|TEXT|0||0
            2|timestamp|INTEGER|0||0
        """.trimIndent()
        assertEquals("timestamp", EReleaseParser.discoverTimestampColumn(pragma))
    }

    @Test
    fun `priority order — last_changed wins over updated_at and created_at`() {
        val pragma = """
            0|id|INTEGER|0||1
            1|created_at|TEXT|0||0
            2|updated_at|TEXT|0||0
            3|last_changed|DATETIME|0||0
        """.trimIndent()
        assertEquals("last_changed", EReleaseParser.discoverTimestampColumn(pragma))
    }

    @Test
    fun `priority order — updated_at wins over created_at`() {
        val pragma = """
            0|id|INTEGER|0||1
            1|created_at|TEXT|0||0
            2|updated_at|TEXT|0||0
        """.trimIndent()
        assertEquals("updated_at", EReleaseParser.discoverTimestampColumn(pragma))
    }

    @Test
    fun `returns null when no timestamp column exists`() {
        val pragma = """
            0|id|INTEGER|0||1
            1|logical_block_id|INTEGER|0||0
            2|e_release|TEXT|0||0
            3|build_number|TEXT|0||0
        """.trimIndent()
        assertNull(EReleaseParser.discoverTimestampColumn(pragma))
    }

    @Test
    fun `returns null for empty output`() {
        assertNull(EReleaseParser.discoverTimestampColumn(""))
    }

    @Test
    fun `returns null for error output`() {
        assertNull(EReleaseParser.discoverTimestampColumn("Error: no such table: logical_block"))
    }

    @Test
    fun `column name matching is case-insensitive`() {
        val pragma = "0|id|INTEGER|0||1\n1|Updated_At|TEXT|0||0"
        assertEquals("updated_at", EReleaseParser.discoverTimestampColumn(pragma))
    }

    // ── buildEReleaseQuery — structure ───────────────────────────────────────────

    @Test
    fun `query targets logical_block_id = 0`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertTrue(query.contains("logical_block_id = 0"))
    }

    @Test
    fun `query uses discovered timestamp column for ordering`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertTrue(query.contains("ORDER BY updated_at DESC, id DESC"))
    }

    @Test
    fun `query uses install_date when discovered`() {
        val query = EReleaseParser.buildEReleaseQuery("install_date")
        assertTrue(query.contains("ORDER BY install_date DESC, id DESC"))
    }

    @Test
    fun `query filters for valid e_release format`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertTrue(query.contains("e_release IS NOT NULL"))
        assertTrue(query.contains("e_release LIKE char(69)"))
        assertTrue(query.contains("INSTR(e_release, char(46)) > 1"))
    }

    @Test
    fun `query limits to single result`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertTrue(query.contains("LIMIT 1"))
    }

    @Test
    fun `query targets inventory db`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertTrue(query.contains("HEAD_UNIT_INVENTORY_DB_PLACEHOLDER"))
    }

    @Test
    fun `query does not sort by e_release or build_number`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertFalse(query.contains("ORDER BY") && query.contains("e_release DESC"))
        assertFalse(query.contains("build_number DESC"))
    }

    @Test
    fun `query sanitizes single quotes from column name`() {
        val query = EReleaseParser.buildEReleaseQuery("updated'_at")
        assertFalse(query.contains("'_at"))
        assertTrue(query.contains("updated_at DESC"))
    }

    // ── buildFallbackQuery — structure ───────────────────────────────────────────

    @Test
    fun `fallback query uses id DESC only`() {
        val query = EReleaseParser.buildFallbackQuery()
        assertTrue(query.contains("ORDER BY id DESC"))
        assertFalse(query.contains("updated_at"))
    }

    @Test
    fun `fallback query targets logical_block_id = 0`() {
        val query = EReleaseParser.buildFallbackQuery()
        assertTrue(query.contains("logical_block_id = 0"))
    }

    // ── isValidERelease ──────────────────────────────────────────────────────────

    @Test
    fun `valid E-Release format accepted`() {
        assertTrue(EReleaseParser.isValidERelease("E355.1103"))
    }

    @Test
    fun `valid E-Release with single digit minor`() {
        assertTrue(EReleaseParser.isValidERelease("E400.1"))
    }

    @Test
    fun `rejects missing E prefix`() {
        assertFalse(EReleaseParser.isValidERelease("355.1103"))
    }

    @Test
    fun `rejects missing dot`() {
        assertFalse(EReleaseParser.isValidERelease("E3551103"))
    }

    @Test
    fun `rejects empty string`() {
        assertFalse(EReleaseParser.isValidERelease(""))
    }

    @Test
    fun `rejects error messages`() {
        assertFalse(EReleaseParser.isValidERelease("Error: no such table"))
    }

    @Test
    fun `rejects e_release with trailing text`() {
        assertFalse(EReleaseParser.isValidERelease("E355.1103-extra"))
    }

    @Test
    fun `accepts with surrounding whitespace`() {
        assertTrue(EReleaseParser.isValidERelease("  E355.1103  "))
    }

    // ── extractERelease ──────────────────────────────────────────────────────────

    @Test
    fun `extracts single valid line`() {
        assertEquals("E355.1103", EReleaseParser.extractERelease("E355.1103"))
    }

    @Test
    fun `extracts first valid line from multi-line output`() {
        val output = "E355.1103\nE354.999"
        assertEquals("E355.1103", EReleaseParser.extractERelease(output))
    }

    @Test
    fun `skips invalid lines and extracts valid one`() {
        val output = "Error: database locked\nE355.1103"
        assertEquals("E355.1103", EReleaseParser.extractERelease(output))
    }

    @Test
    fun `returns null for all-invalid output`() {
        assertNull(EReleaseParser.extractERelease("Error: no such table"))
    }

    @Test
    fun `extractERelease returns null for empty output`() {
        assertNull(EReleaseParser.extractERelease(""))
    }

    @Test
    fun `returns null for blank output`() {
        assertNull(EReleaseParser.extractERelease("   \n  "))
    }

    @Test
    fun `trims whitespace from extracted value`() {
        assertEquals("E355.1103", EReleaseParser.extractERelease("  E355.1103  "))
    }

    // ── Scenario: multiple logical_block_id=0 records, date determines winner ────
    // (SQL does ORDER BY timestamp DESC LIMIT 1; these test the Kotlin parser's
    //  ability to validate what comes back from that single-row result)

    @Test
    fun `newer record with lower E-Release wins — parser accepts any valid format`() {
        // If the DB returns E354.500 because it's the newest record by date,
        // even though E355.1103 exists in a different logical_block_id,
        // the parser must accept it.
        assertEquals("E354.500", EReleaseParser.extractERelease("E354.500"))
    }

    @Test
    fun `parser does not re-sort — trusts SQL ordering`() {
        // The SQL does LIMIT 1 with ORDER BY timestamp DESC.
        // The parser just validates — it doesn't pick the "highest" version.
        val output = "E354.500"
        assertEquals("E354.500", EReleaseParser.extractERelease(output))
    }

    // ── SCHEMA_QUERY constant ────────────────────────────────────────────────────

    @Test
    fun `SCHEMA_QUERY targets inventory db`() {
        assertTrue(EReleaseParser.SCHEMA_QUERY.contains("HEAD_UNIT_INVENTORY_DB_PLACEHOLDER"))
    }

    @Test
    fun `SCHEMA_QUERY uses PRAGMA table_info`() {
        assertTrue(EReleaseParser.SCHEMA_QUERY.contains("PRAGMA table_info(logical_block)"))
    }
}
