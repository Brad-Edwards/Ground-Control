---
id: GC-CLD-5
title: "Protected-Path Power Separation"
status: DEPRECATED
type: CONSTRAINT
priority: MUST
wave: 9
created_at: 2026-07-04T02:46:03.922213Z
updated_at: 2026-07-28T03:05:35.363554Z
---

# GC-CLD-5 — Protected-Path Power Separation

## Statement

Ground Control shall enforce CLD separation of powers with protected-path and authority gates. Implementation-lane changes shall not modify contract packages, oracle batteries, architecture registry data, policy checks, or other design-authority artifacts unless the run carries an explicit design-authority approval marker. Battery weakening, deleted checks, skipped tests, lowered thresholds, or protected-path edits without authority shall fail mechanically in CI or policy before merge.

## Rationale

The implementer is an optimizer against the gate. Separation of powers only works when protected paths and battery strength are enforced by tooling, not by prompts or review etiquette.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/087-contract-locked-development-methodology.md` (ADR-087: Contract-Locked Development Methodology)
- DOCUMENTS → GITHUB_ISSUE `1291` (ADR: contract-locked development methodology)
- DOCUMENTS → ADR `ADR-087` (ADR-087: Contract-Locked Development Methodology)
- IMPLEMENTS → GITHUB_ISSUE `1294` (Issue #1294: Spec-authority separation protected-path policy)
- IMPLEMENTS → PULL_REQUEST `1324` (PR #1324: security: enforce spec-authority protected paths)
- IMPLEMENTS → CONFIG `.ground-control.yaml` (Authoritative review-disposition configuration)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (Design-authority approval token validation and review-disposition enforcement)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP tool contract for token-gated design-authority approval)
- DOCUMENTS → DOCUMENTATION `docs/DEVELOPMENT_WORKFLOW.md` (Development workflow guidance for protected-path authority approval)
