---
id: GC-P024
title: "User, Group, and Role Administration"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 8
created_at: 2026-07-02T22:17:48.659868Z
updated_at: 2026-07-27T21:48:05.814114Z
---

# GC-P024 — User, Group, and Role Administration

## Statement

The system shall provide first-class user, group, and role administration as product data, replacing configuration-file bearer slots and the stock Spring Security users/authorities tables as the only principal model.

(a) Domain model. Users, groups, group membership, roles, credentials, and project-access grants shall be domain entities with lifecycle states, Envers audit history, and forward Flyway migrations building on the V059 seam. Roles shall be data-driven grants evaluated by the existing security chains and ADR-026 path matrix, not hardcoded enum pairs.

(b) Authorization semantics. Endpoint- and action-level authorization shall resolve through the user's roles and group memberships, with project-access grants scoping which projects a user can see and act on. Deny-by-default posture is preserved; no existing ADR-026 or ADR-037 control is weakened.

(c) Administration surface. Authorized administrators shall manage users, groups, roles, credentials, and project access via the REST API, MCP tools where appropriate, and the web console, with every identity lifecycle and access-affecting event audited.

(d) Migration and compatibility. Existing config-credential indexed bearer-slot and form-login deployments shall have a documented, fail-closed migration path. API bearer authentication shall remain supported, with tokens owned by user records and legacy credentials treated only as one-way migration input.

(e) Tenancy forward-compatibility. The model shall be forward-compatible with the tenant organization/workspace model in GC-P020 and membership lifecycle in GC-P018 without implementing tenancy.

(f) Scope boundary. Workflow-control APIs, Temporal operator signals, gate-authority grants, and gate-administration UI are out of scope because issue #1359 retired that product surface. Any restored workflow-control authority requires a new product ADR and closed permission-catalog entries before implementation.

## Rationale

V059 explicitly marks the stock users and authorities tables as the seam for a dedicated domain model. The existing two-role config-credential posture cannot express audited identity lifecycle, group-managed access, data-driven permissions, or per-project access. A single principal model is required so browser sessions and bearer callers share current lifecycle and authorization semantics without weakening ADR-026, ADR-033, or ADR-037. The model remains deliberately below GC-P018 and GC-P020 tenancy. Earlier gate-authority language is removed because issue #1359 retired the workflow-control and operator-signal product surface; this requirement does not recreate it.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1282` (GC-P024: users, groups, and roles as data)
- IMPLEMENTS → ADR `architecture/adrs/085-identity-model-users-groups-roles.md` (ADR-085 identity model)
- IMPLEMENTS → GITHUB_ISSUE `1282` (GC-P024: users, groups, and roles as data)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/identity/service/IdentityAdminService.java` (Identity administration service)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/identity/model/IdentityUser.java` (Identity user domain entity)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V205__create_identity_foundation.sql` (Identity foundation migration)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/identity/IdentityAdminServiceTest.java` (Identity administration service tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/IdentityAdminApiIntegrationTest.java` (Identity administration API integration tests)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-identity-admin.js` (Identity administration MCP tool)
