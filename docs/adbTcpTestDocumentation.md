# ADB TCP Test

> **Historical implementation note.** This page previously contained device-specific address ranges and nftables commands. The current source-backed guidance is [Android application](android-app.md), [Architecture](architecture.md), and [Operations](operations.md).

The app's TCP diagnostic checks that the configured head-unit ADB endpoint accepts a connection. A positive TCP result does not prove that the ADB handshake, authentication, or shell service will succeed.
