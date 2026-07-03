# ADR-085: Identity Model - Users, Groups, and Roles as Data

## Status

Accepted

## Date

2026-07-03

## Context

Ground Control currently has two principal stores:

- API callers authenticate with the indexed `groundcontrol.security.credentials[]`
  bearer-token slots from ADR-026. Each slot has a configured principal name
  and one of two roles, `USER` or `ADMIN`.
- Browser users authenticate through Spring Security form login against the
  stock V059 `users` and `authorities` tables from ADR-037. Those tables are
  deliberately only a security principal store: username, password hash,
  enabled flag, and `ROLE_USER` / `ROLE_ADMIN`.

That split is sufficient for the current Tailscale-only, small-operator
deployment, but it cannot support the milestone-17 console and workflow work.
Console-driven workflow actions need attributable principals, group-managed
project access, data-driven roles, and explicit authority for each permitted
operator signal. ADR-028 also requires signal endpoints to use ordinary
authentication, authorization, project scoping, validation, and audit.

V059 intentionally marks its tables as the seam for a future domain model. This
ADR defines that model so GC-P024 and issue #1282 can implement it without
weakening ADR-026, ADR-037, or ADR-033.

## Decision

### 1. Identity becomes a domain aggregate family

Ground Control will introduce an identity aggregate family at the V059 seam.
The V059 tables remain compatibility input during migration, not the long-term
domain model.

The model has these first-class records:

| Record | Purpose |
|--------|---------|
| `IdentityUser` | Human or service principal with stable UUID identity, login name, display name, lifecycle state, and audit history. |
| `PasswordCredential` | Browser-login credential for a human user. Stores only password verifier material. |
| `ApiTokenCredential` | Machine/API credential issued to an identity user. Stores only verifier material, token prefix/label, expiry, last-used metadata, and revocation state. |
| `IdentityGroup` | Named collection of users or service principals used to manage access in bulk. |
| `GroupMembership` | Audited membership edge from a user to a group, with state and effective timestamps. |
| `Role` | Data row describing a reusable grant bundle. Built-in roles are seeded data, not Java-only enum cases. |
| `RoleGrant` | Assignment of a role to a user or group, scoped globally or to one project. |
| `ProjectAccessGrant` | Explicit user or group access to a Ground Control project, independent of SaaS tenancy. |
| `GateAuthorityGrant` | Explicit authority to send a workflow operator signal for a project or project pattern. |

All records that change access decisions are audited. JPA entities use Envers
where the repo already uses aggregate history; operational decision logs may be
separate append-only tables when a denial or token use is not a lifecycle
change on the aggregate itself.

`IdentityUser` is the principal namespace. API tokens are never standalone
principals: a token resolves to the owning user or service user, then that
principal's current status, project access, roles, and gate authority are
evaluated.

### 2. One authentication projection remains

ADR-026 and ADR-037 are refined, not replaced. Both authentication modes still
end by populating Spring Security's `SecurityContext`.

- Bearer requests stay stateless and CSRF-exempt.
- Browser requests stay session-cookie based with CSRF protection.
- `ActorFilter` remains the single projection from authenticated principal to
  `ActorHolder`, MDC, and Envers actor fields.
- `X-Actor` remains a security-disabled dev/test fallback only.

The authentication result should carry stable principal identity and a bounded
set of authorities derived from identity-domain data. Controllers and request
DTOs must not accept caller-supplied actor fields, project-access decisions, or
gate-decision actors.

### 3. Roles are data, but authorization stays centralized

Roles are rows that reference a closed, versioned permission catalog. The
catalog names product authorities such as:

- authenticated API access,
- administration of identity records,
- project read/write access,
- workflow run start/cancel/retry,
- review-cap disposition or override,
- other operator signals accepted by the workflow contract.

The catalog is not arbitrary user-authored code. Adding a new protected action
requires adding a catalog entry and tests for the corresponding authorization
decision.

`ApiPathMatrix` remains the central REST path owner. The path matrix may
delegate selected decisions to an identity authorization service, but the
caller-visible posture is unchanged: deny by default, privileged paths require
explicit authority, and bearer and browser callers see equivalent
authorization outcomes.

The legacy `ROLE_USER` / `ROLE_ADMIN` vocabulary remains as a compatibility
projection during migration. Implementations may project identity-domain grants
to those coarse authorities where existing Spring Security APIs need them, but
new product decisions should be expressed against the permission catalog and
project access service rather than adding more hardcoded role enums.

### 4. Project access is not tenancy

Project access grants answer: "Can this principal see or act in this Ground
Control project?"

They do not answer: "Which commercial tenant owns this project?" or "Which
Temporal namespace isolates this tenant?" GC-P018 and GC-P020 will introduce
tenant organizations, workspaces, invitations, and membership lifecycle in a
future ADR. Until that lands:

