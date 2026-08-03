---
id: GC-L009
title: "MCP Third-Party / Vendor Aggregate"
status: DRAFT
type: INTERFACE
priority: MUST
wave: 5
created_at: 2026-05-18T05:29:16.729776Z
updated_at: 2026-05-18T05:29:44.866266Z
---

# GC-L009 — MCP Third-Party / Vendor Aggregate

## Statement

When the backend exposes a first-class third-party / vendor aggregate (distinct from operational assets carrying AssetType.THIRD_PARTY plus subtype/metadata, links, and external IDs), the system shall expose CRUD, link, and lifecycle operations for that aggregate as an MCP tool with feature parity to the REST API. The MCP surface shall use the consolidated, action-discriminated style established by ADR-035, shall reuse mcp/ground-control/lib.js transport, error envelope, and authentication helpers, and shall not introduce a generic write proxy or duplicate backend DTO/validation hierarchies. Until the backend aggregate exists, third-party records remain modeled via AssetType.THIRD_PARTY on gc_asset.

## Rationale

GC-L006 originally enumerated "third parties" as an in-scope MCP CRUD term. Empirical inventory at issue #218 showed that the backend has no first-class vendor/third-party aggregate (third parties are currently subsumed under AssetType.THIRD_PARTY on operational assets). Codex architecture preflight (architecture/notes/mcp-grc-entity-crud-preflight.md) ruled that missing REST aggregates must not be faked at the MCP layer — feature parity cannot be achieved against a backend aggregate that does not exist. This requirement carries the future MCP exposure of vendor management forward to the wave where the backend aggregate decision lands, without blocking GC-L006 ACTIVE on a backend dependency that itself has no requirement yet.
