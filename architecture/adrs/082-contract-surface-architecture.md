# ADR-082: Contract Surface Architecture and Enforcement Gates

## Status

Accepted

## Date

2026-07-03

## Context

Contract enforcement exists in fragments. ADR-034 made the backend Java enums
the single source of truth for API-boundary vocabularies, with `bin/policy`
asserting the frontend and MCP mirrors match. Its 2026-06-15 amendment
(GC-O013, #1106) extended that to MCP write-tool request bodies, compared
against the OpenAPI document generated from the current backend build.
ArchUnit enforces the `api/ -> domain/ <- infrastructure/` boundary; OpenJML
and jqwik cover the L1/L2 assurance ladder (ADR-012).

What is missing:

- The frontend still hand-mirrors the REST contract (`frontend/src/types/api.ts`
  unions and constants); ADR-034 itself named OpenAPI-generated types as the
  eventual boundary and chose the extractor as an interim.
- Workflow and activity payloads have no schema home, while ADR-028 requires
  GC-O009 workflow/activity input/output records to be versioned API
  contracts, and the durable issue-thread records (`gc:*` marker family)
  carry schema versions (`gc.implement.grc-screening/v2`, ...) with no
  machine-readable schema artifact behind them.
- Nothing gates breaking changes: a removed field or operation ships silently
  if the mirrors are updated in the same PR.
- Port behavior is tested per-implementation; there is no conformance-suite
  pattern that specifies a port once and holds every implementation to it.

GC-O014 (wave 8) requires a physically separate contract surface with drift
gates, a breaking-change gate, conformance suites, and assurance escalation,
so that the contract and its tests remain stable while implementations
change. ADR-081 sequences that surface as phase 0 of the Temporal program.
The discipline is proven in the sibling moonbase codebase: one contract
artifact set, consumed only through committed generated code, with CI failing
on drift, on undeclared breaking changes, on boundary bypass, and on any
declared invariant no test or spec enforces.

## Decision

### 1. A `contracts/` surface, with the backend as semantic source

Create a top-level `contracts/` directory holding the canonical
machine-readable contracts:

```text
contracts/
  openapi/openapi.json      committed OpenAPI document of record
  schemas/records/          JSON Schemas for durable records (gc:* marker payloads)
  schemas/workflow/         JSON Schemas for GC-O009 workflow/activity I/O records
  authz/path-matrix.yaml    ADR-026 path/role matrix as data
  gen/typescript/           committed generated TypeScript API client types
  CHANGES.md                declared breaking changes and deprecations
```

This does not reverse ADR-034's source-of-truth decision: the backend
(Java enums, Bean Validation, Springdoc annotations) remains the semantic
authority, and the OpenAPI document is **generated from the backend build**,
then committed as the artifact of record. Consumers - the TypeScript client,
the MCP write-contract gate (#1106), the breaking-change diff - read the
committed artifact. Spec-first authoring is rejected (see Alternatives).

The existing `generateContractOpenApi` Gradle task (Testcontainers boot +
`/api/openapi.json` capture, from #1106) is promoted from emitting a
build-directory artifact to refreshing `contracts/openapi/openapi.json`. The
emission is canonicalized (stable key order, pinned Springdoc version) so
regeneration is deterministic and diffs are meaningful.

### 2. Generated consumers replace hand mirrors

- **Frontend:** `openapi-typescript` generates client types under
  `contracts/gen/typescript/`, consumed by `frontend/src/lib/api-client.ts`.
  Generation owns the contract shape, not a second HTTP runtime: the console
  must keep using the existing `apiFetch`/`apiUpload`/`apiDelete` boundary, or
  configure any generated operation helpers to delegate through that boundary,
  so CSRF headers, same-origin credentials, 401 login redirect behavior, and
  `ErrorResponse` parsing stay centralized. The hand-maintained unions in
  `frontend/src/types/api.ts` are removed as pages migrate (GC-Q015 clause d
  completes the removal). ADR-034's enum extractor check retires per mirror
  only when the generated client replaces that mirror; until then both gates
  run.
- **MCP:** the write-tool allowlists stay inventory-gated per the ADR-034
  amendment (#1106) - importing live exported arrays and comparing against
  the spec proved robust, and generating a schema module for MCP is deferred.
  The #1106 gate switches its input from the build-directory spec to the
  committed `contracts/openapi/openapi.json` after verifying freshness (the
  drift gate below guarantees it).

### 3. Drift gate: regenerate and diff

CI regenerates every generated artifact and fails on any diff against the
committed copies (`git diff --exit-code contracts/`): the OpenAPI document
(rides the existing `mcp-contract` job, which already boots the backend), the
TypeScript client, and any schema-derived artifacts. A `make contracts`
target mirrors the full regeneration locally. Hand-editing a generated file
is therefore always a CI failure.

### 4. Breaking-change gate: declared, not forbidden

`oasdiff` (or equivalent) compares `contracts/openapi/openapi.json` against
the merge-base version on every PR. A breaking change - removed or retyped
field, removed operation, narrowed enum, tightened required set - fails CI
unless the PR declares it in `contracts/CHANGES.md` (what broke, why, migration
note, and a version bump for versioned schemas).

Posture matches ADR-012's pre-alpha philosophy: breaking changes are
permitted but must be loud and deliberate. Deprecation windows (an N-2-style
compatibility promise) begin at beta, via a future amendment - the gate
mechanics do not change, only the policy on what may be declared.

JSON Schemas under `contracts/schemas/` are versioned in-name
(`gc.workflow.<name>.v<N>`, matching the existing `gc.implement.*/v2` marker
convention); a breaking schema change is a new version, and consumers of the
old version keep validating until it is retired through `CHANGES.md`.

### 5. Workflow/activity payload contracts

Every GC-O009 activity's input and output record is a JSON Schema under
`contracts/schemas/workflow/` **before** the activity is implemented (ADR-081
phase precondition). Java record classes conformance-test against their
schema (serialize + validate) so Temporal history carries only
schema-versioned shapes, per ADR-028's "workflow and activity input/output
records are versioned API contracts." Durable-record renderers
(`gc_post_grc_screening`, `gc_post_decision_record`, `gc_post_final_report`,
...) get schemas under `contracts/schemas/records/` codifying the marker
payloads they already emit; each schema carries an `Invariants` section with
stable IDs.

### 6. Conformance suites and the negative-authorization matrix

- **Port conformance suites:** a port's behavioral contract is specified once
  as an abstract JUnit suite; every implementation (in-memory test double,
  JPA/Postgres adapter) extends it and must pass identically. First target:
  the workflow correlation store introduced in ADR-081 phase 1, plus one
  existing repository port to prove the pattern (#1275). Test doubles thereby
  stop being unverified mocks.
- **Property tests:** declared invariants in contract schemas map to jqwik
  property tests where the invariant is generative (state machines, ordering,
  idempotency), per the ADR-012 L2 ladder.
- **Negative-authorization matrix:** a shared test helper asserts, per
  authenticated endpoint class: anonymous is denied, wrong-role is denied,
  cross-project access is denied. Mandatory for every new endpoint class this
  program adds, extending the `ApiSecurityIntegrationTest` pattern; the
  path-matrix data file (`contracts/authz/path-matrix.yaml`) is the input, and
  a policy check asserts `ApiSecurityConfig`'s matrix matches the data file.

### 7. Assurance escalation and policy wiring

Surfaces brought under the contract surface escalate to at least L1
(ADR-012): every schema-declared invariant names its enforcing test, property
test, or formal spec in an inventory row; a change that removes an
invariant's enforcement fails `make policy`. Concrete rule additions, each
inventory-driven in the ADR-034 style (one row per covered contract, not one
checker per contract):

| Rule | Enforces | Owning issue |
|------|----------|--------------|
| contract-drift (CI) | regenerated artifacts equal committed `contracts/` | #1275 |
| contract-breaking (CI) | breaking OpenAPI/schema diff requires a `CHANGES.md` declaration | #1275 |
| invariant-enforcement (policy) | every schema `Invariants` ID names an existing test/spec | #1275 |
| authz-matrix-sync (policy) | `ApiSecurityConfig` equals `contracts/authz/path-matrix.yaml` | #1275 |
| workflow-payload-contract (policy) | Temporal activity I/O types map to a `contracts/schemas/workflow/` schema | #1277 |
| gate-set-parity (policy) | implemented operator signal set equals the workflow contract's | #1279 |
| ArchUnit: no Temporal SDK in `domain/` | ADR-028 layering | #1276 |
| ArchUnit: deterministic activities LLM-free | ADR-028 LLM boundary | #1280 |

`workflow-guardrail-sync` (adr-policy.json) is unchanged; the new rules are
additive.

## Consequences

### Positive

- One place answers "what is the contract" for REST, MCP, workflow payloads,
  durable records, and authorization - and CI proves the code matches it.
- Tests anchor to contracts (conformance suites, schema validation, the authz
  matrix), so implementation churn stops forcing test churn - the GC-O014
  goal.
- Breaking changes become visible review events with a written record instead
  of silent mirror edits.
- The Temporal program inherits typed, versioned activity contracts from day
  one instead of retrofitting them.

### Negative

- The committed generated artifacts add merge surface (regeneration conflicts
  when two branches touch the same controller); the drift gate makes the
  resolution mechanical but not free.
- Regenerating the OpenAPI document requires booting the backend
  (Testcontainers), so the authoritative drift check lives in the heavier CI
  job, not the fast `policy` job - same placement trade ADR-034's #1106
  amendment already accepted.
- Two generations of frontend typing coexist during migration (generated
  client alongside remaining `api.ts` mirrors), watched by two gates until
  GC-Q015 finishes the removal.

### Risks

| Risk | Mitigation |
|------|------------|
| Nondeterministic spec emission churns the committed artifact | Canonicalized serialization, pinned Springdoc version, and the drift gate itself surfaces nondeterminism immediately. |
| oasdiff false positives block benign changes | The `CHANGES.md` declaration path is also the override path; a declared non-breaking rationale passes review with the diff visible. |
| Schemas accrete without enforced invariants | The invariant-enforcement policy rule fails schemas whose `Invariants` IDs name no test; empty invariant sections are allowed only with an explicit `none` marker. |
| The contracts directory drifts from the deployed MCP server | The MCP restart-after-contract-deploy operational rule stands; the #1106 gate runs against the same committed artifact the backend build refreshed. |

## Alternatives Considered

### Spec-first authoring (hand-written OpenAPI, generated server stubs)

Rejected. ADR-034 already decided the backend is the semantic authority, the
whole validation stack (Jackson, Bean Validation, service validators,
`GlobalExceptionHandler`) hangs off backend types, and 35+ controllers exist.
Generate-from-backend-then-commit gets the same consumable artifact without
rewriting the API layer.

### Protobuf/gRPC with buf breaking

Rejected. The REST+JSON boundary is established across REST, MCP, and the
console; introducing an IDL would add a translation layer without retiring
any existing surface. `oasdiff` covers the breaking-gate role for OpenAPI;
JSON Schema versioning covers the payload contracts.

### Consumer-driven contracts (Pact)

Rejected for now. All consumers (frontend, MCP) live in this repository and
CI already runs them against the same committed spec; provider/consumer
contract exchange adds machinery without adding a boundary. Revisit when an
external consumer exists.

### Keep the extractor-mirror approach and skip generation

Rejected. ADR-034 itself positioned the extractor as interim pending
OpenAPI-generated types. The mirror set is about to grow (workflow control
surface, identity admin, console) - extending hand mirrors to that surface
scales the drift risk the extractor was built to contain.

## Non-Goals

- Rewriting existing controllers onto generated server stubs.
- Generating the MCP tool schema module (stays inventory-gated per #1106).
- Formal specs for the contract artifacts themselves; TLA+/OpenJML scope is
  unchanged (ADR-012/ADR-014), reachable through the invariant inventory
  where a schema invariant warrants L2+.
- Tenancy or external-consumer contract distribution.

## Related Requirements

- GC-O014 Contract-First Development Surface (anchor)
- GC-O013 MCP-Backend Write-Contract Drift Gate (extended, not replaced)
- GC-O009 Workflow Orchestration via Temporal (payload contracts)
- GC-Q015 Console Shell and Design System (generated client adoption)

## Related ADRs

- ADR-012 Formal Methods Development Process (assurance ladder)
- ADR-014 Pluggable Verification Architecture
- ADR-017 Interactive Web Application (anticipated generated types)
- ADR-026 REST API Access Control (path matrix as data input)
- ADR-028 Temporal Workflow Orchestration Boundary (versioned activity contracts)
- ADR-034 API Enum Contract Single Source of Truth (source-of-truth decision retained)
- ADR-081 Temporal Dev Workflow and Console Program (sequencing; companion)
