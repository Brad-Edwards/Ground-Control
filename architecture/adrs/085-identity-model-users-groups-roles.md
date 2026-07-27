# ADR-085: Identity Model - Users, Groups, and Roles as Data

## Status

Accepted

## Date

2026-07-03

## Amendment

Amended 2026-07-27 for GC-P024 issue #1282 after issue #1359 removed the
Temporal orchestration and workflow-control product surface. This ADR no
longer defines `GateAuthorityGrant`, operator-signal permissions, workflow
control authority, or gate-administration UI. Reintroducing any of those
concepts requires a new product ADR and new entries in the closed permission
catalog; identity administration must not recreate them indirectly.

The amendment also fixes the boundary of issue #1282 as the identity/RBAC
foundation. The authentication-store and credential cutover remains issue
#1411, the React console remains #1412, project-wide admission enforcement
remains #1457, and cross-surface MCP conformance remains #1458.

## Context

Ground Control currently has two principal stores:

- API callers authenticate with indexed
  `groundcontrol.security.credentials[]` bearer-token slots from ADR-026. Each
  slot has a configured principal name and one of two roles, `USER` or
  `ADMIN`.
- Browser users authenticate through Spring Security form login against the
  stock V059 `users` and `authorities` tables from ADR-037. Those tables are a
  security principal store only: username, password hash, enabled flag, and
  `ROLE_USER` / `ROLE_ADMIN`.

That split cannot express audited identity lifecycle, group-managed access,
data-driven roles, or per-project admission. V059 intentionally marks its
tables as the seam for a dedicated domain model. GC-P024 establishes that
model without weakening ADR-026, ADR-033, or ADR-037 and without treating
project access as SaaS tenancy.

Issue #1282 is a deliberately transitional foundation. It introduces the
domain data and one authorization decision seam, but it does not silently
reinterpret either legacy principal store or make the new credential model
authoritative ahead of #1411.

## Decision

### 1. Identity is a domain aggregate family

Identity product data lives under the domain boundary, not in
`shared.security`, the V059 tables, controller DTOs, or configuration
properties.

| Record | Purpose |
|--------|---------|
| `IdentityUser` | Human or service principal with stable UUID identity, immutable normalized login name, mutable display name, principal kind, lifecycle state, and audit history. |
| `IdentityGroup` | Named collection of identity users for bulk access management. Groups are not tenants or workspaces and are not nested in this foundation. |
| `GroupMembership` | Audited user-to-group membership with its own lifecycle. |
| `Role` | Data row describing a reusable permission bundle. It has a stable immutable key separate from its display name. |
| `RolePermissionAssignment` | Audited assignment of one closed-catalog permission to a role. |
| `RoleGrant` | Audited assignment of a role to either one user or one group, globally or for one project. |
| `ProjectAccessGrant` | Audited admission of either one user or one group to one existing Ground Control project. |

`IdentityUser` is the only principal namespace. Groups organize users but
never authenticate. Roles bundle actions but never authenticate. A role grant
does not imply project admission, and a project-access grant does not imply an
action permission.

User/group polymorphism is represented with real user and group foreign keys
plus a database constraint that requires exactly one subject. Do not introduce an
unconstrained `(subject_type, subject_id)` pair or a speculative principal
supertype: both subjects are internal aggregates and referential integrity is
available.

Each aggregate owns a closed lifecycle vocabulary and transition rules. Do
not introduce one generic `LifecycleState` across users, groups, roles,
memberships, and grants merely because some labels coincide. Access-bearing
records are revoked or deactivated through lifecycle transitions rather than
hard-deleted; only active users, groups, memberships, roles, assignments, and
grants participate in an authorization result.

Credentials remain part of the long-term GC-P024 model, owned by an
`IdentityUser`, but `PasswordCredential` and `ApiTokenCredential` are not part
of the #1282 foundation schema or administration contract. Their verifier,
issuance, revocation, and one-way legacy migration semantics land with the
authoritative authentication-store cutover in #1411.

### 2. Permissions are closed product vocabulary; roles are data

The permission catalog is closed and versioned. Permission keys are stable
product-contract identifiers, not administrator-authored strings, Java role
enums, Spring expressions, URL patterns, or executable policy fragments.
Administrators may assign catalog permissions to role rows; they may not
create new permissions.

The backend permission catalog is the semantic authority. Its version and
keys are exposed through the generated OpenAPI contract, and any committed
authorization-contract mirror is drift-checked under ADR-082. Adding a
protected action requires all of the following as one contract change:

- a new catalog entry and catalog-version change;
- an `ApiPathMatrix` or service-action enforcement point;
- negative authorization coverage; and
- any generated REST, MCP, or frontend contract refresh required by ADR-082.

