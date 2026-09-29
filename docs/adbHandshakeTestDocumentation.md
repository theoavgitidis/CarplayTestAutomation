# ADB Handshake Test

> **Historical implementation note.** This page previously documented a device-specific ADB endpoint and manual test procedure. Use [Android application](android-app.md), [Architecture](architecture.md), and [Operations](operations.md) for current behavior.

The implemented raw ADB client opens a TCP connection, sends `CNXN`, and reports whether the target responds with `CNXN`, `AUTH`, or another packet. `AUTH` is an expected unsupported state because Android RSA authentication is not implemented. The ADB endpoint is target configuration, not a portable documented address.
