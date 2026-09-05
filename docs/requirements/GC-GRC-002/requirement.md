---
id: GC-GRC-002
title: "CodeQL Derivation Adapter"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:24:45.532268Z
updated_at: 2026-07-11T23:43:44.555493Z
---

# GC-GRC-002 — CodeQL Derivation Adapter

## Statement

The system shall provide a CodeQL derivation adapter implementing the GC-GRC-001 port.

(a) The adapter shall cover, at minimum, Java, JavaScript/TypeScript, and Python, running taint/data-flow queries that identify source→sink paths, entry points, and call-graph reachability.

(b) Query results shall be normalized into the GC-GRC-001 facts schema, including flows that cross derived or declared boundaries (GC-GRC-004).

(c) Query packs shall be version-pinned per project; a derivation run shall be reproducible for a given commit and pin set.

(d) The adapter shall support diff-scoped incremental derivation (in-loop use) and full-scope derivation (assessment-lane use) through the same interface.

## Rationale

CodeQL is the anchor derivation engine: GitHub-native, polyglot, and mature for taint and data-flow analysis. Pinned query packs make the security model's evidentiary base reproducible rather than dependent on LLM generation.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1115` (Issue #1115: GC-GRC-002 CodeQL derivation adapter)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/CodeQlDerivationAdapter.java` (CodeQL derivation adapter)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/CodeQlDerivationProperties.java` (CodeQL derivation properties)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/CodeQlSarifNormalizer.java` (CodeQL SARIF normalizer)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/CodeQlDerivationAdapterTest.java` (CodeQL derivation adapter tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/CodeQlSarifNormalizerTest.java` (CodeQL SARIF normalizer tests)
