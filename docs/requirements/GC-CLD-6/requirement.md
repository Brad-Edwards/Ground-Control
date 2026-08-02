---
id: GC-CLD-6
title: "CLD Pilot on Contracted Temporal Activities"
status: DRAFT
type: FUNCTIONAL
priority: MUST
wave: 9
created_at: 2026-07-04T02:46:12.692416Z
updated_at: 2026-07-04T02:46:12.692416Z
---

# GC-CLD-6 — CLD Pilot on Contracted Temporal Activities

## Statement

Ground Control shall run a CLD pilot on a bounded set of Temporal activity work, using contract packages authored before implementation, oracle batteries committed before implementation, protected-path controls active during implementation, and measured implementation attempts against the battery. The pilot shall produce enough evidence to decide whether to proceed, adjust, or stop before CLD workflow productization.

## Rationale

The CLD method has real floor cost. A pilot on deterministic Temporal activities gives a small, high-leverage substrate where correctness is measurable and where ADR-081/ADR-082 already require contract-first activity payloads.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1296` (CLD pilot: contract-first implementation of Temporal activities with a low-tier implementer)
- DOCUMENTS → ADR `architecture/adrs/087-contract-locked-development-methodology.md` (ADR-087: Contract-Locked Development Methodology)
- DOCUMENTS → GITHUB_ISSUE `1291` (ADR: contract-locked development methodology)
- DOCUMENTS → ADR `ADR-087` (ADR-087: Contract-Locked Development Methodology)
