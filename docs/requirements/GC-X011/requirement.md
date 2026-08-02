---
id: GC-X011
title: "Real-time ingest of captured observations"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:14:23.921221Z
updated_at: 2026-04-12T19:14:23.921221Z
---

# GC-X011 — Real-time ingest of captured observations

## Statement

An observation captured via the agent capture primitive shall be integrated into the knowledge base promptly enough that a subsequent agent invocation in the same repository, occurring within the same working session, shall be able to consult the resulting knowledge page. Latency from capture to availability shall be measured in seconds under normal conditions, not hours or days.

## Rationale

The value of a newly captured lesson is highest immediately after capture — the same session, the same developer, the same agent run often surfaces the need for the lesson again within minutes. Deferring integration to a scheduled batch destroys most of the compounding benefit. Real-time ingest is what turns the knowledge base from a passive archive into an active working memory for the agents.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#523` (Knowledge system 2/6: capture primitive and real-time ingest engine)
