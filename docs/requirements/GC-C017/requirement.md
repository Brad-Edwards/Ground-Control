---
id: GC-C017
title: "Semantic Requirement Search"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-18T07:26:20.422910Z
updated_at: 2026-03-18T07:26:20.422910Z
---

# GC-C017 — Semantic Requirement Search

## Statement

The system shall support finding requirements semantically similar to a given requirement or to arbitrary free text, returning ranked results with similarity scores. This shall be exposed via both REST API and MCP tools. When given a requirement UID, the system shall use its stored embedding; when given free text, the system shall embed the text on demand. Results shall be scoped to the active project.

## Rationale

GC-O004 requires agents to query Ground Control for related requirements before beginning implementation work, but no semantic mechanism exists to power that query. Text search matches keywords; semantic search matches meaning. An agent starting work on "user session management" should find requirements about "authentication timeout" and "persistent login" even if those exact words don't appear. Semantic search is a natural byproduct of the embedding infrastructure (GC-C015) and directly enables the "find related requirements" workflow that prevents agents from creating redundant requirements.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#679` (GC-C017: Semantic Requirement Search)
