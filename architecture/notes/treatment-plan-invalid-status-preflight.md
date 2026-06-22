# Treatment Plan Invalid Status Preflight

Issue: #881

This note records architecture guardrails for fixing invalid enum status
handling on treatment-plan writes and the matching MCP tool validation. It is
not an implementation plan.

## Boundary

The issue is an API-contract and adapter-validation fix. It must not change the
`TreatmentPlanStatus` lifecycle, treatment-plan persistence, transition rules,
or the `gc_risk_governance` tool catalog shape from ADR-035. Backend Java enums
remain the semantic authority for enum values; MCP schemas are a caller-facing
preflight mirror that prevents avoidable backend round trips.

Do not conflate three distinct checks:

- malformed JSON syntax, which remains a 400 `bad_request`;
- syntactically valid JSON whose enum token is not bindable, which is a 422
  `validation_error` with field and valid-value detail;
- domain-valid enum tokens that violate business rules or state transitions,
  which stay in service/domain validation.

## Incumbents To Reuse

- Backend parsing and error envelope:
  `api.GlobalExceptionHandler`, Jackson `InvalidFormatException` /
  `JsonMappingException`, Spring `HttpMessageNotReadableException`, and
  `shared.web.ErrorResponse`.
- DTO shape validation: request records under
  `backend/src/main/java/com/keplerops/groundcontrol/api/riskscenarios/`,
  Jackson enum binding, `@Valid`, and Bean Validation.
- Domain validation: `TreatmentPlanService`, `TreatmentPlan.transitionStatus`,
  and `TreatmentPlanStatus` for lifecycle rules; `DomainValidationException`
  for semantic 422 responses that are not parser failures.
- MCP validation: `mcp/ground-control/gc-risk-governance.js` for the public
  Zod shape and handler, and `mcp/ground-control/lib.js`
  `GOVERNANCE_STATUS_ENUMS`, `GOVERNANCE_FIELDS`,
  `validateGovernanceStatus`, `pick`, `reqArg`, `request`, and
  `RequestError`.
- Contract drift guidance: ADR-034 for backend-enum authority and mirror
  checks, ADR-035 for consolidated MCP tool curation, and the existing
  risk-treatment-plan preflight note for treatment-plan aggregate boundaries.
- Tests and workflow: `GlobalExceptionHandlerTest`,
  `TreatmentPlanControllerTest`, `gc-risk-governance.test.js`,
  `lib.test.js`, and `make policy`.

## Cross-Cutting Layers

- **Security:** A treatment-plan write still enters through `/api/v1/**` and
  must pass `ApiSecurityConfig`, `IpAllowlistFilter`, bearer-token auth,
  authorization, `ActorFilter`, and audit provenance. The fix must not add a
  bypass route, caller-supplied auth header path, or feature-local actor source.
- **Request shape:** Jackson remains the first backend shape check for enum
  tokens. Bean Validation remains responsible for null/missing fields. Do not
  parse enum strings manually inside controllers or services.
- **Error envelope:** All HTTP failures use `ErrorResponse`. The 422 enum body
  may expose stable field names and enum names, but must not reflect raw request
  bodies, stack traces, Java class names, source paths, or untrusted payloads.
- **Logging and observability:** Unexpected server failures continue through
  the existing `GlobalExceptionHandler` logging. Expected invalid enum input is
  client validation noise and should not add high-cardinality logs containing
  raw request bodies or tokens.
- **MCP transport:** `gc_risk_governance` writes continue through `request()`,
  `toCamelCase`, canonical bearer-token environment variables, `X-Actor:
  mcp-server`, backend error-envelope preservation, and the existing body
  allowlist. Do not add a treatment-plan-specific HTTP client or URL builder.
- **Configuration and OS/runtime exposure:** This issue requires no new env
  vars, secrets, subprocesses, shell-outs, network clients, or argv-carried
  tokens. Any test helper should use local fixtures and mocked fetches.

## Extensibility

The seam for backend enum parser failures is one centralized helper in
`GlobalExceptionHandler` that extracts the offending path and valid enum
`name()` values for any enum-bound DTO field, not a treatment-plan-specific
handler. The next enum-bound request field should inherit the same 422 envelope
without editing a controller.

The MCP seam is the per-entity `GOVERNANCE_STATUS_ENUMS` map used by
`validateGovernanceStatus`. A future status-bearing `gc_risk_governance` entity
should add one status array and one map entry; an entity without `status` should
continue to reject status explicitly rather than silently forwarding it.

## Gotchas And Anti-Patterns

- Do not use a flat Zod union for all governance statuses; it accepts values
  valid for the wrong entity, such as `ACCEPTED` for `treatment_plan`.
- Do not broaden `HttpMessageNotReadableException` into 422 for all parse
  failures. Broken JSON and type-shape errors that are not enum tokens should
  remain 400 unless a separate API-contract decision says otherwise.
- Do not add endpoint-local `@ExceptionHandler` methods, new error envelopes,
  new domain exceptions, or controller-side enum parsers.
- Do not hide invalid MCP status values in `metadata` or let stale fields bypass
  `GOVERNANCE_FIELDS`.
- Do not use enum `toString()` for valid-value hints; callers must see bindable
  enum names.
- Do not change `TreatmentPlanStatus` values or transition semantics to satisfy
  this UX/observability bug.

## Non-Goals

- No treatment-plan lifecycle redesign, database migration, persistence change,
  audit model change, or workflow-engine abstraction.
- No new REST endpoint for enum discovery and no OpenAPI/codegen migration as
  part of this issue.
- No replacement of the consolidated `gc_risk_governance` tool with
  entity-specific tools.
- No change to authentication, authorization, actor provenance, project
  scoping, or token handling.
