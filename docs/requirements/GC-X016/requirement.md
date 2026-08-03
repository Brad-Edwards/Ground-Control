---
id: GC-X016
title: "Administrative registry of monitored repositories"
status: DRAFT
type: INTERFACE
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:53.629792Z
updated_at: 2026-04-12T19:14:53.629792Z
---

# GC-X016 — Administrative registry of monitored repositories

## Statement

The system shall provide an administrative interface for registering, deregistering, listing, and inspecting the repositories that participate in scheduled knowledge base processing. Registration shall read the repository's Ground Control configuration to resolve the knowledge base location; listing shall report each registered repository along with its last-processed timestamp and processing state.

## Rationale

Scheduled processing needs to know which repositories to process. A user-facing registry surface is the administrative boundary for that list. Reading the knowledge base location from the repository's own configuration keeps the registry minimal (just paths) and keeps per-repo configuration authoritative in the repository itself.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#525` (Knowledge system 4/6: admin CLI and scheduler lifecycle)
