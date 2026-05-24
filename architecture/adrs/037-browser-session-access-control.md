# ADR-037: Browser Session Access Control

## Status

Accepted

## Update — Issue #846 (2026-05-23)

The React SPA login screen has shipped. `BrowserSecurityConfig` now calls
`.loginPage("/login")`, which disables Spring's `DefaultLoginPageGeneratingFilter`
so `GET /login` is forwarded to `SpaController → index.html` (the SPA shell).
`POST /login` form-urlencoded continues to be processed by
`UsernamePasswordAuthenticationFilter` at the same path. The SPA login form reads
the `XSRF-TOKEN` cookie (written by `CookieCsrfTokenRepository` on the GET) and
echoes it as `X-XSRF-TOKEN`, satisfying the CSRF gate. No Decision contracts in
this ADR were changed.

## Date

2026-05-11

## Context

ADR-026 established the production access-control boundary for machine and
operator API callers: `IpAllowlistFilter`, `BearerTokenAuthFilter`, the
central `ROLE_USER` / `ROLE_ADMIN` path matrix in `ApiSecurityConfig`, and
stable `ErrorResponse` 401/403 envelopes. ADR-033 then made the authenticated
Spring Security principal the canonical audit actor consumed by `ActorFilter`,
`ActorHolder`, MDC, and Envers.

That model intentionally disabled form login, logout, CSRF, anonymous auth, and
sessions because `/api/v1/**` was a stateless REST surface authenticated by
`Authorization: Bearer`. This is sufficient for agents and automation, but not
for a human opening the React SPA in a browser: normal navigation to `/` does
not carry an `Authorization` header, and the current SPA API client does not
have a session-backed authentication path.

Issue #857 adds single-tenant human login while preserving the existing bearer
API contract. The main architectural risk is not "how to add a login form"; it
is avoiding two independent auth models, two role vocabularies, two error
envelopes, or a filter-chain split where browser navigation succeeds but the
SPA's `/api/v1/**` XHR calls still 401 because they landed in the bearer-only
chain.

## Decision

### 1. Two authentication modes, one authorization and actor model

Ground Control will support both:

- **Machine/API mode:** `Authorization: Bearer <token>` backed by the existing
  `groundcontrol.security.credentials[]` list from ADR-026.
- **Browser/session mode:** Spring Security form login backed by a JDBC user
  store and BCrypt password hashes.

Both modes must end by populating the same `SecurityContext` principal and
`ROLE_USER` / `ROLE_ADMIN` authorities. `ApiSecurityConfig` remains the
canonical authorization owner for `/api/v1/**`; controllers must not add
feature-local auth checks, role enums, or request-body actor fields. `ActorFilter`
continues to project the authenticated principal into `ActorHolder` and MDC.

### 2. Filter-chain matching must be explicit and non-overlapping

Spring Security picks the first matching `SecurityFilterChain`; it does not fall
through to a later chain after an authentication failure. Therefore the browser
session chain must not be described as "SPA routes only" unless another chain
also authenticates the SPA's `/api/v1/**` XHR calls with the session cookie.

The implementation must make the request matching explicit enough that:

- Bearer requests to `/api/v1/**` keep the ADR-026 behavior: stateless,
  CSRF-exempt, no redirect to `/login`, standard JSON 401/403 envelopes, and the
  existing path matrix.
- Browser requests can log in, receive a hardened session cookie, call
  `/api/v1/**` with that session, and hit the same path matrix.
- Anonymous browser navigation can load only the login page and required static
  assets; authenticated SPA routes require the session.
- `/actuator/health`, `/actuator/info`, `/error`, and OpenAPI public/private
  behavior stay aligned with ADR-026.

If a request can plausibly be either browser or API traffic, the discriminator
must be a stable request property such as the bearer `Authorization` scheme,
path, and response content negotiation. Do not rely on controller-side retries
or frontend token storage to paper over chain ordering.

### 3. CSRF is required for cookie-authenticated browser mutations

ADR-026's CSRF-disabled decision applies only to stateless bearer requests.
Session-authenticated browser requests are cookie-bearing and must keep CSRF
protection on for state-changing operations.

The browser session surface must use Spring Security's CSRF machinery and a
documented token-delivery seam that the SPA can consume later. A CSRF token may
be delivered through Spring's generated login page for v1 and through a
dedicated token endpoint or cookie repository when the React login flow is
added. Disabling CSRF globally, or exempting all `/api/v1/**` merely because the
endpoint is JSON, violates this ADR.

