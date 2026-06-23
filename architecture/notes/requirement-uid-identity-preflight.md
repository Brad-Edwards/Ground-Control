# Requirement UID Identity Preflight

Issue: #1197
Requirement: none

This note records architecture guardrails for server-side UID allocation,
project-scoped traceability reverse lookup, and legible UID uniqueness. It is
not an implementation plan.

## Boundary

The storage invariant is already project-local: `requirement.uid` is unique per
project through the PostgreSQL functional index
`uq_requirement_project_uid_ci` on `(project_id, LOWER(uid))`. Do not replace
that with global UID uniqueness, and do not rely on JPA `@UniqueConstraint` for
that requirement invariant because JPA cannot express the functional index.

Requirement creation may accept either an explicit `uid` or a server-allocation
request (`uidPrefix` at REST, `uid_prefix` at MCP), but not both. Explicit UID
creation remains the import/migration path and keeps existing requirement
normalization semantics. Server-generated UIDs should be uppercase and
monotonic within `(project, aggregate namespace, prefix)`, using the next
numeric suffix after all reserved rows, including archived rows. Gaps are not a
reuse pool.

Allocation belongs inside the owning service transaction. The allocator may
produce a UID string, but it must not become a generic entity writer. Owning
services still resolve the project, validate the command, construct the
aggregate, save through the repository, and publish any domain events.

Race safety must be a database-backed critical section plus the existing unique
constraint as the final guard. A plain `existsBy...` or `maxSuffix` check
followed by `save` is not sufficient under concurrent creates. The existing
transaction-scoped advisory-lock pattern in `UserAdminService` is the local
incumbent if serialization is needed without adding a new table.

Traceability reverse lookup must be scoped by project. Preserve ADR-011
artifact identifier conventions for this issue: GitHub issues and pull
requests remain raw decimal strings, ADRs remain ADR UIDs, and code/test/config
evidence remains repo-relative identifiers. Do not introduce
`owner/repo#number`, `#number`, `file:...`, or other alternate encodings unless
ADR-011 is amended and existing data/contracts are migrated deliberately.

Project-blind requirement UID lookups are invalid against the per-project
namespace. `RequirementRepository.findByUid` and `existsByUid` should either
disappear or gain project scope before any production path can call them.

## Incumbents To Reuse

- REST shape and validation: `RequirementRequest`, `TraceabilityLinkRequest`,
  Jackson enum binding, Bean Validation, and controller `@Valid`.
- Project resolution: `ProjectService.resolveProjectId(...)` and
  `requireProjectId(...)`; reverse lookup must not fall back to an unscoped
  artifact query when multiple projects exist.
- Write ownership: `RequirementService` owns `Requirement` creation and clone
  semantics; `TraceabilityService` owns traceability link reads/writes.
- Data access: project-scoped repository methods such as
  `findByProjectIdAndUidIgnoreCase`, `existsByProjectIdAndUidIgnoreCase`, and a
  project-scoped traceability reverse lookup that joins through
  `TraceabilityLink.requirement.project`.
- Persistence authority: Flyway migrations remain the source of database
  constraints; entity comments document functional indexes that cannot be
  represented in `@Table(uniqueConstraints = ...)`.
- Errors: `ConflictException`, `DomainValidationException`,
  `NotFoundException`, `GlobalExceptionHandler`, `ErrorResponse`, and the
  existing `DataIntegrityViolationException` to 409 translation.
- Audit and logs: Envers on `Requirement` and `TraceabilityLink`,
  `ActorFilter` / `ActorHolder`, request logging, and low-cardinality SLF4J
  events only where the owning service already logs lifecycle events.
- MCP transport and shape: `mcp/ground-control/index.js` Zod schemas,
  `lib.js` `request`, `pick`, `reqArg`, `toCamelCase`, `RequestError`, and
  bearer-token routing. The current requirement surface is the
  action-discriminated `gc_requirements` tool; update aliases only if they
  actually exist.
