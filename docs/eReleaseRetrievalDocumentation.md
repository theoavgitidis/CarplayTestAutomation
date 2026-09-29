# E-Release Retrieval

> **Historical implementation note.** Use [Android application](android-app.md) and [Operations](operations.md) for current guidance.

The implemented E-Release action uses a head-unit ADB shell query. It discovers the `logical_block` schema before selecting an eligible `e_release` record by an available timestamp column or an ID fallback. It reports no value when ADB, `sqlite3`, the database, or an eligible record is unavailable; it does not infer a release value.
