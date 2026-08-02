---
id: GC-GRC-012
title: "GRC Coverage Completion Gate"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:26:07.476020Z
updated_at: 2026-07-12T16:35:41.562807Z
---

# GC-GRC-012 — GRC Coverage Completion Gate

## Statement

The /implement completion gate shall include a blocking, server-side GRC coverage assertion.

(a) Every security-relevant touched surface (per the derivation-backed screening) shall have at least one linked ACTIVE threat-model entry covering it.

(b) Every such threat shall have at least one control at IMPLEMENTED/OPERATIONAL with CODE and efficacy-test linkage in the current change, or an authorized disposition (GC-GRC-015).

(c) The stale set from screening shall be addressed (entities refreshed) or explicitly dispositioned.

(d) The failure envelope shall enumerate the specific uncovered surface, threat, or control so the fix is obvious from the error alone, mirroring the quality-gate envelope contract.

(e) Enforcement shall be mechanical at the MCP tool layer; prose-only instructions shall not be the enforcement mechanism.

## Rationale

This is the teeth: the structural guarantee that the security model and its controls exist, are implemented, and are tested for everything a change touches. It generalizes gc_assert_grc_reconciled from verify-what-was-claimed to verify-what-was-required, closing the self-certification gap.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1125` (Issue #1125: GC-GRC-012 GRC coverage completion gate (gc_assert_grc_coverage))
