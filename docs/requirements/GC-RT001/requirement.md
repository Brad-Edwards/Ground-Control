---
id: GC-RT001
title: "Adopt the external agent-runtime boundary and ownership model"
status: DRAFT
type: CONSTRAINT
priority: MUST
wave: 8
created_at: 2026-07-30T04:12:11.280427Z
updated_at: 2026-07-30T04:12:11.280427Z
---

# GC-RT001 — Adopt the external agent-runtime boundary and ownership model

## Statement

Ground Control shall define and enforce an architecture boundary in which a separately versioned Agent Deck runtime owns execution, sessions, terminals, credentials, MCP, Docker, watchers, and conductors while Ground Control owns orchestration, policy, audit, and durable business state.

## Rationale

This requirement is part of the private Agent Deck runtime program and is independently owned by the Ground Control control-plane repository.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#1484` (GC-RT001: Adopt the external agent-runtime boundary and ownership model)
