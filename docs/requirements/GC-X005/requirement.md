---
id: GC-X005
title: "Source citations on every knowledge claim"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:13:44.351317Z
updated_at: 2026-04-13T00:47:21.286289Z
---

# GC-X005 — Source citations on every knowledge claim

## Statement

Every claim on a knowledge page shall record its source material so that the claim can be traced back to an originating commit, pull request, review comment, or user correction. Pages shall not contain unsourced assertions.

## Rationale

Unsourced assertions drift from reality and cannot be audited or re-verified. Requiring a citation for every claim makes the knowledge base a projection of real events, surfaces contradictions when the same source produces conflicting claims, and gives the lint pass a concrete check to enforce. It also lets an agent jump from a page to the original evidence when it needs more context than the page captured.

## Traceability

- IMPLEMENTS → DOCUMENTATION `docs/knowledge/SCHEMA.md`
- IMPLEMENTS → GITHUB_ISSUE `522`
- TESTS → TEST `mcp/ground-control/lib.mergereviewerarchitecturalreads-decision-record-.test.js` (formatSourceCitation / writeKnowledgeInbox tests enforce a valid typed source citation on every knowledge claim)
