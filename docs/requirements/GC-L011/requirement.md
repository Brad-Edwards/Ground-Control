---
id: GC-L011
title: "MCP Compliance Framework Mapping Aggregate"
status: DRAFT
type: INTERFACE
priority: MUST
wave: 5
created_at: 2026-05-18T05:29:30.576964Z
updated_at: 2026-05-30T08:06:03.567339Z
---

# GC-L011 — MCP Compliance Framework Mapping Aggregate

## Statement

When the backend exposes a first-class compliance-framework-mapping aggregate (distinct from existing audit framework link types, control-pack framework reference fields, and per-control external framework strings), the system shall expose CRUD and traversal operations for that aggregate as MCP tools with feature parity to the REST API. The MCP surface shall use the consolidated, action-discriminated style established by ADR-035, shall reuse mcp/ground-control/lib.js transport, error envelope, and authentication helpers, and shall update the relevant link-target enums, GraphTargetResolverService, and graph projection contributors in the same change that promotes framework mappings from external string identifiers to a first-class aggregate.

## Rationale

GC-L006 originally enumerated "compliance framework mappings" as an in-scope MCP CRUD term. Empirical inventory at issue #218 showed that compliance framework mappings exist today only as external FRAMEWORK/mapping fields in audit links and pack/control-pack surfaces — there is no universal mapping aggregate. Codex architecture preflight (architecture/notes/mcp-grc-entity-crud-preflight.md) ruled that the MCP surface cannot satisfy parity by inventing MCP-only persistence for a missing aggregate and identified the link-target promotion path (GraphTargetResolverService, graph projection contributor, REST DTOs, MCP enum mirror, adapter tests) that any future first-class mapping must follow. This requirement carries the future MCP exposure forward to the wave where the backend aggregate decision lands.