### 4. JDBC users are a security principal store, not a new business user model

The JDBC store uses Spring Security's established user/authority concepts:
username, BCrypt password hash, enabled state, and authorities named
`ROLE_USER` / `ROLE_ADMIN`. It must not introduce a second Ground Control role
vocabulary, a project membership model, groups, tenants, federation metadata, or
profile fields.

The persistence source of truth is Flyway. If the implementation uses Spring
Security's default `users` / `authorities` schema, admin role changes should go
through `JdbcUserDetailsManager` or a thin service around it, not raw SQL from
controllers. If a future change needs richer user lifecycle data, that is the
extension point for a dedicated domain model and a new ADR.

### 5. First-admin seeding must not expose secrets through process argv

First-admin creation is required for a locked-down install, but passing a
password in command-line arguments is not acceptable for production because it
can be exposed through shell history, process listings, `/proc`, CI logs, and
agent transcripts.

The production-safe seam is an interactive prompt, standard input, a mode-600
file descriptor, or an operator-managed secret store. A `--password=...`
shortcut may exist only as a clearly documented local-dev convenience and must
not appear in production deployment guidance. Passwords and password hashes must
never be logged.

### 6. Error, logging, config, and deployment surfaces stay canonical

- 401/403/CSRF failures for `/api/v1/**` use the shared `ErrorResponse`
  contract through `ApiAuthenticationEntryPoint`, `ApiAccessDeniedHandler`, or a
  compatible shared handler. Parser or credential details are not echoed.
- Logging stays on `RequestLoggingFilter`, `ActorFilter`, MDC, and ordinary
  service logs. Log usernames and role-change facts only; never log passwords,
  password hashes, session ids, CSRF tokens, or bearer tokens.
- Configuration stays in `@ConfigurationProperties` / Spring Boot native
  properties. Existing `groundcontrol.security.*` remains the API credential and
  IP allowlist namespace; browser-session tunables belong under a narrow
  browser/session subtree or native `server.servlet.session.cookie.*` keys.
- Production env passthrough must remain compatible with the existing
  `deploy/docker/docker-compose.prod.yml`, `.env.example`, and
  `tools/policy/checks.py` guardrails. Optional indexed values must not be
  injected as blank env vars.

## Consequences

### Positive

- Human browser login becomes an addition to ADR-026, not a replacement for
  machine bearer access.
- Audit actor provenance remains a single projection of `SecurityContext`.
- The SPA can move from Spring's generated login page to a React login screen
  without changing the backend's principal, role, or CSRF contracts.

### Negative

- The security configuration becomes more complex: chain ordering, request
  matching, session policy, CSRF, and entry points must be tested together.
- Browser API calls need CSRF-token handling; this is intentional friction for
  cookie-authenticated mutations.
- First-admin bootstrap needs a production-safe secret input path, not only a
  convenient CLI flag.

### Risks and Guardrails

- A browser chain scoped only to `/`, `/login`, `/logout`, and static assets is
  incomplete unless `/api/v1/**` also accepts the browser session.
- A catch-all browser chain placed before the bearer chain can accidentally turn
  API 401s into login redirects and can break agents.
- A catch-all bearer chain placed before the browser chain can swallow `/login`
  and keep humans locked out.
- `X-Actor` remains a dev/test fallback only. Do not resurrect it as a login,
  impersonation, or user-selection mechanism.
- Do not put bearer tokens into `localStorage` or `sessionStorage` to make the
  SPA work; browser authentication is session-cookie based.
- Do not create duplicate admin endpoints, duplicate error envelopes, duplicate
  validation layers, or a second role enum.

## Non-goals

- Federation, SSO, SAML, OIDC, MFA, self-service signup, email verification,
  and email password reset.
- Multi-tenant or project-scoped authorization.
- Group membership or custom roles beyond `ROLE_USER` and `ROLE_ADMIN`.
- Replacing ADR-026 bearer credentials for agents and automation.
- Replacing ADR-033 audit actor provenance.

## References

- Issue #857
- GC-P011 (Access Control)
- GC-P017 (Authentication, Federation, and Machine Access)
- ADR-026: REST API Access Control
- ADR-033: Authenticated Audit Actor Provenance
- `backend/src/main/java/com/keplerops/groundcontrol/shared/security/`
- `backend/src/main/java/com/keplerops/groundcontrol/shared/web/ActorFilter.java`
