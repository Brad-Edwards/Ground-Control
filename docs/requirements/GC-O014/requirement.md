---
id: GC-O014
title: "Contract-First Development Surface"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 8
created_at: 2026-07-02T22:17:40.143751Z
updated_at: 2026-07-05T00:31:19.350888Z
---

# GC-O014 — Contract-First Development Surface

## Statement

Ground Control's externally consumed surfaces — the REST API, the MCP tool catalog, workflow/activity input-output payloads, and the web console's API client — shall be governed by a physically separate contract surface from which implementation-facing artifacts are generated, such that the contract and its tests remain stable while implementations change.

(a) Contract artifact set. A dedicated contracts location shall hold the canonical machine-readable contracts: the OpenAPI document of record (generated from the backend per ADR-034/GC-O013 and treated as the consumed artifact), JSON Schemas for durable records and workflow/activity payloads, and the authorization path/role matrix vocabulary. Consumers shall depend on committed generated artifacts (TypeScript client types, MCP tool schemas) — never hand-mirrored copies.

(b) Drift gates. CI shall fail when committed generated artifacts drift from their contract source (regenerate-and-diff), extending the existing enum and write-contract gates (ADR-034, GC-O013) to the full generated surface, including the web console client.

(c) Breaking-change gate. CI shall fail on a breaking change to a published contract (removed or retyped field, removed operation, narrowed enum) unless the change ships with an explicit version bump and a deprecation record; additive changes pass.

(d) Conformance over implementation tests. Port-level behavior shall be specified once as contract-anchored conformance suites run against every implementation of the port (for example in-memory and JPA/Postgres), with property-based tests (jqwik) for declared invariants and mandatory negative-authorization tests for each authenticated endpoint class.

(e) Assurance escalation. Surfaces brought under this requirement escalate to at least L1 (ADR-012): every contract-declared invariant carries a named enforcing test, property test, or formal spec, and a change that removes an invariant's enforcement shall fail the policy gate.

New work under the Temporal orchestration program (GC-O009) shall be contract-first from the outset: workflow and activity input/output records are versioned contracts under (a) before implementation lands.

## Rationale