- Test and policy surfaces: `@WebMvcTest` controller slices, focused domain
  service tests, a persistence/concurrency integration test for allocation,
  MCP tool tests, `docs/API.md`, `mcp/ground-control/README.md`, and
  `make policy`.

## Cross-Cutting Layers

- Authentication and authorization: `/api/v1/**` continues through
  `ApiPathMatrix`, `BearerTokenAuthFilter`, browser-session rules, and
  `IpAllowlistFilter`. This issue adds no new role, token, CIDR, or security
  configuration surface.
- Request shape gates: REST uses Jackson plus Bean Validation; MCP uses Zod for
  caller ergonomics and body allowlists before forwarding to REST. Requiredness
  changes must be made in both places and in the generated/OpenAPI-facing docs.
- Project boundary: controller and MCP paths must carry or resolve exactly one
  project before UID or artifact lookup. A missing project in a multi-project
  instance should fail through the existing `project_required` validation path,
  not silently search every project.
- Persistence and transaction boundary: allocation, aggregate construction, and
  save run in the service transaction. The functional unique index and any
  aggregate-specific `(project_id, uid)` constraint remain the final authority.
- Error envelope: validation, duplicate UID, malformed enum, missing project,
  and late uniqueness failures must all flow through `ErrorResponse`; do not
  add controller-local handlers or expose constraint names, SQL, stack traces,
  tokens, or request bodies.
- OS/runtime exposure: no `gh`, `git`, `curl`, direct database shelling,
  argv-visible token passing, or live-service dependency is needed for UID
  allocation. MCP continues to use its configured base URL and environment
  tokens through `request`.
- Documentation and policy: API/MCP shape changes must keep docs, tests, and
  policy gates in sync. If policy or ADR workflow surfaces are changed, follow
  the repo rule to sync live Ground Control policy when reachable.

## Extensibility

The extension point is a small, namespace-aware UID allocator, not duplicated
suffix scanning in every service and not a generic CRUD layer. Its parameters
are the project id, the aggregate namespace, and the validated prefix; its
result is only the allocated UID. Each aggregate remains responsible for its
own case sensitivity, explicit UID semantics, repository query, and database
constraint.

The first additional namespace should prove that the abstraction is earning its
keep. Requirements plus multiple GRC aggregates clear that bar; a
requirement-only change would not. Adding another aggregate later should mean
registering its namespace/query/constraint semantics, not editing a
requirements-specific parser.

If GitHub issue identity later needs repository-qualified identifiers, that is
a traceability model migration. Amend ADR-011, migrate existing
`TraceabilityLink` rows and sync caches, update status-drift docs, and update
MCP/read-side consumers together.

## Gotchas And Anti-Patterns

- Do not use `gc_list_requirements` search, active-only listings, or archived
  filters to discover reserved UIDs.
- Do not match prefixes loosely. `PLAT` must not see `PLATFORM-001`, and
  `GC-GRC` must allocate from `GC-GRC-<number>`.
- Do not sort numeric suffixes lexicographically. `PLAT-10` comes after
  `PLAT-9`.
- Do not parse arbitrary regexes from callers. Prefix validation should be
  bounded and simple.
- Do not implement allocation by catching uniqueness failures inside a
  rollback-only transaction and continuing as if the persistence context were
  clean.
- Do not add a new exception hierarchy, error envelope, audit table, schema
  language, or MCP-only validation model.
- Do not add prompt-only workflow guidance in place of enforceable tool,
  service, repository, or policy changes.
- Do not change artifact identifier encoding to dodge project scoping.
- Do not treat GRC UID allocation as proof that GRC derivation, screening, or
  runtime evidence collection changed. This is identity plumbing only.

## Non-Goals

- No multi-tenancy or per-project authorization redesign.
- No global UID registry across all projects or all aggregate types.
- No owner/repo-qualified traceability identifier migration in this issue.
- No search semantics redesign for `gc_list_requirements`.
- No AGE graph, DAST/runtime, derivation-engine, or GRC screening behavior
  change.
- No generic MCP write proxy and no direct repository/database access from MCP.
