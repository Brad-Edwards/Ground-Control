---
id: GC-P017
title: "Authentication, Federation, and Machine Access"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 5
created_at: 2026-04-12T06:22:21.640677Z
updated_at: 2026-05-12T00:26:50.937808Z
---

# GC-P017 — Authentication, Federation, and Machine Access

## Statement

The system shall support user authentication and machine access suitable for hosted and self-managed deployments, including organization-scoped sign-in, federated identity integration, revocable API or service credentials, and timely deprovisioning when access is removed or expires.

## Rationale

GC-P011 requires access restriction, but a usable product also needs concrete identity entry points for humans and machines. Organizational adoption stalls when authentication, federation, and API access are left as deployment-specific afterthoughts.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/037-browser-session-access-control.md` (ADR-037: Browser Session Access Control)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/BrowserSecurityConfig.java` (BrowserSecurityConfig (form-login chain, CSRF, session, SessionRegistry, ConcurrentSessionFilter))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/PathAwareSessionExpiredStrategy.java` (PathAwareSessionExpiredStrategy (ConcurrentSessionFilter expired-event dispatch))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/users/UserAdminController.java` (UserAdminController (/api/v1/admin/users REST surface))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/service/UserAdminService.java` (UserAdminService (last-admin guard, advisory lock, BCrypt, session revocation))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/FirstAdminBootstrapRunner.java` (FirstAdminBootstrapRunner (CLI bootstrap; rejects --password argv; fail-closed POSIX checks))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/UserCredentialPolicy.java` (UserCredentialPolicy (single source of truth: username regex + password length range))
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V059__create_users.sql` (V059 — Spring Security users + authorities tables (ROLE_USER / ROLE_ADMIN, BCrypt))
- IMPLEMENTS → CONFIG `backend/src/main/resources/application.yml` (application.yml — session cookie hardening (HttpOnly + Secure + SameSite=Strict + GC_SESSION name + 60m timeout))
- IMPLEMENTS → CODE_FILE `frontend/src/lib/api-client.ts` (apiFetch / apiUpload / apiDelete — credentials: 'same-origin', X-XSRF-TOKEN, 401 → /login redirect)
- IMPLEMENTS → CODE_FILE `frontend/src/components/layout/app-layout.tsx` (AppLayout — Sign out button (POST /logout with X-XSRF-TOKEN))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib.js — list / update_role / update_enabled / delete_user (no password surface))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP index.js — gc_user_admin tool (no create_user; passwords never in agent payloads))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/BrowserSessionIntegrationTest.java` (BrowserSessionIntegrationTest (end-to-end form login, CSRF, logout, disabled-user session revocation, login-CSRF guard))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/UserAdminControllerTest.java` (UserAdminControllerTest (@WebMvcTest — wire format, validation, error envelopes))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/UserAdminServiceTest.java` (UserAdminServiceTest (last-admin guard, session revocation, validation))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/FirstAdminBootstrapRunnerTest.java` (FirstAdminBootstrapRunnerTest (argv guard, mode-600 file gate, idempotency, password buffer wipe))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/UserCredentialPolicyContractTest.java` (UserCredentialPolicyContractTest (Java regex ↔ SQL CHECK equivalence))
- TESTS → TEST `frontend/src/lib/api-client.test.ts` (api-client.test.ts (credentials, CSRF header echo, 401 → login redirect))
- DOCUMENTS → DOCUMENTATION `docs/API.md` (docs/API.md — Admin Users (ADR-037) endpoint reference)
- DOCUMENTS → DOCUMENTATION `docs/deployment/DEPLOYMENT.md` (DEPLOYMENT.md — Web UI login + first-admin bootstrap + curl flow)
- IMPLEMENTS → CODE_FILE `frontend/src/pages/login.tsx` (Login page (CSRF-aware form login, credential submission, post-login redirect))
- TESTS → TEST `frontend/src/pages/login.test.tsx` (Login page tests (form rendering, CSRF header, credential post, redirect, error display))
- DOCUMENTS → GITHUB_ISSUE `#756` (GC-P017: Authentication, Federation, and Machine Access)
- IMPLEMENTS → GITHUB_ISSUE `857` (#857 Web UI login for single-tenant installs: Spring Security form-login + JDBC users)
- IMPLEMENTS → PULL_REQUEST `870` (PR #870 — Add ADR-037 web UI login: form-login + JDBC users alongside ADR-026 bearer chain)
