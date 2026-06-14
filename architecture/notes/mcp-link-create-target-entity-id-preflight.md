# MCP Link Create Target Entity Preflight

Issue: #877
Requirement: none

This note captures architecture guardrails for restoring UUID-keyed
`link_create` support on consolidated MCP entity tools. It is not an
implementation plan.

## Boundary

The backend REST contracts are the semantic authority for cross-entity links:

- `AssetLinkRequest`
- `ThreatModelLinkRequest`
- `RiskScenarioLinkRequest`
- `ControlLinkRequest`

Those DTOs share the same link body shape: `targetType`, `targetEntityId`,
`targetIdentifier`, `linkType`, `targetUrl`, and `targetTitle`. MCP must mirror
that shape in snake_case and forward only action-scoped link fields. Do not
change backend DTOs, controllers, services, repositories, migrations, or graph
projection to work around an MCP adapter schema omission.

## Incumbents To Reuse

- MCP consolidation: ADR-035. Keep consolidated action-discriminated tools
  (`gc_asset`, `gc_threat_model`, `gc_risk_scenario`, `gc_control`) and do not
  re-expand one REST endpoint into one MCP tool per link operation.
- Shared MCP link path: `mcp/ground-control/link-create.js` owns the
  `link_create` field allowlist, optional shared Zod fields, and required
  `target_type` / `link_type` preconditions for all consolidated link tools.
- MCP transport helpers: `pick`, `reqArg`, `toCamelCase`, `buildUrl`,
  `request`, `addAuthorizationHeader`, `RequestError`, and `parseErrorBody` in
  `mcp/ground-control/lib.js`. Do not add feature-local serializers, fetch
  wrappers, auth-header logic, or error renderers.
- Backend target semantics: `GraphTargetResolverService` decides whether a
  target type requires `targetEntityId` or `targetIdentifier`, verifies
  project-scoped existence for internal entities, and returns the normalized
  target slot the link service persists.
- Link services and repositories: `AssetService.createLink`,
  `ThreatModelLinkService.create`, `RiskScenarioLinkService.create`, and
  `ControlLinkService.create` already enforce duplicate detection, persistence,
  transaction boundaries, and lifecycle logging.
- Error contract: backend exceptions route through `GlobalExceptionHandler` and
  `ErrorResponse`; MCP propagates them through `RequestError` and `err()`.
- Documentation contracts: ADR-024 owns threat-model link boundaries; ADR-034
  shows the inventory pattern for future static drift checks; `docs/API.md`
  documents the internal-target versus external-target split.

## Cross-Cutting Layers

- MCP public schema: expose `target_entity_id` as an optional UUID wherever a
  consolidated tool exposes `link_create`. Keep `target_identifier`,
  `target_url`, and `target_title` available for the same action. Do not expose
  link fields only on one entity tool while leaving another consolidated link
  surface drifted.
- MCP action/body gate: `performLinkCreate` must require the parent id,
  `target_type`, and `link_type`, then forward only the shared link body fields
  with `pick`. Entity create/update fields and arbitrary unknown args must not
  leak into link bodies.
- Serialization: use `TO_CAMEL` and `toCamelCase` for
  `target_entity_id -> targetEntityId` and related link fields. Do not introduce
  a second snake-to-camel mapping.
- Backend parser and validators: Jackson enum binding and Bean Validation own
  `targetType`, `linkType`, URL/title/identifier size caps, and malformed UUID
  rejection after MCP's Zod shape check.
- Backend semantic validation: `GraphTargetResolverService` owns the internal
  versus external target choice. MCP should not duplicate the target-type switch
  or attempt to prove target existence before the REST call.
- Project scoping: the adapter passes the optional `project` query parameter
  through existing lib helpers; `ProjectService.resolveProjectId` /
  `requireProjectId` and repository `existsByIdAndProjectId` checks remain the
  scope boundary.
- Security: MCP calls still use `GC_BASE_URL` plus
  `GROUND_CONTROL_API_TOKEN` / `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN`
  through `addAuthorizationHeader`; `/api/v1/**` still passes the shared Spring
  Security path matrix. This change needs no new env var, secret, CLI argument,
  caller-supplied header, or admin catalog flag.
- Audit and observability: `ActorFilter`, `ActorHolder`,
  `RequestLoggingFilter`, Envers-audited link entities, and service-level SLF4J
  logs remain the audit/logging path. Do not log request bodies or bearer
  tokens from the MCP layer.
- Error leakage: preserve the stable backend error envelope for 400/401/403/409
  /422 responses. Do not reflect Authorization headers, raw request bodies,
  stack traces, process env, or response headers through MCP errors.
- Workflow policy: application-source changes under `mcp/**` require a
  changelog fragment. `make policy` is the repo-native completion gate.

## Extensibility

The seam is the shared link-create contract: one field inventory and one
handler helper for every consolidated link surface. A future link DTO field
should be added once to the shared body-field allowlist and optional Zod field
shape, then covered by shared tests for all consolidated tools.

If this drift class recurs beyond links, extend the ADR-034-style static
inventory pattern to request DTO mirrors rather than adding runtime reflection,
generated controllers, or tool-specific duplicate schemas.

## Gotchas And Anti-Patterns

- Do not use `target_identifier` as the UUID slot for first-class internal
  targets. Internal targets use `target_entity_id`; external or unmodeled
  targets use `target_identifier`.
- Do not make `target_entity_id` universally required in MCP. External targets
  intentionally require `target_identifier` and the backend is the authority for
  that distinction.
- Do not copy-paste separate `LINK_FIELDS` arrays into each entity tool. That
  recreates the exact drift class in #877.
- Do not fold link fields into entity create/update allowlists.
- Do not weaken backend validation, add compatibility aliases, or catch and
  rewrite 422 responses to hide wrong target-slot usage.
- Do not add new exception hierarchies, auth paths, graph write paths, or
  persistence shortcuts for this adapter bug.
- Do not treat MCP catalog visibility as authorization. Backend auth remains
  the enforcement layer.

## Non-Goals

- No backend REST, domain, persistence, graph, audit, or migration redesign.
- No new link target types, link types, relationship semantics, or lifecycle
  states.
- No OpenAPI/codegen migration or generic schema framework as part of #877.
- No remediation of unrelated create/update DTO drift unless it is separately
  scoped.
- No change to ADR-024, ADR-026, ADR-033, ADR-034, ADR-035, or workflow policy.
