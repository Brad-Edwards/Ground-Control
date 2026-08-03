---
id: GC-CLD-1
title: "Contract-Locked Development Method Authority"
status: DEPRECATED
type: CONSTRAINT
priority: MUST
wave: 9
created_at: 2026-07-04T02:45:19.486135Z
updated_at: 2026-07-28T03:05:16.656901Z
---

# GC-CLD-1 — Contract-Locked Development Method Authority

## Statement

Ground Control shall adopt Contract-Locked Development (CLD) as a binding development methodology through an accepted program ADR. The method authority shall define the three separated powers (design authority, implementer, verifier), the boundary contract stack, lock levels, oracle battery composition, implementation sandbox rules, specification lifecycle, and risk-scored escalation model. The method shall state that CLD operationalizes ADR-012 per boundary and that ADR-082 is the first reference instance of the contract surface rather than a duplicate schema system.

## Rationale

Issue #1291 turns the reviewed CLD research packet into governance. Without a binding ADR and requirement anchor, later implementation issues have no authority to check plans against and no traceability target for the method's enforceable properties.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1291` (ADR: contract-locked development methodology)
- DOCUMENTS → ADR `architecture/adrs/087-contract-locked-development-methodology.md` (ADR-087: Contract-Locked Development Methodology)
- DOCUMENTS → ADR `ADR-087` (ADR-087: Contract-Locked Development Methodology)
- IMPLEMENTS → ADR `architecture/adrs/087-contract-locked-development-methodology.md` (ADR-087: Contract-Locked Development Methodology)
- IMPLEMENTS → ADR `ADR-087` (ADR-087: Contract-Locked Development Methodology)
- IMPLEMENTS → GITHUB_ISSUE `1291` (ADR: contract-locked development methodology)
