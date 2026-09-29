package com.example.adb_connection.ui.home

import com.example.adb_connection.domain.parser.EReleaseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-style tests verifying the full E-Release query flow:
 * schema discovery → query construction → output parsing.
 */
class EReleaseQueryTest {

    // ── Schema discovery → query building ────────────────────────────────────────

    @Test
    fun `schema with updated_at produces query sorting by updated_at DESC, id DESC`() {
        val pragma = "0|id|INTEGER|0||1\n1|logical_block_id|INTEGER|0||0\n2|e_release|TEXT|0||0\n3|updated_at|TEXT|0||0"
        val col = EReleaseParser.discoverTimestampColumn(pragma)
        assertEquals("updated_at", col)

        val query = EReleaseParser.buildEReleaseQuery(col!!)
        assertTrue(query.contains("ORDER BY updated_at DESC, id DESC"))
        assertTrue(query.contains("logical_block_id = 0"))
    }

    @Test
    fun `schema without timestamp falls back to id-only ordering`() {
        val pragma = "0|id|INTEGER|0||1\n1|e_release|TEXT|0||0\n2|build_number|TEXT|0||0"
        val col = EReleaseParser.discoverTimestampColumn(pragma)
        assertNull(col)

        val query = EReleaseParser.buildFallbackQuery()
        assertTrue(query.contains("ORDER BY id DESC"))
        assertFalse(query.contains("updated_at"))
    }

    // ── Query does NOT use e_release or build_number for ordering ─────────────────

    @Test
    fun `generated query never sorts by e_release`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        val orderClause = query.substringAfter("ORDER BY")
        assertFalse("e_release must not appear in ORDER BY", orderClause.contains("e_release"))
    }

    @Test
    fun `generated query never sorts by build_number`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        val orderClause = query.substringAfter("ORDER BY")
        assertFalse("build_number must not appear in ORDER BY", orderClause.contains("build_number"))
    }

    // ── Query filters ────────────────────────────────────────────────────────────

    @Test
    fun `query filters for logical_block_id = 0`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertTrue(query.contains("logical_block_id = 0"))
        assertFalse("must not use logical_block_state", query.contains("logical_block_state"))
    }

    @Test
    fun `query selects only e_release column`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertTrue(query.contains("SELECT e_release FROM"))
    }

    @Test
    fun `query does not include build_number in SELECT`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        val selectClause = query.substringAfter("SELECT").substringBefore("FROM")
        assertFalse(selectClause.contains("build_number"))
    }

    // ── Output parsing end-to-end ────────────────────────────────────────────────

    @Test
    fun `valid single-row output is accepted`() {
        assertEquals("E355.1103", EReleaseParser.extractERelease("E355.1103\n"))
    }

    @Test
    fun `older record with higher E-Release is accepted if SQL returns it`() {
        // This tests that the parser does NOT re-sort — it trusts the SQL ordering.
        // If the DB says E354.500 is the newest by timestamp, we accept it.
        assertEquals("E354.500", EReleaseParser.extractERelease("E354.500"))
    }

    @Test
    fun `invalid output like error messages returns null`() {
        assertNull(EReleaseParser.extractERelease("Error: database is locked"))
    }

    // ── Full flow simulation ─────────────────────────────────────────────────────

    @Test
    fun `full flow — discover schema, build query, validate output`() {
        // 1. Schema discovery
        val pragma = """
            0|id|INTEGER|0||1
            1|logical_block_id|INTEGER|0||0
            2|e_release|TEXT|0||0
            3|build_number|TEXT|0||0
            4|updated_at|TEXT|0||0
            5|logical_block_state|INTEGER|0||0
        """.trimIndent()
        val col = EReleaseParser.discoverTimestampColumn(pragma)
        assertEquals("updated_at", col)

        // 2. Build query
        val query = EReleaseParser.buildEReleaseQuery(col!!)
        assertTrue(query.contains("ORDER BY updated_at DESC, id DESC"))
        assertTrue(query.contains("logical_block_id = 0"))
        assertTrue(query.contains("LIMIT 1"))

        // 3. Validate output
        val dbOutput = "E355.1103"
        val eRelease = EReleaseParser.extractERelease(dbOutput)
        assertEquals("E355.1103", eRelease)
    }

    @Test
    fun `full flow with identical timestamps — id breaks tie`() {
        // When two records have the same timestamp, ORDER BY updated_at DESC, id DESC
        // ensures the higher id wins. The parser just validates the single-row result.
        val pragma = "0|id|INTEGER|0||1\n1|updated_at|TEXT|0||0\n2|e_release|TEXT|0||0"
        val col = EReleaseParser.discoverTimestampColumn(pragma)!!
        val query = EReleaseParser.buildEReleaseQuery(col)

        // Verify id DESC is the tiebreaker after the timestamp
        assertTrue(query.contains("updated_at DESC, id DESC"))

        // The DB would return exactly one row — the one with higher id
        assertEquals("E400.1", EReleaseParser.extractERelease("E400.1"))
    }

    @Test
    fun `full flow with failed ADB connection — schema returns error`() {
        val errorOutput = "error: closed"
        val col = EReleaseParser.discoverTimestampColumn(errorOutput)
        assertNull("error output must not yield a column", col)
    }

    // ── No user-interpolated values ──────────────────────────────────────────────

    @Test
    fun `queries use no string interpolation from user input`() {
        val query = EReleaseParser.buildEReleaseQuery("updated_at")
        assertFalse(query.contains("\${"))
        assertFalse(query.contains("%s"))
    }

    @Test
    fun `SCHEMA_QUERY is fully static`() {
        assertFalse(EReleaseParser.SCHEMA_QUERY.contains("\${"))
    }
}
