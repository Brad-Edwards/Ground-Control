---
id: GC-H007
title: "Threat Model Scope Definition"
status: DEPRECATED
type: FUNCTIONAL
priority: COULD
wave: 5
created_at: 2026-03-14T19:32:43.575489Z
updated_at: 2026-07-12T23:08:15.210480Z
---

# GC-H007 — Threat Model Scope Definition

## Statement

The system shall support defining threat model scope including operational assets, identities, data stores, service dependencies, system boundaries, trust zones, data flows, and entry points under analysis. Scope artifacts shall be linkable to architecture models, topology relationships, and requirements. The system shall provide structured data suitable for DFD-based or graph-based threat-model visualization.

## Rationale

Threat modeling without defined scope produces unfocused results. Scope definition has to include the operational objects and boundaries that the rest of the graph-native risk model will reason about.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#267` (GC-H007: Threat Model Scope Definition)
