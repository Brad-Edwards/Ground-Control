---
id: GC-X010
title: "Knowledge base changes committed to version control"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:14.879835Z
updated_at: 2026-04-12T19:14:14.879835Z
---

# GC-X010 — Knowledge base changes committed to version control

## Statement

Knowledge base changes produced by ingest shall be recorded as commits in the repository's version control on the branch active at the time of processing. Every ingest that modifies the knowledge base shall produce at least one commit with a descriptive message identifying the source material integrated.

## Rationale

The knowledge base lives in the same repository as the code it describes. Treating ingest writes as ordinary commits gives every change a durable history, lets reviewers see knowledge updates alongside the code changes that produced them in the same pull request, and makes every update revertible via the normal git workflow. No separate audit log is needed; git is the audit log.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#523` (Knowledge system 2/6: capture primitive and real-time ingest engine)
