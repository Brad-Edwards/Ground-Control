# Requirement Nested Route Ownership Preflight

Issue #432 is a security-boundary fix for requirement nested routes that carry
both a requirement id and a child relation or traceability-link id. This note is
preflight guidance only; it does not implement the controller, service, or test
changes.

## Boundary

- Treat the `{id}` path variable on nested requirement routes as a security
  scope, not a display hint. The child resource must be proven to belong to that
  requirement before history is returned or a delete is performed.
- Keep relation semantics consistent with the existing requirements API:
  `RequirementService.getRelations(id)` returns both outgoing and incoming
  relations, so relation child ownership means `{id}` is either `source.id` or
  `target.id`. Do not make history source-only while delete remains
  source-or-target, or vice versa.
- Keep traceability semantics stricter: a `TraceabilityLink` belongs to exactly
  one requirement via `link.requirement.id`; no alternate artifact identifier,
  URL, title, or link type may establish ownership.
- Preserve the 404 concealment pattern for mismatches. A child that exists under
  a different requirement must be indistinguishable from a missing child at the
  HTTP contract level unless a future authorization model explicitly replaces
  this with a 403.

## Incumbents to Reuse

- `RequirementController` stays thin: pass both path ids to the owning domain
  service and map returned domain objects to response records.
- `RequirementService` owns `RequirementRelation` writes and deletes.
  `AuditService` owns Envers-backed relation and traceability history reads.
  `TraceabilityService` owns `TraceabilityLink` writes and deletes.
- Use the existing repositories and entity associations
  (`RequirementRelation.source`, `RequirementRelation.target`,
  `TraceabilityLink.requirement`) as the source of truth. Do not add duplicate
  ownership tables, DTO fields, or request-body parent ids.
- Use `NotFoundException` and the `GlobalExceptionHandler` /
  `ErrorResponse` envelope. Do not add endpoint-local exception handlers or new
  exception types for ownership mismatches.
- Use the existing test surfaces: `RequirementControllerTest` for WebMvc slice
  behavior and the integration tests under `BaseIntegrationTest` for HTTP plus
  persistence/audit behavior.

## Cross-Cutting Layers

- `ApiSecurityConfig`, `IpAllowlistFilter`, and `BearerTokenAuthFilter` remain
  the authentication and authorization gate for `/api/v1/**`; nested ownership
  is a domain authorization check after request authentication, not a
  replacement for the path matrix.
- `SecurityProperties.validate()` still owns credential and CIDR shape
  validation. This issue must not introduce new security config keys or env
  bindings.
- `ActorFilter` and `RequestLoggingFilter` keep audit/log context. Ownership
  mismatches should not log child ids with new ad hoc log events; rely on the
  existing request and error paths unless a broader audit requirement appears.
- Hibernate Envers remains the audit source for relation and traceability
  history. Ownership must be checked before returning Envers revisions so audit
  history cannot be used as a cross-resource read side channel.
- Error responses must continue through `ErrorResponse` with stable
  `error.code = "not_found"` for deterministic 404 responses. Do not echo unrelated
  parent/child linkage details into response `detail`.

## Extensibility

If more nested requirement child resources are added, the reusable seam is a
service-layer "load child belonging to parent" helper scoped to that service's
owned entity. Parameterize the parent id and child id only; keep route shape,
HTTP status mapping, and response envelope outside the helper.

## Anti-Patterns

- Controller-level repository lookups or ownership checks.
- Checking only that the parent requirement exists, then loading the child by id.
- Returning `403` for some child mismatches and `404` for others without a
  centralized authorization decision.
- Treating relation ownership as source-only while list/delete/history still
  expose incoming relations elsewhere.
- Using artifact identifiers, URLs, UIDs, or request-body fields as substitutes
  for FK-based ownership checks.
- Adding new schemas, exception hierarchies, audit models, logging conventions,
  or security configuration for this narrow bug.

## Non-Goals

- No route redesign, API version change, or frontend contract change.
- No database migration; the required ownership data already exists in foreign
  keys.
- No change to the requirement relation DAG model or traceability artifact
  identifier conventions in ADR-011.
- No change to ADR-026 access control, bearer token handling, IP allowlisting,
  or OS/runtime secret exposure rules.
