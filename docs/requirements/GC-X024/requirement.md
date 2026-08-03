---
id: GC-X024
title: "Health check operation for knowledge base integrity"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-04-12T19:15:41.458548Z
updated_at: 2026-04-12T19:15:41.458548Z
---

# GC-X024 — Health check operation for knowledge base integrity

## Statement

The system shall support a lint operation that evaluates the health of a knowledge base, identifying contradictions between pages, pages with no inbound references, pages whose freshness has expired, and assertions without a source citation, so that drift between the knowledge base and reality can be surfaced for correction.

## Rationale

Knowledge bases rot over time as the underlying reality changes and as individual ingest runs produce subtly inconsistent output. Without a periodic health check, small drifts accumulate until the knowledge base loses credibility. Linting for a small set of concrete integrity properties surfaces the problems early enough to be fixed before users lose trust in the knowledge.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#527` (Knowledge system 6/6: knowledge base lint pass)
