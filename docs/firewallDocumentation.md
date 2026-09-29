# ADB Firewall Preparation

> **Historical implementation note.** Earlier revisions of this page exposed target-specific nftables rules and address ranges. Treat those values as non-portable and do not recreate them manually from documentation.

The implemented scoped firewall workflow is an explicit, visible head-unit modification performed as part of the operator-initiated connection validation flow. It checks and adds expected tagged nftables rules, then verifies them. It must be validated for every target head-unit variant. The older broad debug action that opens the ADB port to all sources is deprecated; scoped preparation is the supported path.
