---
id: GC-Q013
title: "GRC Portfolio Reporting Views"
status: DEPRECATED
type: FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-30T02:53:41.308456Z
updated_at: 2026-07-11T23:43:44.555623Z
---

# GC-Q013 — GRC Portfolio Reporting Views

## Statement

The web application shall provide GRC portfolio views for risk posture, control health, evidence freshness, finding trends, asset criticality concentration, and methodology-specific FAIR, NIST, or ISO summaries with drill-down to underlying graph entities.

## Rationale

Executives, risk owners, auditors, and engineers need different summary views over the same graph. Portfolio reporting is the human-facing complement to agent-queryable analysis APIs.

## Traceability

- DOCUMENTS → DOCUMENTATION `docs/architecture/ARCHITECTURE.md` (GRC portfolio architecture note)
- IMPLEMENTS → GITHUB_ISSUE `751` (Issue 751: GRC Portfolio Reporting Views)
- IMPLEMENTS → PULL_REQUEST `1159` (PR 1159: feat: add grc portfolio reporting view)
- DOCUMENTS → GITHUB_ISSUE `#751` (GC-Q013: GRC Portfolio Reporting Views)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `frontend/src/pages/grc-portfolio.tsx` (GRC portfolio reporting page)
- IMPLEMENTS → CODE_FILE `frontend/src/hooks/use-grc-portfolio.ts` (GRC portfolio data composition hook)
- IMPLEMENTS → CODE_FILE `frontend/src/routes.tsx` (GRC portfolio route registration)
- IMPLEMENTS → CODE_FILE `frontend/src/components/layout/app-layout.tsx` (GRC portfolio navigation entry)
- IMPLEMENTS → CODE_FILE `frontend/src/types/api.ts` (GRC portfolio API response types)
- TESTS → TEST `frontend/src/pages/__tests__/grc-portfolio.test.tsx` (GRC portfolio reporting page tests)
