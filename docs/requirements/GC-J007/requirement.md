---
id: GC-J007
title: "ADR Lifecycle State Machine"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-14T19:45:38.474568Z
updated_at: 2026-03-14T19:45:38.474568Z
---

# GC-J007 — ADR Lifecycle State Machine

## Statement

The system shall support an ADR lifecycle state machine: proposed → accepted → deprecated → superseded. Status transitions shall be tracked with timestamp, actor, and justification. Only accepted ADRs shall be enforceable via constraint requirements (GC-J003). Deprecated and superseded ADRs shall retain full history and remain queryable.

## Rationale

An ADR without lifecycle tracking is a static document with no governance. Teams need to know whether a decision is still active, has been deprecated due to changed context, or has been superseded by a newer decision. Without lifecycle states, stale decisions silently accumulate — J006's enforcement coverage analysis cannot distinguish "unenforced because deprecated" from "unenforced because nobody got around to it." The proposed→accepted gate enables review before a decision becomes binding.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#285` (GC-J007: ADR Lifecycle State Machine)
