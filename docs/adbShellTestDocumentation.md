# ADB Shell Test

> **Historical implementation note.** This page previously contained target-specific endpoint and firewall instructions. Follow [Android application](android-app.md) and [Operations](operations.md) instead.

The implemented raw client performs a fresh ADB handshake per shell command, opens one shell stream, collects output, and parses an appended exit marker. It does not support AUTH/RSA, multiplexed streams, persistent sessions, or incoming checksum verification.
