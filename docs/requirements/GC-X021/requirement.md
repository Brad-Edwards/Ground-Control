---
id: GC-X021
title: "Cold-path extraction from merged pull requests"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-04-12T19:15:22.343081Z
updated_at: 2026-04-12T19:15:22.343081Z
---

# GC-X021 — Cold-path extraction from merged pull requests

## Statement

Scheduled processing shall extract lessons from merged pull requests that were not captured through the real-time capture primitive, including review comments, fix-commit messages, and related continuous integration outcomes.

## Rationale

Not every pull request is merged through the /implement workflow. Some are merged via the web UI, some are merged by processes that do not call the capture primitive, some are merged by users who never had an agent in session. Cold-path extraction from merged pull request metadata captures the knowledge from those paths and prevents the knowledge base from becoming dependent on perfect agent discipline.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#526` (Knowledge system 5/6: scheduled processing and cold-path extraction)
