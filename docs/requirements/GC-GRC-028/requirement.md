---
id: GC-GRC-028
title: "Secrets Derivation Adapter"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T23:26:08.494817Z
updated_at: 2026-07-12T16:35:41.563876Z
---

# GC-GRC-028 — Secrets Derivation Adapter

## Statement

The system shall provide a secrets-detection derivation adapter implementing the GC-GRC-001 port.

(a) The adapter shall detect committed and historical secrets (credentials, tokens, keys, high-entropy strings) across all file types, including configuration, IaC, and source.

(b) Findings shall normalize into the facts model as secret-usage nodes and shall seed CREDENTIALS/SECRETS lattice labels (GC-GRC-006) on the data they represent.

(c) A detected live or recently-valid secret introduced by a change shall be a blocking finding through the GC-GRC-012 gate, distinct from a lattice-flow violation.

(d) The adapter shall integrate with, not duplicate, platform secret-scanning where available (for example, GitHub secret scanning), reconciling external alerts into the same facts model.

## Rationale

Leaked secrets are an immediate, high-consequence breach vector that taint/data-flow analysis does not by itself surface. Treating detected secrets as first-class facts feeds both the lattice and the blocking gate, and reconciling platform scanners avoids a parallel, divergent source of truth.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1142` (Issue #1142: GC-GRC-028 secrets derivation adapter)