The moonbase repository demonstrates the target discipline: a contracts module depended on only through committed generated code, with CI failing on drift, breaking changes, boundary bypass, and any spec'd invariant no test proves — yielding contract surfaces and tests that survive implementation churn. Ground Control already has the seams (Springdoc OpenAPI, ADR-034 enum single-source, GC-O013 write-contract gate, ArchUnit layering, jqwik, OpenJML) but the frontend hand-mirrors API types, workflow payloads have no schema home, and no breaking-change or conformance-suite discipline exists. GC-O009 makes this urgent: ADR-028 requires workflow and activity records to be versioned API contracts, so establishing the contract surface before the Temporal build means the orchestration program raises the assurance base instead of accruing debt.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/082-contract-surface-architecture.md` (ADR-082: Contract Surface Architecture and Enforcement Gates)
- IMPLEMENTS → GITHUB_ISSUE `1275` (Issue #1275: GC-O014 contract surface foundation)
- IMPLEMENTS → PULL_REQUEST `1320` (PR #1320: feat: add contract surface enforcement gates)
- IMPLEMENTS → CONFIG `.github/workflows/ci.yml` (CI contract-surface drift, breaking-change, and MCP contract gates)
- IMPLEMENTS → CONFIG `.pre-commit-config.yaml` (Pre-commit hygiene exception for committed generated OpenAPI artifact)
- IMPLEMENTS → CONFIG `Makefile` (Contract generation, drift, breaking-change, and MCP OpenAPI gate targets)
- IMPLEMENTS → CONFIG `backend/Dockerfile` (Container build consumes generated contract bindings for frontend build)
- IMPLEMENTS → SPEC `contracts/openapi/openapi.json` (Committed OpenAPI contract of record)
- IMPLEMENTS → SPEC `contracts/authz/path-matrix.yaml` (Authorization path and role matrix contract)
- IMPLEMENTS → CODE_FILE `contracts/gen/typescript/api.ts` (Generated TypeScript API contract types)
- IMPLEMENTS → POLICY `tools/policy/checks.py` (Repo policy checks for contract surface, invariant enforcement, authz matrix sync, and generated bindings)
- IMPLEMENTS → CODE_FILE `frontend/src/types/api.ts` (Frontend API type compatibility shim re-exporting generated contract bindings)
- IMPLEMENTS → SPEC `contracts/schemas/workflow/workflow-run-record.v1.schema.json` (Workflow run record JSON Schema contract)
- IMPLEMENTS → DOCUMENTATION `contracts/CHANGES.md` (Published contract version and deprecation/change record)
- DOCUMENTS → DOCUMENTATION `contracts/schemas/README.md` (Contract schema home documentation)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/test/oracle/AbstractPortConformanceSuite.java` (Reusable port conformance-suite contract fixture)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/test/oracle/NegativeAuthorizationMatrix.java` (Negative authorization matrix oracle cases)
- IMPLEMENTS → CODE_FILE `tools/contracts/generate-contracts.mjs` (Deterministic contract artifact generator)
- IMPLEMENTS → CODE_FILE `tools/contracts/check-breaking-changes.mjs` (OpenAPI breaking-change gate)
- IMPLEMENTS → SPEC `contracts/schemas/records/implement-final-report.v1.schema.json` (Implement final-report durable record JSON Schema contract)
- DOCUMENTS → DOCUMENTATION `contracts/schemas/workflow/README.md` (Workflow schema contract home documentation)
- DOCUMENTS → DOCUMENTATION `docs/DEVELOPMENT_WORKFLOW.md` (Development workflow contract-surface guidance)
- DOCUMENTS → ADR `architecture/adrs/054-documentation-coverage-gate.md` (ADR-054 updates for generated contract artifact policy coverage)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/McpOpenApiContractSpecTest.java` (MCP OpenAPI committed contract drift test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/ProjectRepositoryConformanceTest.java` (Project repository port conformance suite across implementations)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/test/oracle/OracleBatteryScaffoldTest.java` (Oracle battery scaffold tests for conformance and authorization fixtures)
- IMPLEMENTS → SPEC `contracts/ontology/gc-concept-families-v1.json` (Context-graph ontology concept family contract v1)
- IMPLEMENTS → SPEC `contracts/ontology/gc-controlled-vocabularies-v1.json` (Context-graph controlled vocabulary contract v1)
- IMPLEMENTS → SPEC `contracts/ontology/gc-artifact-bindings-v1.json` (Context-graph Java artifact binding contract v1)
- IMPLEMENTS → ADR `architecture/adrs/084-context-graph-concept-authority.md` (ADR-084 context-graph concept authority and ontology binding gate)
- IMPLEMENTS → GITHUB_ISSUE `1307` (Issue #1307: ontology artifacts and binding gate)
- IMPLEMENTS → PULL_REQUEST `1394` (PR #1394: ontology binding contracts and drift gate)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AsOfRevisionResolverConformanceTest.java` (AsOfRevisionResolverConformanceTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/graph/GraphProjectionContributorAuditGuardTest.java` (GraphProjectionContributorAuditGuardTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/OpenApiAsOfParameterGuardTest.java` (OpenApiAsOfParameterGuardTest)
- IMPLEMENTS → SPEC `contracts/ontology/crosswalks/aces-concept-families-v1.json` (ACES concept-family crosswalk v1 (pinned to aces-sdl==0.23.0, closed effect vocabulary))
- IMPLEMENTS → PULL_REQUEST `1407` (PR #1407: feat: add ACES concept-family crosswalk v1 and referential-integrity policy gate)
- IMPLEMENTS → SPEC `contracts/schemas/measurement/measurement-record.v1.schema.json` (Measurement record JSON Schema contract (three outcome axes structurally disjoint))
- IMPLEMENTS → SPEC `contracts/schemas/measurement/station-catalogue.v1.schema.json` (Station catalogue JSON Schema contract (station identity and alias kinds))
- IMPLEMENTS → SPEC `contracts/measurement/gc-station-catalogue-v1.json` (Station catalogue data: authoritative station_id set with aliases declared by source kind)
- IMPLEMENTS → CODE_FILE `tools/policy/checks.py` (run_measurement_catalogue_check — station-catalogue drift gate (clause e: named enforcing gate))
- IMPLEMENTS → GITHUB_ISSUE `1438` (Versioned measurement contract and station catalogue for ADR-090)
- TESTS → TEST `tools/tests/test_policy_contract_invariant_enforcement.py` (Policy tests for contract surface, generated bindings, invariant enforcement, authz matrix, and breaking-change gates)
