---
id: GC-X003
title: "One knowledge base per repository, never merged"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 6
created_at: 2026-04-12T19:13:28.580298Z
updated_at: 2026-04-13T00:47:18.649647Z
---

# GC-X003 — One knowledge base per repository, never merged

## Statement

Each repository shall own exactly one knowledge base, and knowledge bases shall not be merged across repositories. A repository's knowledge base is authoritative for that repository only.

## Rationale

Repositories have different conventions, vocabularies, and lifecycles. Merging knowledge across repositories produces contradictions and destroys the per-repo precision that makes the knowledge useful for agents working on that specific repo. Cross-repository knowledge is a different concern that belongs in a different layer.

## Traceability

- IMPLEMENTS → DOCUMENTATION `docs/knowledge/SCHEMA.md`
- IMPLEMENTS → GITHUB_ISSUE `522`
