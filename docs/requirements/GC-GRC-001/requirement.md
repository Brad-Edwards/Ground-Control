---
id: GC-GRC-001
title: "Derivation Adapter Port and Normalized System-Model Facts"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:24:30.991586Z
updated_at: 2026-08-02T18:39:44.325006Z
---

# GC-GRC-001 — Derivation Adapter Port and Normalized System-Model Facts

## Statement

The system shall provide a language-agnostic derivation adapter port (mirroring the ADR-014 verifier port pattern) through which static-analysis tools derive system-model facts from a repository scope.

(a) Adapters shall accept a scope (full repo, diff, path set) and return facts in a normalized schema: components, trust boundaries, data flows, entry points, taint paths, secret usages, external interactions, and data-classification hints.

(b) Every fact shall carry provenance: tool, tool version, ruleset/query-pack version, commit SHA, and timestamp, sufficient to reproduce the derivation.

(c) Adapters shall register per language/surface in a registry; a scope request routes to every applicable adapter.

(d) Where no adapter covers a language or surface, the gap shall be recorded as an explicit, machine-readable capture limit — never a silent skip.

(e) Normalized facts shall be persisted server-side in Ground Control (evidence/architecture-model aggregates), not committed to the analyzed repository.

## Rationale

Derivation replaces LLM generation as the source of the security model (derive → enumerate → judge → enforce → maintain). Provenance makes facts auditable and reproducible; explicit declination keeps polyglot coverage honest; server-side persistence keeps sensitive analysis output out of potentially public repos. Anchors the continuous secure-by-design GRC program.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1114` (Issue #1114: GC-GRC-001 derivation adapter port and normalized system-model facts)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/derivation/service/DerivationAdapter.java` (Derivation adapter port)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/derivation/service/DerivationService.java` (Server-side derivation orchestration and persistence)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/derivation/DerivationController.java` (Derivation REST invocation and readback surface)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-derivation.js` (Derivation MCP invocation and readback tool)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/derivation/DerivationAdapterRegistryTest.java` (Derivation adapter registry routing and capture-limit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/DerivationControllerTest.java` (Derivation REST controller tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/derivation/DerivationServiceIntegrationTest.java` (Derivation service persistence integration test)
- TESTS → TEST `mcp/ground-control/gc-derivation.test.js` (Derivation MCP adapter tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/derivation/DerivationServiceTest.java` (Derivation service unit coverage)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/derivation/StubDerivationAdapterTest.java` (Stub derivation adapter unit coverage)
