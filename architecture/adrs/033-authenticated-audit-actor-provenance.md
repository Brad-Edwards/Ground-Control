# ADR-033: Authenticated Audit Actor Provenance

## Status

Accepted

## Date

2026-05-10

## Context

Issue #431 documents that audit actor identity must not come from a caller-controlled
`X-Actor` header. Ground Control is audit- and compliance-heavy: Envers revisions,
timeline exports, `createdBy` fields, pack-registry install records, and future
authorization decisions all depend on actor provenance being trustworthy.

ADR-026 already establishes the REST API access-control boundary:
`ApiSecurityConfig` owns the Spring Security filter chain, bearer-token credentials
are bound through `groundcontrol.security.*`, and `ActorFilter` runs after
authorization. This ADR narrows the audit-specific contract so future changes do not
reintroduce header-sourced identity or a second authentication model.

## Decision

### 1. The authenticated principal is the canonical audit actor

When `groundcontrol.security.enabled=true`, audit actor identity MUST resolve from the
Spring Security `SecurityContext` principal. `ActorFilter` remains the boundary that
copies that principal into `ActorHolder` and MDC for the lifetime of the request.
`GroundControlRevisionListener` persists only the value already in `ActorHolder`.

`ActorHolder` is propagation state, not an authentication mechanism. Domain services
may read it for audited fields such as `createdBy`, but controllers and services must
not authenticate callers by setting it directly.

### 2. `X-Actor` is development fallback only

`X-Actor` MUST NOT be trusted when security is enabled. It may remain only as a
security-disabled dev/test convenience so local tools can produce readable audit
records without minting tokens.

In production, `anonymous` is valid only for explicitly anonymous, non-mutating
surfaces such as health/info probes and static SPA delivery. A production mutation or
audit-relevant API request without an authenticated principal must be rejected by the
security chain instead of writing an `anonymous` Envers revision.

### 3. Trusted service identity uses the same credential path

Automation and service-to-service callers are represented as configured
`groundcontrol.security.credentials[]` principals with `USER` or `ADMIN` role. Their
principal names are the values written to audit records.

Do not add an actor override header, request-body actor field, or feature-local token
store for service callers. A future OIDC, mTLS, or signed-service-identity adapter may
replace `BearerTokenAuthFilter`, but it must still populate `SecurityContext` with the
canonical principal and authorities consumed by `ActorFilter`.

### 4. Cross-cutting guards stay shared

- `ApiSecurityConfig` is the primary authorization boundary: it performs authentication
  and the `ROLE_USER` / `ROLE_ADMIN` path matrix. The single deliberate exception is
  `PackRegistryAccessGuard.requireAdminActor()`, a defense-in-depth bridge invoked per
  pack-registry endpoint. It does *not* introduce a parallel auth model - when security is
  enabled it re-derives the admin principal from the same `SecurityContext` (never from
  headers or request bodies), re-asserts `ROLE_ADMIN`, and throws `AuthenticationException`
  if no authenticated admin principal is in scope, so a misconfiguration that left the
  pack-registry path out of the security matrix fails closed. When
  `groundcontrol.security.enabled=false` (dev/test profile) the guard, like `ActorFilter`,
  falls back to `ActorHolder` (`X-Actor` or `anonymous`) - the same explicit dev
  convenience described in §2, not a production path. New controllers must not add their
  own auth checks; they rely on `ApiSecurityConfig`, and where they need the principal as
  an audit field they read it from the `SecurityContext` (or, in the security-disabled dev
  profile, from `ActorHolder`).
- Credential shape and startup validation stay in `SecurityProperties`. New credential
  attributes must extend that configuration model instead of adding a duplicate schema.
- 401/403 responses stay on `ApiAuthenticationEntryPoint` and
  `ApiAccessDeniedHandler`, using the shared `ErrorResponse` envelope and stable
  messages that do not echo tokens or parser details.
- IP gating stays in `IpAllowlistFilter`, which uses `remoteAddr` and intentionally
  does not trust `X-Forwarded-For`.
- Audit persistence stays on Envers plus `GroundControlRevisionListener`; do not create
  a second revision table or feature-local audit writer.
- Logging stays on `RequestLoggingFilter`, `ActorFilter`, MDC, and Logback. Tokens and
  raw authorization headers must never be logged. The actor MDC key is `actor_id` -
  `ActorFilter` writes it and `logback-spring.xml`'s production JSON appender lists it
  under `<includeMdcKeyName>`; the two must stay in sync (`ActorFilterTest` pins the key
  `ActorFilter` writes), and the `_id` suffix matches the sibling correlation keys
  `request_id` / `tenant_id`.

### 5. Tests must prove provenance under security-enabled conditions

Security-disabled controller tests may continue to focus on controller behavior, but
they are not evidence for issue #431. Audit provenance tests for this issue must enable
`groundcontrol.security.enabled=true`, configure test credentials, send
`Authorization: Bearer <token>`, and assert that:

- Envers actor values come from the authenticated principal.
- A spoofed `X-Actor` header does not override that principal.
- Unauthenticated mutation requests receive the standard 401 envelope and do not create
  audit revisions.

Tests that keep `@AutoConfigureMockMvc(addFilters = false)` are acceptable only as
slice tests; they must not be cited as security or audit-provenance coverage.

## Consequences

### Positive

- The audit identity model is a thin projection of the authenticated principal, not a
  parallel header contract.
- Service automation, human operators, Envers revisions, `createdBy` fields, and audit
  timeline filters all use the same principal namespace.
- A future authentication backend can swap into the existing `SecurityContext` seam
  without changing Envers, domain services, or audit DTOs.

### Negative

- Security-enabled audit integration tests must mint configured tokens and can no
  longer rely on the simpler `X-Actor` test shortcut.
- Operators must manage stable principal names as part of credential rotation because
  those names become compliance-visible audit data.

### Risks

- If production scripts pass bearer tokens in command-line `curl -H` arguments, other
  local users can read them through process inspection. Production automation should
  use environment/secret stores or config-file descriptors, not argv, for token
  material.
- If the `actor_id` MDC key drifts between `ActorFilter` and `logback-spring.xml`,
  structured logs stop showing the actor even though Envers stays correct. The key is now
  aligned and `ActorFilterTest` asserts the value `ActorFilter` writes; any change must
  update both sides together.
- If future tests rely only on the `test` profile, they can accidentally re-normalize
  the security-disabled `X-Actor` fallback as production behavior.
