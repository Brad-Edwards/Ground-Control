# ADR-026: REST API Access Control (GC-P011)

## Status

Accepted

## Date

2026-05-03

## Context

Issue [#243](https://github.com/KeplerOps/Ground-Control/issues/243) (P0,
security) documented that the Ground Control backend exposed every REST
endpoint without authentication or authorization. Any caller who could
reach `:8000` could create, modify, archive, import, sync, and materialize
arbitrary data - including triggering GitHub syncs and rebuilding graph
state. Before #243 the only access control was an in-controller bearer
token on `/api/v1/pack-registry/**` (`PackRegistryAccessGuard`). All other
controllers, including `RequirementController`, `ImportController`,
`SyncController`, `GraphController`, `AnalysisController`, were reachable
unauthenticated.

GC-P011 ("Access Control," NON_FUNCTIONAL, MUST, wave 1) states:

> The system shall support configurable access restrictions such that only
> authorized users or network locations can reach the application. Access
> policy shall be configurable without code changes.

The non-localhost deployment posture (ADR-018, AWS EC2) requires the
restriction to be enforced at the application layer; relying purely on
network isolation, a reverse proxy, or deployment documentation is not
sufficient.

## Decision

Add an application-layer access control stack centred on Spring Security.
Configuration is driven from `groundcontrol.security.*` (a new
`@ConfigurationProperties` bean) so policy is editable in `application.yml`
or via `GC_SECURITY_*` environment variables - zero code change is needed
to rotate credentials, adjust roles, or change the IP allowlist.

The chain is wired by `ApiSecurityConfig` in this order:

1. **`IpAllowlistFilter`** - rejects requests whose source `remoteAddr` is
   outside any configured CIDR. Empty allowlist is opt-out (token auth
   alone). Spring's `IpAddressMatcher` evaluates the CIDRs. The filter
   intentionally does NOT honour `X-Forwarded-For`; in deployments behind
   a reverse proxy, the proxy must terminate the IP gate or set the source
   IP via `RemoteIpFilter` configured separately and explicitly.
2. **`BearerTokenAuthFilter`** - parses `Authorization: Bearer <token>`,
   compares against the configured credential list with `MessageDigest.isEqual`
   for constant-time compare (mirrors the pre-existing pack-registry
   pattern), and on match sets a `UsernamePasswordAuthenticationToken` with
   `ROLE_USER` or `ROLE_ADMIN` in the `SecurityContext`. Tokens are never
   logged. Wrong scheme, missing header, or bad token leaves the context
   empty (anonymous).
3. **Spring Security authorization** - path matrix:

   | Path | Authority |
   |------|-----------|
   | `/actuator/health`, `/actuator/info`, `/actuator/health/**`, `/error` | anonymous |
   | `/api/openapi.json`, `/api/docs/**`, `/v3/api-docs/**`, `/swagger-ui/**` | gated by `groundcontrol.security.openapi-public` (default `false` → authenticated) |
   | `/api/v1/admin/**` | `ROLE_ADMIN` |
   | `/api/v1/embeddings/**` | `ROLE_ADMIN` |
   | `/api/v1/analysis/sweep/**` | `ROLE_ADMIN` |
   | `/api/v1/pack-registry/**` | `ROLE_ADMIN` |
   | Other `/api/v1/**` | authenticated (USER or ADMIN) |
   | Anything else | denyAll |

4. **`ApiAuthenticationEntryPoint` / `ApiAccessDeniedHandler`** - emit 401
   and 403 respectively using the existing `ErrorResponse` JSON envelope so
   wire format stays consistent with `GlobalExceptionHandler`. Underlying
   exception messages are NOT echoed in response bodies. The 401 response
   carries `WWW-Authenticate: Bearer`.

5. **`ActorFilter`** then runs AFTER the security chain and prefers the
   authenticated principal name over the legacy `X-Actor` header. The
   header remains a fallback only when security is disabled (dev / test
   profiles) so audit identity stays useful in those environments.

CSRF is disabled (stateless REST API; bearer auth, no cookies). Sessions
are stateless. Anonymous, form-login, http-basic, and logout filters are
explicitly disabled.

