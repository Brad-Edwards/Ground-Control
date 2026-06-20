# MCP OpenAPI Contract Preflight

Issue: #1106
Requirement: none

This note records architecture guardrails for a CI contract test that compares
MCP write-tool body allowlists and enum mirrors with the backend generated
OpenAPI contract. It is not an implementation plan.

## Boundary

The backend Spring MVC request DTOs remain the semantic authority for write
shape, required fields, enum values, Bean Validation, project scoping,
authorization, audit, and persistence. Springdoc OpenAPI is the mechanical API
contract emitted from that authority. MCP tool schemas and body allowlists are
mirrors that keep agents from sending stale, unknown, or control-plane fields
to REST.

The gate must catch drift between those two boundaries. It must not create a
second DTO hierarchy, a generic write proxy, or an MCP-owned validation model.

Initial coverage belongs to the GRC write tools where drift already caused
failures:

- `gc_risk_governance`
- `gc_threat_model`
- `gc_risk_scenario`
- `gc_control`
- `gc_evidence`
- `gc_finding`
- `gc_audit`
- `gc_observation`
- `gc_asset`

The same mechanism must be inventory-driven so the remaining write surface can
be added as rows, not as a second checker.

## Incumbents To Reuse

- Backend API contract: Springdoc's generated OpenAPI from the existing
  controllers and request records under `backend/src/main/java/.../api`.
- Backend validators: Jackson enum binding, `@Valid`, Bean Validation, and the
  existing service-layer semantic validators remain authoritative.
- MCP transport and shaping: `mcp/ground-control/lib.js` owns `pick`,
  `reqArg`, `toCamelCase`, `TO_CAMEL`, `OPAQUE_VALUE_KEYS`, `RequestError`,
  `parseErrorBody`, and bearer-token routing.
- MCP write adapters: exported field arrays and action dispatch in
  `gc-risk-governance.js`, `gc-threat-model.js`, `gc-risk-scenario.js`,
  `gc-control.js`, `gc-evidence.js`, `gc-finding.js`, `gc-audit.js`,
  `gc-observation.js`, `gc-asset.js`, and shared `link-create.js`.
- Existing drift pattern: ADR-034 and `tools/policy/checks.py` already use a
  parameterized inventory for enum mirrors. Reuse that idea; do not hand-code
  one assertion per tool.
- CI surfaces: required GitHub checks already include `policy`, `test`,
  `integration`, `verify`, and `sonar`. `mcp/ground-control` has an `npm test`
  script, but current CI only installs its dependencies for policy helpers and
  does not run the MCP test suite.
- Documentation surface: the current MCP action contract lives in
  `mcp/ground-control/README.md` plus each adapter's description string.

## Cross-Cutting Layers

- **OpenAPI generation:** use a local generated OpenAPI document from the
  current backend code. Do not read production OpenAPI, depend on `GC_BASE_URL`,
  or make `/api/openapi.json` public to let CI fetch it.
- **Spring Security:** production OpenAPI remains governed by
  `GC_SECURITY_OPENAPI_PUBLIC=false` and ADR-026 path rules. Test-only access to
  generated OpenAPI must stay in the test harness or build output.
- **MCP public schema:** Zod remains the caller-facing shape check. The contract
  test should compare action-scoped body allowlists, not every Zod field,
  because tool schemas also contain `action`, `entity`, `id`, `project`, path
  ids, filters, lifecycle fields, and read/link controls.
- **Body allowlist gate:** `pick(args, FIELDS)` remains the adapter boundary.
  Unknown MCP args must be dropped, not tunneled through `metadata` or
  forwarded as arbitrary JSON.
- **Field naming:** compare backend camelCase OpenAPI properties to MCP
  snake_case fields through the existing `TO_CAMEL` mapping and manual adapter
  mappings. Preserve opaque user maps such as `metadata`, `schema_body`,
  `input_factors`, and `computed_outputs`.
- **Enums:** resolve OpenAPI enum values through `$ref` schemas, not only inline
  `enum` arrays. MCP constants in `lib.js` are mirrors under ADR-034.
- **Validation:** the test checks drift only. It must not duplicate every
  `@Size`, blank-string, date, UUID, same-project, append-only, or
  `GraphTargetResolverService` semantic rule in MCP.
- **Error envelopes:** runtime validation failures continue to flow through
  `GlobalExceptionHandler`, `ErrorResponse`, `RequestError`, and `err()`. CI
  drift failures should name the tool, field, and wrong side without exposing
  stack traces, env vars, tokens, request bodies, or response headers.
- **Secrets and OS exposure:** the contract gate needs no bearer token, admin
  token, live URL, shell `curl`, or argv-visible secret. It should read tracked
  source/build artifacts inside the workspace.
- **Workflow policy:** `make policy` remains the repo-native guardrail before
  declaring work complete. If the new check is implemented as an MCP Node test,
  it also needs a required CI invocation, since the MCP package test script is
  not run by the current workflow.

## Extensibility

The extension seam is a data inventory with one row per tool/entity/action/body
contract. Each row should name:

- tool name and, where present, entity/action discriminator values;
- the exported MCP body-field array or shared helper that feeds `pick`;
- the OpenAPI operation and request schema to compare;
- expected create/update/transition split;
- field-name normalization through `TO_CAMEL` or explicit manual body mapping;
- intentional exclusions such as path params, query params, server-populated
  fields, lifecycle transition fields, read-only fields, and MCP control
  arguments.

The next write tool should require one inventory row plus any missing exported
field array, not a copied parser or another test harness.

## Gotchas And Anti-Patterns

- Do not compare a tool's full Zod object to a DTO. Zod includes control fields
  that must never enter request bodies.
- Do not compare create and update against one combined field set. Create-only
  fields, update-only clear flags, transition fields, and path ids differ by
  action.
- Do not tune the inventory to bless current broken allowlists. If generated
  OpenAPI exposes a DTO field that the MCP tool should support, fix the
  allowlist or record a narrow exclusion with rationale.
- Do not make `metadata` an escape hatch for fields modeled explicitly by a
  backend DTO.
- Do not satisfy this issue with Java request-record source parsing alone.
  Source parsing can support local extraction, but the compared contract must
  be Springdoc's generated OpenAPI for the current backend build.
- Do not duplicate the `link_create` contract per tool. The shared
  `LINK_CREATE_BODY_FIELDS` and `performLinkCreate` helper are the seam for
  link DTO parity.
- Do not duplicate backend target-slot validation in MCP. The backend resolver
  decides whether a target type needs `targetEntityId` or `targetIdentifier`.
- Do not weaken backend `@JsonIgnoreProperties` behavior, security config,
  Bean Validation, or service validation to make the MCP mirror pass.
- Do not add a live-service CI dependency or require a deployed Ground Control
  instance for this gate.

## Non-Goals

- No backend controller, service, repository, entity, migration, graph, audit,
  or security redesign.
- No generic MCP write tool and no direct database, repository, AGE, GitHub, or
  shell side effect from the checker.
- No OpenAPI-generated frontend type migration.
- No exhaustive validation-code generator. The gate checks field and enum drift
  at the API boundary; runtime validators keep their existing ownership.
- No change to ADR-026, ADR-033, ADR-035, ADR-057, or ADR-058.
