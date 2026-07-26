# Requirement UID Workflow Validation Preflight

Issue: #1425
Requirement: none

This note records architecture guardrails for accepting requirement UIDs at
MCP workflow boundaries. It does not implement the validator or define an
implementation plan.

## Architecture Boundary

A stored requirement UID is project-local identity, not a value whose
existence can be inferred from the allocator's prefix or numeric-suffix
grammar. ADR-060's prefix grammar governs requests for server allocation; it
does not define which explicit or legacy UIDs may be looked up. The
project-scoped Ground Control lookup is the semantic authority for whether a
UID such as `APP-2` identifies a requirement.

MCP input validation may enforce transport-safe scalar bounds that agree with
the backend UID field, but it must not reject a UID because of a client-only
segment-length or suffix convention. All curated workflow tools that accept a
requirement UID must share that input contract, including direct reads,
PR-body rendering, traceability reconciliation, final-report/completion
records, mechanical workflow entry, and workflow-ingest configuration.

Keep three concepts separate:

- requirement UID input validation: a bounded identifier scalar;
- project-scoped identity resolution: a REST lookup through the existing
  requirement service and repository;
- rendered-body recognition: a presentation-policy check over Markdown.

The rendered-body scanner is not an identity validator. It may have a
language-specific search form in JavaScript and Python, but it must recognize
every UID accepted by the structured workflow path, including `APP-2`.

## Canonical Incumbents

- Backend identity and bounds: `Requirement.uid`,
  `RequirementRequest.uid`, `RequirementController.getByUid`,
  `RequirementService.getByUid`, and
  `RequirementRepository.findByProjectIdAndUidIgnoreCase`.
- MCP transport: the `gc_get_requirement` shape in
  `mcp/ground-control/index.js`, `getRequirementByUid`, `request`,
  `RequestError`, `encodeURIComponent`, and bearer-token header routing in
  `mcp/ground-control/lib.js`.
- Workflow composition: `gc_assert_traceability_reconciled`,
  `gc_post_final_report`, `gc_assert_completion`, and `gc_render_pr_body`;
  the composite completion tool remains the canonical closeout path.
- Durable rendering and safety: `validatePrBodyInput`,
  `validateFinalReportInput`, `detectSensitiveBodyContent`, reserved-marker
  rejection, body-size caps, and argv-based GitHub posting in the MCP server.
- PR policy: `PR_REQUIREMENT_RE`, `checkPrBodyShape`,
  `tools/policy/checks.py::PR_REQUIREMENT_RE`, `check_pr_body`, and the
  JavaScript-renderer-to-Python-policy compose test in
  `tools/tests/test_policy.py`.
- Workflow discovery and telemetry: `extractInScopeRequirementUids` and
  `extractRequirementUids`. These must not silently drop a valid short UID and
  turn a requirement-backed run into a requirement-free run.

## Cross-Cutting Layers

- **MCP schema gate:** use one shared structured UID contract across the
  affected Zod schemas. Preserve non-empty and bounded scalar checks without
  imposing allocator syntax. Runtime validators in `lib.js` must agree so a
  direct library caller cannot bypass or contradict the tool schema.
- **REST and project boundary:** keep `encodeURIComponent` for the path
  segment, pass or resolve one project, and let the existing case-insensitive
  project-scoped lookup establish identity. Do not add an unscoped fallback.
- **Authentication and authorization:** requests continue through the MCP
  bearer's HTTP `Authorization` header and the existing backend
  `ApiPathMatrix`, authentication filters, project resolution, and controller
  surface. No role, token, CIDR, or browser-session rule changes.
- **Error handling:** Zod shape errors remain MCP `-32602` errors only for
  malformed transport input. Unknown UIDs must reach Ground Control and return
  through `RequestError` and the backend's
  `GroundControlException`/`GlobalExceptionHandler`/`ErrorResponse` path. Do
  not add a UID-specific exception hierarchy or leak raw responses, stack
  traces, or project data.
- **Markdown and marker safety:** broadening UID acceptance must not make UID
  fields a free-form Markdown injection channel. Apply bounded, single-value
  input handling and the existing sensitive-content, reserved-marker, and
  body-size gates. Escape at the renderer boundary where required; do not use
  an identity regex as the only output-safety control.
- **Policy composition:** the JavaScript structural check and Python CI policy
  must accept the same rendered short UID. Keep their existing compose test
  and add a shared representative corpus so one language cannot regress while
  the other remains green.
- **OS and secret exposure:** no new environment variable, configuration
  binding, filesystem access, shell interpolation, or token-in-argv path is
  needed. REST tokens stay in headers; privileged GitHub writes stay in the
  MCP server's fixed argv calls.
- **Observability:** this is input-contract repair, not a new lifecycle event.
  Preserve existing structured refusal envelopes and request logging; do not
  log bearer tokens, full issue bodies, or caller payloads, and do not add a
  UID-specific metric unless an established operational need appears.
- **Persistence and audit:** no entity, migration, transaction, repository
  query, Envers, or `ActorHolder` change is required. The existing database
  uniqueness and audited requirement/traceability aggregates remain
  authoritative.

## Extensibility

The seam is a shared bounded-identifier contract plus contract fixtures, not a
new UID value-object hierarchy. The fixture set should cover the established
forms (`APP-2`, `GC-O007`, `GC-O-007`), exact-value rejection cases
(surrounding prose, empty values, multiline values, over-bound values), and
rendered-body recognition in both JavaScript and Python.

If Ground Control later adopts a stricter full-UID grammar, define it first at
the backend domain/API boundary, account for existing stored identifiers, and
then derive client validation and fixtures from that contract. Do not tighten
one workflow tool independently.

## Gotchas and Anti-Patterns

- Do not reuse `RequirementUidAllocator` prefix validation as full-UID
  validation; prefix allocation and identity lookup are different concepts.
- Do not fix only the three reported Zod fields. Runtime final-report
  validation, PR-body shape checking, Python PR policy, issue-body extraction,
  mechanical workflow bootstrap, and config UID lists can otherwise fail or
  silently omit the same UID later.
- Do not replace the anchored structured-input check with an unanchored body
  search. A string containing a UID is not itself a UID.
- Do not leave `PR_REQUIREMENT_RE` stricter than the renderer input contract;
  that merely converts an MCP input failure into a renderer or CI failure.
- Do not add a Ground Control lookup to `gc_render_pr_body`. ADR-036 keeps the
  renderer deterministic; it should validate and safely render the structured
  UID value supplied by the workflow, not acquire project identity or network
  failure modes.
- Do not let an empty extracted UID list trigger the orphaned-link audit when
  the issue's Requirements section names a valid short UID.
- Do not normalize, rewrite, or allocate a caller-supplied UID in the MCP
  layer. Use the canonical UID returned by Ground Control when a lookup occurs.
- Do not duplicate Zod schemas, runtime predicates, test corpora, exception
  envelopes, or workflow reconciliation logic.
- Do not weaken sensitive-content, reserved-marker, no-deferral, merge,
  traceability, review, CI, or Sonar gates while changing UID acceptance.

## Non-Goals

- No backend requirement model, database constraint, UID allocator, import,
  clone, or case-insensitive lookup redesign.
- No global UID namespace or cross-project lookup.
- No change to requirement status, traceability semantics, orphan detection,
  completion ordering, or durable issue-thread markers.
- No new REST endpoint, MCP tool, configuration property, log event, metric,
  exception family, or generic identifier abstraction.
- No relaxation of non-UID workflow inputs or GitHub publishing safeguards.
