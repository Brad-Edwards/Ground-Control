---
id: GC-L010
title: "MCP Questionnaires"
status: DRAFT
type: INTERFACE
priority: MUST
wave: 5
created_at: 2026-05-18T05:29:23.243252Z
updated_at: 2026-05-18T05:29:49.774293Z
---

# GC-L010 — MCP Questionnaires

## Statement

When the backend exposes a first-class questionnaire aggregate (definitions, responses, scoring, and lifecycle), the system shall expose CRUD and lifecycle operations for those entities as MCP tools with feature parity to the REST API. The MCP surface shall use the consolidated, action-discriminated style established by ADR-035, shall reuse mcp/ground-control/lib.js transport, error envelope, and authentication helpers, and shall not store questionnaire answers, scores, or definitions inside generic metadata fields on other aggregates as a substitute for the missing first-class entity.

## Rationale

GC-L006 originally enumerated "questionnaires" as an in-scope MCP CRUD term. Empirical inventory at issue #218 showed that the backend has no first-class questionnaire aggregate — there is no JPA entity, no controller, no service, and no migration covering questionnaire definitions or responses. Codex architecture preflight (architecture/notes/mcp-grc-entity-crud-preflight.md) ruled that the MCP surface cannot satisfy parity against a non-existent REST aggregate and explicitly forbade tunneling questionnaire data through metadata fields on other aggregates. This requirement carries the future MCP exposure forward to the wave where the backend questionnaire aggregate decision lands.