The pre-existing pack-registry `PackRegistryAccessGuard` is consolidated
into this model: its only remaining responsibility is reading the
authenticated principal name from the `SecurityContext` for audit fields.
The legacy per-feature `groundcontrol.pack-registry.security.adminCredentials`
list and its `authenticationHeader` / `tokenScheme` knobs are removed in
favour of the unified `groundcontrol.security.credentials` store with
`ROLE_ADMIN`. Pack signing remains separate
(`groundcontrol.pack-registry.security.trustedSigners`).

### Profile defaults

| Profile | `groundcontrol.security.enabled` | Why |
|---------|---------------------------------|-----|
| default (production) | `true` | denyAll without configured credentials/allowlist |
| `dev` | `false` | zero-friction local development |
| `test` | `false` | existing 200+ controller tests do not need to mint tokens |

Even when `enabled=false` the framework filter chain still owns request
mapping; it just degrades to `permitAll()`. The chain is never absent -
disabling Spring Security entirely would silently regress to the
pre-#243 posture if the flag flipped.

### Why bearer + CIDR (and not OAuth2/OIDC, mTLS, or basic auth)

- The repo already uses `Authorization: Bearer <token>` in the pack-registry
  pre-existing guard; consistent UX and one fewer auth scheme to operate.
- "Users **or** network locations" in GC-P011 explicitly authorizes IP-based
  gating. CIDR allowlisting fits single-tenant perimeter deployments.
- OAuth2/OIDC would require an identity provider that does not exist for
  the current single-operator deployment posture. It remains a valid
  upgrade path; a future ADR can flip the auth backend without changing
  the path matrix or the configuration namespace.
- mTLS would push the auth boundary to the load balancer / reverse proxy
  and create deployment friction disproportionate to the threat model.
- HTTP Basic stores plaintext credentials in client config and provides no
  meaningful improvement over bearer tokens.

## Consequences

### Positive

- Every `/api/v1/**` endpoint is now authenticated by default.
- Admin endpoints require an explicit `ROLE_ADMIN` principal.
- Operators can rotate credentials and adjust the IP allowlist via env
  vars or yaml - no code change, no rebuild, just a restart.
- Pack registry admin auth is now part of the same surface as the rest of
  the API; no second credential store to manage.
- `ActorFilter` audit identity tracks the authenticated principal, so
  audit logs are no longer trivially spoofable via the `X-Actor` header
  in production.

### Negative / require operator attention

- **Breaking change for any caller of `/api/v1/**`**: production
  deployments MUST configure `groundcontrol.security.credentials` (and/or
  `ip-allowlist`) before upgrading. With `enabled=true` and an empty
  credential list, every authenticated route returns 401.
- **Pack registry admin credentials migrate**: deployments that were using
  `ground-control.pack-registry.security.admin-credentials` must move
  those entries into `groundcontrol.security.credentials` with
  `role: ADMIN`. The old keys are removed; misconfiguration surfaces at
  startup.
- The `X-Actor` header is no longer a self-service identity claim; it is
  ignored when an authenticated principal is in scope.

## Alternatives considered

- **OAuth2/OIDC via Spring Authorization Server or an external IdP** -
  superior identity model but requires deploying an IdP. Deferred until
  the deployment topology grows beyond a single operator.
- **mTLS with client certs at the load balancer** - strong but creates
  certificate-management overhead disproportionate to the current threat
  model.
- **Per-controller `@PreAuthorize` annotations only** - rejected; spreads
  authorization logic across 35+ controllers (codex preflight flagged
  this as the primary anti-pattern to avoid). The path-matrix approach
  keeps the policy in one place.
- **Network isolation only (no application-layer auth)** - explicitly
  rejected by GC-P011 ("only authorized **users** or network locations")
  and by codex preflight ("do not rely on deployment documentation,
  reverse-proxy examples, or agent/user hooks as the only enforcement
  layer for non-localhost deployments").

## References

- Issue [#243](https://github.com/KeplerOps/Ground-Control/issues/243)
- GC-P011 (Access Control)
- ADR-018 (AWS EC2 Deployment)
- `backend/src/main/java/com/keplerops/groundcontrol/shared/security/`
- `backend/src/test/java/com/keplerops/groundcontrol/integration/ApiSecurityIntegrationTest.java`
