---
id: GC-E010
title: "Source File UID Scanning"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T17:23:15.642807Z
updated_at: 2026-03-14T17:23:15.642807Z
---

# GC-E010 — Source File UID Scanning

## Statement

The system shall support scanning a configurable set of source file paths for requirement UID references (matching the project's UID pattern, e.g., GC-xxxx), and shall surface discovered references as candidate traceability links with artifact type CODE_FILE and link type IMPLEMENTS. Scanning shall be invokable via both REST API and MCP tools, and results shall distinguish between confirmed links (already in the system) and candidate links (newly discovered, pending confirmation).

## Rationale

StrictDoc automatically detects requirement UIDs in source code comments and creates FILE relations. GC-E006 covers test artifacts specifically. The general case — scanning any source file for UID references — is the automated traceability discovery mechanism that makes code-to-requirement linking a derivable fact rather than a manually maintained mapping. For agents, this enables autonomous traceability graph maintenance.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#228` (GC-E010: Source File UID Scanning)