The initial catalog covers the existing explicit ADR-026/ADR-037 path-matrix
classes and identity administration. It contains no workflow start, cancel,
retry, gate, operator-signal, or review-cap authority. Permission keys do not
encode `ROLE_USER` or `ROLE_ADMIN`.

`USER` and `ADMIN` survive only as seeded compatibility role data and as the
temporary Spring authority projection required by the two legacy
authentication stores. New product authorization must not add another
hardcoded role pair or branch on a role display name.

### 3. One effective-authorization decision owns action and project scope

The domain exposes one effective-authorization service over:

```text
principal identity + permission key + optional project identity
```

An allow result requires an active user and an active direct or group-derived
role path to the requested permission. A project-scoped request additionally
requires both:

- a role grant that is global or scoped to that same project; and
- an active direct or group-derived `ProjectAccessGrant` for that project.

Absent, unknown, inactive, revoked, cross-project, or internally inconsistent
data denies. Direct and group grants form an allow-only union; this foundation
does not add deny grants, nested groups, wildcard projects, or an implicit
administrator bypass. A global role answers which actions a principal may
perform; it does not grant visibility into every project.

The optional project argument is the extensibility seam. Global actions use
no project. Project-owned actions use the resolved `Project` UUID. Future
workspace or organization membership can become an additional admission
factor without changing permission keys or overloading `ProjectAccessGrant`
with tenant meaning.

Authorization is evaluated from current lifecycle and grant state at the
protected action. It must not be a login-time-only snapshot. A future cache
requires explicit versioning/invalidation on every access-affecting mutation;
there is no authorization cache in the foundation.

### 4. `ApiPathMatrix` remains the REST policy owner during transition

`ApiPathMatrix` remains the central REST entry point shared by bearer and
browser chains. A shared-security Spring `AuthorizationManager` adapter may
extract the authenticated principal and delegate to the domain
effective-authorization service; the domain service does not import Spring
Security or web types.

Issue #1282 does not switch `BearerTokenAuthFilter` or
`JdbcUserDetailsManager` to the identity tables. Existing path rules therefore
continue to enforce their current `ROLE_USER` / `ROLE_ADMIN` behavior. The new
identity administration namespace is `/api/v1/admin/identity/**` and uses one
explicit, temporary compatibility bridge:

- an authentication that carries an identity UUID is evaluated for the
  identity-administration permission by the effective-authorization service;
- an unmigrated legacy principal is admitted only when the existing security
  chain supplies `ROLE_ADMIN`; and
- every other unmapped permission or authority denies.

The bridge is narrow to the identity administration namespace, is tested for
both chains, does not create identity rows or grants from legacy names, and is
removed by #1411. It is not a general `ROLE_ADMIN` fallback for new product
permissions.

The existing `/api/v1/admin/users` contract continues to administer the stock
V059 browser store until #1411 so a current deployment does not lose its login
administration path. New domain-user contracts live under the identity
namespace and must not reuse `SecurityProperties.Role`, `UserSummary`, or the
legacy user-admin response shape as if they were domain objects. This
temporary separation is explicit compatibility, not two competing principal
models.

No #1282 change adds identity rows to `SecurityProperties`, changes the
indexed credential env shape, mutates V059, or makes config credentials read
the identity tables. `SecurityProperties.validate`, the production env
schema, deploy validator, both existing security chains, and the first-admin
bootstrap remain authoritative for the legacy stores until #1411.

### 5. Administration preserves delegation and recoverability

REST administration is a thin-controller-to-service/aggregate-to-repository
flow. Request records use Bean Validation for transport shape; immutable
command records carry writes into the domain; services own transactions,
lifecycle transitions, delegation rules, and aggregate coordination;
repositories own queries. Controllers never accept an actor, an effective
permission set, or an authorization decision from the caller.

List contracts are bounded/pageable. Domain IDs are UUIDs. Project inputs
resolve through `ProjectService` and repository queries remain
project-scoped. The immutable login-name validation already centralized by
`UserCredentialPolicy` is reused for V059 compatibility rather than copied
into another regex; group and role naming rules remain their own domain
contracts and must not be inferred from the username grammar.

Every operation that can remove the last effective global identity
administrator participates in one serialized transaction guard. This includes
user, group, role, membership, role-permission, and role-grant lifecycle
changes—not only deleting or demoting one user. The guard evaluates effective
global identity-administration permission after the proposed mutation and
reuses the existing PostgreSQL transaction-scoped advisory-lock pattern so two
concurrent changes cannot both observe a safe pre-state and commit a lockout.

Delegation is fail-closed. A caller cannot assign a permission or project
scope that the caller is not authorized to delegate. Creating a role,
attaching permissions, then granting it is not a route around that check; all
three mutations use the same effective-authorization and delegation rules.
The actor comes from the authenticated server context.

