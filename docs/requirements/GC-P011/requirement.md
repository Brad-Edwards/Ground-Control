---
id: GC-P011
title: "Access Control"
status: ACTIVE
type: NON_FUNCTIONAL
priority: MUST
wave: 1
created_at: 2026-03-15T19:59:14.963964Z
updated_at: 2026-05-03T17:27:03.310500Z
---

# GC-P011 — Access Control

## Statement

The system shall support configurable access restrictions such that only authorized users or network locations can reach the application. Access policy shall be configurable without code changes.

## Rationale

A remotely deployed system exposed to the network must restrict who can access it. The mechanism (IP allowlist, tunnel, identity provider, etc.) is an implementation choice, but the ability to restrict access is a hard requirement before any non-localhost deployment.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/SecurityProperties.java` (SecurityProperties (groundcontrol.security configuration))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/ApiSecurityConfig.java` (ApiSecurityConfig (Spring Security filter chain + path matrix))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/BearerTokenAuthFilter.java` (BearerTokenAuthFilter (Authorization: Bearer authentication))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/IpAllowlistFilter.java` (IpAllowlistFilter (CIDR-based source IP gate))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/ApiAuthenticationEntryPoint.java` (ApiAuthenticationEntryPoint (401 ErrorResponse envelope))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/ApiAccessDeniedHandler.java` (ApiAccessDeniedHandler (403 ErrorResponse envelope))
- DOCUMENTS → ADR `architecture/adrs/026-rest-api-access-control.md` (ADR-026: REST API Access Control)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/ApiSecurityIntegrationTest.java` (ApiSecurityIntegrationTest (end-to-end auth/authz/IP allowlist))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/ApiSecurityConfigTest.java` (ApiSecurityConfigTest (slice unit tests for path matrix))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/SecurityPropertiesTest.java` (SecurityPropertiesTest (config validation))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/BearerTokenAuthFilterTest.java` (BearerTokenAuthFilterTest (token parsing + role assignment))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/IpAllowlistFilterTest.java` (IpAllowlistFilterTest (CIDR matching + 403 envelope))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/ApiAuthenticationEntryPointTest.java` (ApiAuthenticationEntryPointTest (401 envelope + WWW-Authenticate))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/ApiAccessDeniedHandlerTest.java` (ApiAccessDeniedHandlerTest (403 envelope))
- IMPLEMENTS → ADR `architecture/adrs/032-age-query-construction-boundary.md` (ADR-032 AGE Query Construction Boundary — refines Access Control at the AGE adapter)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/infrastructure/AgeGraphServiceTest.java` (AgeGraphServiceTest — Sanitization / PropertyKeyRegistry / AgtypeParsing nested classes verify Access Control posture at the AGE adapter)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AgeGraphServiceIntegrationTest.java` (AgeGraphServiceIntegrationTest — adversarial-input materialization round-trip verifies Access Control posture against real AGE)
- IMPLEMENTS → CONFIG `deploy/docker/docker-compose.prod.yml` (ADR-026 indexed credential / IP-allowlist env passthrough on backend service (#828))
- IMPLEMENTS → CODE_FILE `tools/policy/checks.py` (run_deploy_compose_credential_passthrough — make policy structural gate (#828))
- IMPLEMENTS → CONFIG `.github/workflows/ci.yml` (policy-live live API token wiring restricted to refs/heads/main (#828))
- IMPLEMENTS → CONFIG `deploy/docker/.env.template` (ADR-026 indexed credential block placeholders (#828))
- DOCUMENTS → DOCUMENTATION `docs/deployment/DEPLOYMENT.md` (Pre-existing-deployment ADR-026 auth migration playbook (#828))
- DOCUMENTS → ADR `architecture/adrs/037-browser-session-access-control.md` (ADR-037: Browser Session Access Control (refines ADR-026))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/BrowserSecurityConfig.java` (BrowserSecurityConfig (second SecurityFilterChain, browser session, CSRF gate matrix))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/BearerRequestMatcher.java` (BearerRequestMatcher (chain discriminator: Authorization: Bearer routes to API chain))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/ApiPathMatrix.java` (ApiPathMatrix (shared /api/v1/** authority matrix used by both chains))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/ApiRequestPaths.java` (ApiRequestPaths (shared API-shaped request classifier for entry point + access denied + request cache))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/DelegatingAuthenticationEntryPointFactory.java` (DelegatingAuthenticationEntryPointFactory (API → JSON 401, SPA → /login redirect))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/PathAwareAccessDeniedHandler.java` (PathAwareAccessDeniedHandler (API → JSON 403, others → default))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/BrowserSessionIntegrationTest.java` (BrowserSessionIntegrationTest (bearer-still-works alongside browser session))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/BearerRequestMatcherTest.java` (BearerRequestMatcherTest (chain discriminator predicate))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/ChainOrderingTest.java` (ChainOrderingTest (API chain @Order(1) ahead of browser @Order(2)))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/DelegatingAuthenticationEntryPointFactoryTest.java` (DelegatingAuthenticationEntryPointFactoryTest (API → JSON 401, SPA → redirect))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/PathAwareAccessDeniedHandlerTest.java` (PathAwareAccessDeniedHandlerTest (API → JSON 403, others → default))
- IMPLEMENTS → CODE_FILE `frontend/src/pages/login.tsx` (Login page (browser-facing access gate, CSRF enforcement, credentials-only entry))
- TESTS → TEST `frontend/src/pages/login.test.tsx` (Login page tests (CSRF enforcement, error on failed auth, no credential echo))
- DOCUMENTS → GITHUB_ISSUE `243` (Unauthenticated REST API exposes full admin and write access)
- IMPLEMENTS → GITHUB_ISSUE `244` (#244 AGE graph queries are vulnerable to injection via interpolated Cypher/SQL strings)
- IMPLEMENTS → GITHUB_ISSUE `857` (#857 Web UI login for single-tenant installs (browser-session dimension of GC-P011))
- IMPLEMENTS → PULL_REQUEST `870` (PR #870 — browser session chain alongside bearer (refines GC-P011))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/ApiRequestPathsTest.java` (ApiRequestPathsTest — API and documentation path classification)
- TESTS → TEST `tools/tests/test_policy_deploy_compose_credential_passthrough.py` (run_deploy_compose_credential_passthrough policy tests (#828))
