# Requirement Enum Contract Preflight

Issue #433 fixes drift between backend requirement/traceability enums, MCP tool
enums, and frontend request types. This note records the architecture guardrails
for that work. It is not an implementation plan.

## Architecture Boundaries

- The backend domain enums remain the semantic source of truth:
  `RequirementType`, `ArtifactType`, and `LinkType` under
  `domain/requirements/state/`.
- Frontend API request/response types and enum option lists must be derived from
  the backend API contract, not hand-authored as an independent schema. ADR-017
  already names OpenAPI-generated TypeScript types as the intended frontend
  boundary.
- MCP enum constants must stay a generated or mechanically checked mirror of
  the same backend enum set. Do not add an MCP-only enum taxonomy.
- Request contract ownership stays split by layer: DTO shape validation in
  `api/requirements/*Request.java`, semantic validation in
  `RequirementService` and `TraceabilityService`, and display-only constraints
  in React forms.
- Traceability artifact/link semantics stay with `TraceabilityLink` per
  ADR-011. Do not merge these values with `RelationType`, asset links, control
  links, risk scenario links, or threat-model links.

## Cross-Cutting Concerns to Reuse

- **API contract:** Spring MVC DTOs, Jackson enum parsing, Bean Validation, and
  Springdoc OpenAPI. Avoid a separate JSON schema or custom TypeScript enum
  registry.
- **Frontend contract:** React forms and `frontend/src/types/api.ts` should
  consume generated or extracted constants through one local module. UI controls
  should not keep duplicate literal arrays.
- **MCP contract:** `mcp/ground-control/lib.js` constants and `lib.test.js`
  are the current MCP enum guardrail; extend that pattern or replace it with a
  mechanical check against the backend enum source.
- **Error handling:** invalid enum names and missing required fields must keep
  routing through `GlobalExceptionHandler` and `ErrorResponse`. Do not add a
  feature-local error envelope.
- **Auth and audit:** the affected REST routes remain under ADR-026 Spring
  Security path rules, `ActorFilter`, and Envers auditing on requirements and
  traceability links. This change must not introduce a bypass or controller-side
  persistence write path.
- **Policy and CI:** keep `make policy` as the repo-native completion gate. Add
  the enum drift check to existing test or CI surfaces rather than adding a
  parallel policy runner.

## Security Layers In Scope

- **Spring Security:** `/api/v1/requirements/**` and analysis endpoints remain
  authenticated under `ApiSecurityConfig`; no new unauthenticated enum endpoint
  should be needed for this issue.
- **Request parsing:** Jackson enum binding is the first backend shape check for
  enum strings. Unknown values should fail before domain mutation.
- **Bean Validation:** `RequirementRequest`, `UpdateRequirementRequest`, and
  `TraceabilityLinkRequest` are the DTO boundary for required fields and size
  limits. Frontend validation should match these rules without replacing them.
- **Error envelope:** parser and validation failures must continue to emit the
  shared `ErrorResponse` envelope without reflecting stack traces or internal
  enum-source paths.
- **OS/process exposure:** any contract-generation or drift-check script must
  read tracked source files and local build output. It must not pass tokens,
  credentials, or live API responses through command argv or logs.

## Extensibility Guardrail

The obvious next change is another API enum or request DTO needing the same
single-source treatment. The seam should be a parameterized extractor or
generator that names enum classes and request DTOs, not one bespoke check for
only these three enums. Adding a future enum should require updating the
contract inventory, not copying parser logic into a new script.

## Gotchas and Anti-Patterns

- Do not satisfy the issue by editing only the currently visible dropdowns.
  Filters, badges, tests, MCP constants, docs, and generated/static API types can
  all preserve drift.
- Do not freeze the backend values inside a frontend test. A contract test that
  compares one manual list to another manual list only moves the drift.
- Do not remove backend-supported values such as traceability targets that are
  used by sync, ADR, risk, or control workflows just because a current UI screen
  does not expose them.
- Do not introduce `GITHUB_PR` as a synonym for `PULL_REQUEST`, or string
  prefixes such as `github_pr:` to compensate. ADR-011 artifact identifiers are
  scoped by `ArtifactType`.
- Do not conflate traceability `LinkType` with requirement `RelationType` or
  asset/control/risk link enums. Similar words do not mean shared semantics.
- Do not let frontend form convenience make backend-required fields optional.
  Create must require `uid`, `title`, and `statement`; traceability create must
  require `artifactType`, `artifactIdentifier`, and `linkType`.

## Non-Goals

- No database migration, enum rename, or persisted-data rewrite unless the
  implementation discovers actual stored invalid values and escalates that as a
  separate data-repair decision.
- No new REST endpoint solely to serve enum lists unless OpenAPI generation or a
  source extractor cannot meet the contract.
- No new exception hierarchy, logging channel, audit model, security scheme, or
  workflow abstraction.
- No broad frontend validation framework migration unless the repo already has
  the chosen validator in active use for API request contracts.

## Implementation Outcome

Implemented in issue #433 and recorded as ADR-034 (*API Enum Contract Single
Source of Truth*):

- The source-extractor path was taken (not OpenAPI codegen). The Java enums
  under `domain/requirements/state/` are the source of truth; `bin/policy`'s
  `run_enum_contract_check` (`tools/policy/checks.py`) parses them and asserts
  the `frontend/src/types/api.ts` unions/constants and the
  `mcp/ground-control/lib.js` constants match. It runs in the `policy` CI job on
  every PR. The check is parameterized by `ENUM_CONTRACT_INVENTORY` - adding an
  enum is one row (the extensibility seam above) - and covers every API-exposed
  enum: `RequirementType`, `RelationType`, `ArtifactType`, `LinkType`, `Status`,
  `Priority`, `SyncStatus`, `ChangeCategory`. The parsers strip comments before
  extracting, and the Java parser reads only the constant list (up to the first
  `;` / `}`), so enums with methods (`Status`) parse correctly.
- `frontend/src/types/enum-contract.test.ts` was rewritten to read the actual
  Java enum files (no more manual-list-vs-manual-list). It is a developer-local
  mirror; `bin/policy` is the authoritative gate because the frontend test suite
  is not in PR CI today.
- Drift fixed: `ArtifactType` in `api.ts` regained `PULL_REQUEST`,
  `RISK_SCENARIO`, `CONTROL`; `SyncStatus` was corrected from
  `SYNCED|NOT_SYNCED|ERROR` to the backend's `SYNCED|STALE|BROKEN`.
- Mirrors consolidated: `RELATION_TYPES`, `STATUSES`, `PRIORITIES` added to
  `api.ts` and consumed by `relation-form.tsx`, `requirement-form.tsx`, and the
  `requirements.tsx` filters; `CHANGE_CATEGORIES` added to `lib.js` and consumed
  by `index.js`'s timeline `z.enum(...)` schemas.
- `RequirementRequest.statement` is required on the create form and typed
  `string`, matching the backend `@NotBlank`.