Lifecycle, grant, and role changes that affect an authoritative principal
expire that principal's browser sessions through the existing
`SessionRegistry` path. During #1282 the identity store is not yet an
authentication source, so the existing `UserAdminService` remains the owner
of legacy V059 session revocation; #1411 moves that responsibility with the
authoritative store.

### 6. Persistence and audit use the existing spine

V059 is immutable. Identity tables and their Envers shadows are introduced by
new forward Flyway migrations after the migration head. The live schema
backstops the domain with foreign keys, check constraints for closed states,
checks that require exactly one subject, uniqueness for stable keys and active
assignments/grants, and indexes for effective-authorization and reverse
administration reads.

Seeded compatibility roles use stable immutable keys and deterministic
identities so later migrations can refer to them without matching mutable
display names. Seed data does not assign a legacy username or config principal
to an identity user in #1282.

All identity lifecycle and access-affecting records are `@Audited` and have
matching `_audit` migrations, including subject, role, permission, and project
identity needed to reconstruct a deleted or revoked edge. A relation to the
non-audited `Project` aggregate records the project identity without making
`Project` audited. Audit tables reference `revinfo(rev)` so the catalog-driven
`AuditRetentionJob` discovers them automatically; do not add a second audit
writer, revision table, or hand-maintained retention list.

`SecurityContext` to `ActorFilter` to `ActorHolder`/MDC/Envers remains the
single actor path. Successful mutations log bounded event names, stable
entity/project identifiers, permission keys, and outcomes through the existing
structured SLF4J pipeline. Passwords, token material, verifier hashes, session
IDs, authorization headers, request bodies, and unbounded lists are absent
from logs, errors, audit fields, and telemetry.

### 7. REST, MCP, and generated contracts stay aligned

REST errors continue through `GroundControlException`,
`GlobalExceptionHandler`, and `ErrorResponse`. The identity domain reuses
`DomainValidationException`, `ConflictException`, `NotFoundException`, and
`AuthorizationException`; it does not add an identity-only exception
hierarchy or return hand-built error JSON. Security-chain 401/403 responses
remain on `ApiAuthenticationEntryPoint` and `ApiAccessDeniedHandler` and never
echo an authorization rationale or credential detail.

The committed OpenAPI document and generated TypeScript types are refreshed
from the backend through ADR-082 tooling. They are not hand-edited. Any
intentional breaking replacement of the legacy user-admin contract is
declared only when #1411 performs the cutover; #1282's namespaced domain
contract is additive.

MCP exposes only curated, non-secret identity administration whose REST
contract is stable. The adapter uses Zod shapes, action-specific field
allowlists, the shared REST client/`RequestError` path, and the OpenAPI
write-contract parity inventory. It does not widen the generic `gc_query`
admin allowlist. Password input, password verifiers, raw API-token input,
newly minted raw tokens, session/CSRF values, and credential migration are
absent from MCP arguments and results.

MCP authentication continues through `addAuthorizationHeader`, which reads
the existing token environment and sends the header in the backend request.
Identity tooling must not place a bearer token or future minted credential in
process argv, a URL, a tool description, an MCP transcript, or a GitHub issue
record.

### 8. Project access is not tenancy

Project access answers only: “Can this principal see or act in this existing
Ground Control project?” It does not answer which commercial tenant owns the
project, which workspace the user joined, or which organization controls an
identity.

`ProjectAccessGrant` uses the existing `Project` UUID and
`ProjectService`/project-scoped repository conventions. Identity users,
groups, roles, and projects retain stable UUID identities so GC-P018 and
GC-P020 can add organization/workspace membership and ownership joins later.
This foundation adds no `tenant_id`, organization, workspace, invitation,
subscription, tenant-role, tenant-group, or tenant-isolation claim.

Project-wide admission and concealed ownership across existing project-owned
APIs is #1457. Issue #1282 supplies and tests the decision seam but does not
retrofit every controller or repository ahead of that issue.

### 9. Credential cutover is one-way and fail-closed

Issue #1411 owns the authoritative credential model and the cutover of both
authentication chains. That cutover must preserve these decisions:

- password and API-token credentials belong to an `IdentityUser`; a token is
  never a standalone principal;
- only password verifier material or token verifier material is persisted;
  an API token may be returned raw once at creation but is never recoverable;
- existing BCrypt verifiers may migrate without recovering plaintext;
- config credential slots and V059 rows are one-way migration input, never a
  second live source after cutover;
- disabled users and revoked/expired credentials fail authentication;
- duplicate or ambiguous legacy principal mapping fails startup/migration;
- security enabled with no effective administrator or valid bootstrap path
  fails closed; and
- `FirstAdminBootstrapRunner`'s password-file/environment/TTY handling remains
  the incumbent secret-input pattern. Password or token values are never
  accepted in argv.