- project access is evaluated against existing `Project` identity and
  `ProjectService` resolution;
- repositories and services remain project-scoped as they are today;
- Temporal workflow IDs and Search Attributes remain project-partitioned in
  the single namespace described by ADR-028 and ADR-081;
- no implementation should treat project access as a SaaS tenant boundary.

The identity model should leave clean join points for tenant/workspace
membership later, but it must not implement tenant isolation in this slice.

### 5. Workflow gate authority is explicit product data

Workflow operator signals are not covered by generic "is admin" checks alone
once the console is multi-principal. Each accepted signal type has an
authorization rule over:

- authenticated principal,
- project,
- workflow type or run,
- signal type,
- current run state,
- role/group grants and gate-authority grants.

Examples include cancel run, retry from a permitted phase, approve an
authorized review-cap disposition, or send another signal explicitly listed in
the workflow contract. PR merge remains the single mandatory human touchpoint
from ADR-029; it is observed from GitHub and is not re-modeled as a product
signal.

Every accepted or denied operator signal records an audit decision with actor,
project, run/workflow correlation, signal type, decision, reason, timestamp,
and source surface. Decision records must not contain prompts, secrets, bearer
tokens, raw password material, or unbounded agent output.

### 6. Migration path

The implementation must provide a documented, fail-closed migration from both
current principal stores.

Config bearer slots:

- existing `groundcontrol.security.credentials[]` entries import or map to
  service `IdentityUser` records plus `ApiTokenCredential` verifier records;
- imported credentials retain principal names for audit continuity;
- duplicate or invalid principal names fail startup or migration validation;
- raw token material is never logged and is not persisted in plaintext.

V059 browser users:

- rows from `users` and `authorities` migrate or map to `IdentityUser`,
  `PasswordCredential`, and role grants;
- existing BCrypt verifier material can be retained;
- disabled users stay disabled or suspended;
- `ROLE_ADMIN` and `ROLE_USER` map to seeded compatibility roles.

Administration surfaces:

- the existing admin-user REST surface must either route through the new
  identity domain service or be replaced by it;
- first-admin bootstrap continues to avoid password-in-argv production paths;
- MCP tools must not accept raw passwords or newly minted token values unless a
  non-transcript secret channel exists.

The default production posture remains locked down. A deployment with security
enabled and no valid admin/token bootstrap path must fail closed rather than
silently permit anonymous access.

## Consequences

### Positive

- Human and API callers share one domain principal namespace and one audit
  identity projection.
- Groups, roles, project access, and gate authority become inspectable product
  data instead of configuration-file or stock-table side effects.
- Workflow console actions can be authorized and audited without exposing
  Temporal Web or bypassing the product boundary.
- Existing ADR-026 bearer access and ADR-037 browser sessions remain valid
  compatibility paths.

### Negative

- Authentication and authorization become a multi-table domain concern rather
  than a small configuration object plus two Spring Security tables.
- Migration has to preserve audit continuity while removing the old split
  principal model.
- Tests need to cover browser, bearer, project-scoped, group-inherited,
  negative-authorization, and gate-signal cases.

### Risks

- If token credentials are modeled as independent principals, disabling or
  demoting a user would not reliably revoke machine access. Tokens must always
  resolve through an owning identity user.
- If roles-as-data bypass `ApiPathMatrix`, bearer and browser authorization can
  drift. The path matrix must remain the central entry point.
- If project access is treated as tenant isolation, SaaS security claims will
  outrun the architecture. Tenant/workspace isolation remains future work.
- If gate-authority decisions are audited only on success, denied attempts and
  policy probes disappear from the operator record. Denials need bounded audit
  records too.

## Non-Goals

- OIDC, SAML, MFA, self-service signup, email verification, and password reset.
  Issue #983 tracks the broader internet-exposed auth surface.
- SaaS tenant organizations, workspace membership, subscription entitlements,
  or tenant-to-Temporal-namespace mapping.
- A new workflow engine, workflow DSL, or Temporal namespace model.
- A second audit actor model or request-body actor override.
- Replacing ADR-026 bearer authentication, ADR-037 browser sessions, or ADR-033
  audit actor provenance.

## Related Requirements

- GC-P024 User, Group, and Role Administration
- GC-P018 Tenant Membership and Invitation Lifecycle
- GC-P020 Tenant Organization and Workspace Model
- GC-Q016 Workflow Operations and Agent Interaction Console
- GC-O009 Workflow Orchestration via Temporal

## Related ADRs

- ADR-016 Project Scoping
- ADR-026 REST API Access Control
- ADR-028 Temporal Workflow Orchestration Boundary
- ADR-029 Issue-Thread Gate Model
- ADR-033 Authenticated Audit Actor Provenance
- ADR-037 Browser Session Access Control
- ADR-081 Temporal Dev Workflow and Console Program
