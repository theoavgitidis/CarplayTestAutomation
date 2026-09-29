package com.example.adb_connection.domain.parser

/**
 * Discovers the date/time column in the `logical_block` table and builds a query
 * that selects the E-Release from the newest record for `logical_block_id = 0`.
 *
 * The schema is discovered at runtime via `PRAGMA table_info(logical_block)`.
 * Known timestamp column candidates (checked in priority order):
 * - `last_changed`, `updated_at`, `modified_at`, `changed_at`, `timestamp`,
 *   `created_at`, `install_date`, `date`, `time`
 *
 * On the target head unit the column is `last_changed` (DATETIME, default CURRENT_TIMESTAMP).
 * If none is found, falls back to `id DESC` only.
 */
object EReleaseParser {

    private const val INVENTORY_DB_PATH = "HEAD_UNIT_INVENTORY_DB_PLACEHOLDER"

    private val TIMESTAMP_CANDIDATES = listOf(
        "last_changed", "updated_at", "modified_at", "changed_at",
        "timestamp", "created_at", "install_date", "date", "time"
    )

    private val E_RELEASE_PATTERN = Regex("""^E\d+\.\d+$""")

    const val SCHEMA_QUERY = "sqlite3 $INVENTORY_DB_PATH 'PRAGMA table_info(logical_block);'"

    /**
     * Parses `PRAGMA table_info` output and returns the best date/time column name,
     * or null if no candidate matches.
     *
     * PRAGMA table_info format: `cid|name|type|notnull|dflt_value|pk`
     */
    fun discoverTimestampColumn(pragmaOutput: String): String? {
        val columnNames = pragmaOutput.lines()
            .filter { it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 2) parts[1].trim().lowercase() else null
            }
            .toSet()

        return TIMESTAMP_CANDIDATES.firstOrNull { it in columnNames }
    }

    /**
     * Builds the E-Release SELECT query using the discovered timestamp column.
     * Selects from `logical_block_id = 0`, orders by [timestampColumn] DESC, id DESC.
     */
    fun buildEReleaseQuery(timestampColumn: String): String {
        val safeCol = timestampColumn.replace("'", "")
        return "sqlite3 $INVENTORY_DB_PATH " +
            "'SELECT e_release FROM logical_block " +
            "WHERE logical_block_id = 0 " +
            "AND e_release IS NOT NULL AND length(trim(e_release)) > 1 " +
            "AND e_release LIKE char(69) || char(37) " +
            "AND INSTR(e_release, char(46)) > 1 " +
            "ORDER BY $safeCol DESC, id DESC " +
            "LIMIT 1;'"
    }

    /**
     * Fallback query when no timestamp column is discovered — uses id as sole ordering.
     */
    fun buildFallbackQuery(): String =
        "sqlite3 $INVENTORY_DB_PATH " +
            "'SELECT e_release FROM logical_block " +
            "WHERE logical_block_id = 0 " +
            "AND e_release IS NOT NULL AND length(trim(e_release)) > 1 " +
            "AND e_release LIKE char(69) || char(37) " +
            "AND INSTR(e_release, char(46)) > 1 " +
            "ORDER BY id DESC " +
            "LIMIT 1;'"

    /**
     * Validates that a string looks like a valid E-Release: `E<major>.<minor>`.
     */
    fun isValidERelease(value: String): Boolean =
        E_RELEASE_PATTERN.matches(value.trim())

    /**
     * Extracts the first valid E-Release value from (potentially multi-line) sqlite3 output.
     * Returns null if no valid entry found.
     */
    fun extractERelease(rawOutput: String): String? =
        rawOutput.lines()
            .map { it.trim() }
            .firstOrNull { isValidERelease(it) }
}