The migration must update the application binding, env templates, production
compose passthrough, `deploy/docker/env.schema`, `validate-env.sh`, deployment
documentation, MCP token resolution, and their policy/tests as one boundary.
Issue #1282 does not partially change those shapes.

## Consequences

### Positive

- Identity, groups, roles, permission assignments, and project access become
  inspectable and audited product data.
- Authorization has one current-state, deny-by-default decision over action
  and optional project instead of controller-local role checks.
- Existing bearer and browser deployments remain operational until the
  deliberate #1411 cutover.
- Project access remains separable from future organization/workspace
  membership.
- The retired workflow-control surface cannot return through an identity
  grant or console screen without a new product decision.

### Negative

- The foundation temporarily has a clearly namespaced identity-admin contract
  alongside the V059 user-store admin contract.
- Authorization evaluation spans multiple audited tables and needs
  concurrency-safe administration invariants.
- Contract, migration, negative-authorization, audit, and controller-slice
  coverage all grow with the surface.

### Risks and guardrails

| Risk | Guardrail |
|------|-----------|
| Role rows become executable user-authored policy | Permissions are closed catalog keys; roles only bundle assignments. |
| Role and project access are conflated | Permission and project admission are independent conjuncts in the one effective service. |
| Legacy `ROLE_ADMIN` becomes a permanent bypass | Compatibility is exact-path, explicit, tested, and removed by #1411. |
| A non-user authenticates through a group or token row | Only `IdentityUser` is a principal; groups and credentials resolve to an owning user. |
| A mutation leaves no effective administrator | Every relevant mutation uses one post-change effective check under a shared transaction lock. |
| Concurrent grant changes bypass the guard | Reuse the PostgreSQL transaction-scoped advisory-lock pattern. |
| Access changes are delayed by session/caching snapshots | Evaluate current state per action; expire authoritative sessions; cache only with explicit invalidation. |
| Audit history loses the subject or project edge | Audit shadows retain identity-defining FK values and use the existing revision spine. |
| Cross-project data is leaked before #1457 | Do not claim enforcement beyond the seam; new identity queries remain explicitly scoped and project-owned APIs keep their current contracts. |
| MCP or errors expose credentials | No credential fields in #1282 MCP; shared error/client paths; raw secrets excluded from every transcript and log. |

## Verification guardrails

- Service and repository tests cover direct and group-derived permissions,
  global versus project-scoped role grants, the independent project-access
  conjunct, inactive/revoked paths, and default denial.
- Concurrency-capable tests prove last-effective-administrator protection
  across user, group, role, membership, permission-assignment, and grant
  mutations.
- Security-enabled tests cover anonymous denial, wrong-role denial, the narrow
  legacy-admin bridge, and parity between bearer and browser chains.
- `@WebMvcTest` controller slices cover validation and the standard error wire
  contract so the normal Sonar job receives coverage.
- Migration smoke tests probe live and audit-table shapes, constraints, seed
  rows, V059 compatibility, and Envers history; `ddl-auto=validate` alone is
  not audit-table evidence.
- OpenAPI generation, generated TypeScript drift, MCP Zod/field allowlists,
  tool descriptions, and the MCP/OpenAPI write-contract inventory remain in
  sync.
- Security tests must fail if current-state evaluation, project admission,
  delegation checks, last-admin protection, or audit actor provenance is
  removed; existence-only assertions are insufficient.

## Non-Goals

- Switching either authentication chain or migrating credentials in #1282.
- Password/API-token administration or secret-bearing MCP operations in
  #1282.
- React identity administration in #1282.
- Project-wide admission enforcement or concealed ownership in #1282.
- Tenant organizations, workspaces, invitations, subscriptions, tenant
  groups, tenant roles, or tenant isolation.
- Nested groups, deny grants, wildcard project grants, administrator bypass,
  arbitrary policy expressions, or user-created permission keys.
- OIDC, SAML, MFA, self-service signup, email verification, password reset, or
  account recovery.
- Workflow-control APIs, Temporal operator signals, gate-authority grants,
  gate-administration UI, or workflow-control permission keys.
- A second audit actor model, revision table, error envelope, configuration
  schema, or request-body actor override.

## Related Requirements

- GC-P024 User, Group, and Role Administration
- GC-P018 Tenant Membership and Invitation Lifecycle
- GC-P020 Tenant Organization and Workspace Model

## Related ADRs

- ADR-016 Project Scoping
- ADR-026 REST API Access Control
- ADR-029 Issue-Thread Gate Model
- ADR-033 Authenticated Audit Actor Provenance
- ADR-034 API Enum Contract Single Source of Truth
- ADR-037 Browser Session Access Control
- ADR-082 Contract Surface Architecture and Enforcement Gates
- ADR-089 Retire the GRC Product Surface and Next-Issue Recommendation
