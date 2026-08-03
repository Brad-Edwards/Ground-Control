---
id: GC-J011
title: "ADR Compliance & Coverage Reporting"
status: DRAFT
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-14T19:46:03.616446Z
updated_at: 2026-03-14T19:46:03.616446Z
---

# GC-J011 — ADR Compliance & Coverage Reporting

## Statement

The system shall support ADR compliance and coverage reporting including: ADRs by status, ADRs without linked requirements, ADRs without enforcement mechanisms (extending J006), ADR age analysis (time since last review), and ADR decision velocity (decisions per period). Reports queryable via REST API and MCP tools.

## Rationale

J006 covers enforcement coverage (ADRs without tests), but broader ADR health requires lifecycle analysis. ADRs without linked requirements may be orphaned decisions. ADR age analysis identifies decisions that predate significant architecture changes and may need re-evaluation. Decision velocity is a leading indicator of architecture governance health.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#289` (GC-J011: ADR Compliance & Coverage Reporting)
