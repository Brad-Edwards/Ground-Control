---
id: GC-GRC-026
title: "GRC Artifact Storage and Exposure Policy"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:28:02.676533Z
updated_at: 2026-07-12T16:35:41.563806Z
---

# GC-GRC-026 — GRC Artifact Storage and Exposure Policy

## Statement

GRC artifacts shall be stored server-side in Ground Control by default, with repo exposure as an explicit, policy-controlled exception.

(a) Architecture models, DFDs, derived facts, threats, risks, controls, assessments, drift state, and dispositions shall persist in Ground Control's database (project-scoped, auth-gated, audited) — never required to live in the analyzed repository.

(b) Agents shall obtain GRC context through graph links and MCP/REST reads, exactly as for threat models and risks today; no workflow step shall depend on GRC content being present as repo files.

(c) Repo mirroring of any GRC artifact (for example, rendered DFD diagrams for docs) shall be opt-in per artifact class via the GRC configuration block, default off; the policy shall warn when mirroring sensitive classes in a public repository.

(d) Exported/rendered artifacts shall carry a generation marker tying them to the authoritative server-side version, so drift between a mirror and the graph is detectable.

## Rationale

Some consumer repos are public; a repo is the wrong trust boundary for threat models and data-flow maps. Server-side-by-default follows the existing GRC aggregate pattern and GC-E012's sensitive-artifact exclusion principle, while the opt-in mirror with generation markers preserves docs use-cases without forking the source of truth.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1139` (Issue #1139: GC-GRC-026 GRC artifact storage and exposure policy)
