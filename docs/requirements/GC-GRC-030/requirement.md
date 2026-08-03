---
id: GC-GRC-030
title: "Attack-Path and Threat-Chaining Analysis"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T23:26:23.430731Z
updated_at: 2026-07-12T16:35:41.563925Z
---

# GC-GRC-030 — Attack-Path and Threat-Chaining Analysis

## Statement

The system shall derive multi-step attack paths over the architecture model, beyond per-element enumeration.

(a) An attack-graph analysis shall chain element-level threats and boundary crossings into reachable paths from external entry points to high-value assets and sensitive data stores.

(b) Paths shall be computed deterministically over the model and derived facts (entry points, reachability, taint, trust levels, dependency exposure), with each path explainable as an edge chain.

(c) Paths shall carry an aggregate exposure indicator usable as a prioritization input and as a methodology input (GC-GRC-021/022); the presence of an unmitigated end-to-end path to a sensitive sink shall be a high-priority finding.

(d) Controls that break a path (choke points) shall be identifiable, so mitigation can target the path rather than only individual elements.

## Rationale

Real breaches chain steps; a list of isolated element threats misses the composed risk. Attack-graph analysis (a mature information-security technique) prioritizes by reachable end-to-end exposure and surfaces choke-point controls — turning the model into something that reasons about how an attacker actually moves, deterministically.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1144` (Issue #1144: GC-GRC-030 attack-path and threat-chaining analysis)
