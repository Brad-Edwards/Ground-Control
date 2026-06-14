# MCP Tool Catalog Surface Preflight

Issue #628 / A12-03 flags that the repo-local Ground Control MCP server exposes
the full REST API as 203 tools. That makes every connected LLM load a large
catalog even when the session only needs workflow-level actions. This note
records architecture guardrails for reducing that surface. It is not an
implementation plan.

## Architecture Boundaries

- The Spring REST API remains the semantic authority for Ground Control
  behavior. MCP tools are adapters over that API plus a small workflow
  automation surface; they must not become a second controller/service layer.
- Preserve the existing `mcp/ground-control/lib.js` HTTP client boundary:
  `buildUrl`, `request`, `toCamelCase`, `RequestError`, `parseErrorBody`, and
  bearer-token selection are the canonical place for URL construction, field
  conversion, auth headers, and backend error-envelope propagation.
- Keep workflow tools first-class. `gc_get_repo_ground_control_context`,
  `gc_codex_architecture_preflight`, `gc_post_implementation_plan`,
  `gc_codex_review`, and `gc_codex_verify_finding` encode repo workflow gates
  from ADR-027, ADR-029, and ADR-031; they must not be hidden behind a generic
  API proxy.
- If a generic escape hatch is introduced, it is only an adapter to existing
  REST endpoints. It must be path/method allowlisted, route through the same
  HTTP client, and return the same MCP error shape as specific tools. It must
  not accept arbitrary hosts, absolute URLs, shell commands, files, headers, or
  caller-supplied bearer tokens.
- Prefer a curated default tool catalog plus an explicit expanded catalog
  parameter over many near-identical MCP server entrypoints. A split server can
  be a packaging option later, but the selection seam should be data-driven so
  adding a future "risk," "documents," or "admin" catalog is configuration, not
  copy-paste registration code.

## Cross-Cutting Concerns to Reuse

- **Auth:** ADR-026's backend path matrix remains the enforcement layer.
  The MCP adapter must continue loading `GC_BASE_URL`,
  `GROUND_CONTROL_API_TOKEN`, and
  `GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN` from the existing `.env` /
  process-env path and must continue using `addAuthorizationHeader`'s
  admin-path routing.
- **Secret handling:** tokens stay in environment or consumer-repo `.env`, not
  `.mcp.json`, tool arguments, logs, argv, or returned error text.
- **Validation:** MCP Zod schemas validate tool arguments only. Backend request
  DTOs, Bean Validation, Jackson enum binding, and domain services remain the
  authoritative validators for API semantics.
- **Transport metadata:** SDK-injected control fields are not user-facing tool
  arguments. Normalize only known runtime keys at the MCP adapter boundary, then
  keep the public argument allowlist strict so `headers`, `method`, `body`, and
  caller-supplied tokens still fail loudly.
- **Enum contracts:** API enum mirrors in `mcp/ground-control/lib.js` remain
  governed by ADR-034 and `make policy`; do not introduce another enum registry
  for a curated catalog.
- **Error handling:** non-2xx REST responses continue through `RequestError`,
  `parseErrorBody`, and `index.js`'s `err()` renderer. Do not invent a second
  MCP exception hierarchy or raw fetch-error format.
- **Workflow conventions:** phase markers, reviewer caps, override reasons, and
  GitHub side effects remain owned by the existing workflow helpers in
  `lib.js`; catalog reduction must not weaken those gates.
- **Policy and docs:** `make policy` remains the completion gate for MCP,
  policy, workflow, and ADR surfaces. If repo policy or ADR workflow surfaces
  change, run the live sync/check path when a Ground Control instance is
  reachable.

## Security Layers In Scope

- **MCP process config:** `loadDotenvFromCwd()` reads consumer-repo `.env`
  before requests. Any new catalog-selection flag must be non-secret, bounded to
  documented enum-like values, and safe to place in `.mcp.json`.
- **URL construction:** `buildUrl(path, params)` is the only allowed URL join.
  A generic API tool must accept repo-defined relative `/api/v1/...` paths only
  and reject absolute URLs, protocol-relative URLs, non-API paths, traversal-like
  encodings, and caller-supplied query strings when a structured `params` field
  can represent them.
- **Auth forwarding:** `addAuthorizationHeader(path, headers)` decides whether
  to use the generic or admin token. New code must not let a caller override
  `Authorization`, `X-Actor`, `Host`, or other security-sensitive headers.
- **Backend auth and audit:** requests still pass through `IpAllowlistFilter`,
  `BearerTokenAuthFilter`, `AuthorizationFilter`, `ActorFilter`, controllers,
  services, repositories, and Envers where applicable. MCP catalog selection is
  usability only, not authorization.
- **Error envelopes:** backend `ErrorResponse` detail may be surfaced, but
  transport exceptions, stack traces, token values, and raw response headers
  must not be returned to the LLM.
- **OS/process exposure:** no design should require users to pass bearer tokens
  on the command line or embed them in generated server args. Catalog selection
  may be an env/config value because it is not secret.
- **MCP runtime plumbing:** known non-user fields injected by the SDK or
  transport, such as an AbortController `signal`, must be consumed or ignored
  only at the adapter boundary. Do not forward them to URL construction,
  request params, headers, backend DTOs, logs, or error details.

## Extensibility Guardrail

The obvious next change is another curated catalog for a subdomain. The seam
belongs in a single catalog inventory that maps tool names to catalog tags
(`workflow`, `requirements`, `documents`, `risk`, `admin`, etc.) and a startup
selection parameter with a conservative default. Specific `server.tool(...)`
registrations should be generated or registered through one helper that applies
the inventory filter; adding a future catalog should not require cloning the
MCP server or reimplementing auth, error handling, or Zod schema definitions.

## Gotchas and Anti-Patterns

- Do not solve catalog size by deleting REST endpoints, changing backend
  controllers, or removing MCP coverage without an explicit compatibility
  decision. This issue is about the LLM-visible catalog, not API capability.
- Do not create a generic `gc_call_api` that bypasses Zod shape checks for
  high-risk workflow or admin operations unless the path/method allowlist and
  backend auth/error handling are still enforced.
- Do not duplicate request schemas in a new catalog layer. Reuse existing Zod
  schemas or centralize registration metadata so descriptions and validators do
  not drift between curated and expanded modes.
- Do not fix SDK-injected metadata by globally accepting arbitrary unknown
  arguments. A narrow allowlist for runtime metadata is acceptable; a generic
  passthrough that lets user-supplied `method`, `headers`, or `body` reach the
  handler is not.
- Do not split servers by copying `index.js` into multiple entrypoints with
  different hand-pruned tool lists. That preserves the current maintenance
  problem under more files.
- Do not conflate catalog visibility with permissions. Hiding admin tools from
  the default catalog reduces LLM noise; ADR-026 still decides whether the call
  is authorized.
- Do not rely on MCP dynamic tool-list-change notifications as the first
  production mechanism unless client support is verified and there is a static
  fallback. Claude Code compatibility is part of this issue's contract.

## Non-Goals

- No new backend API behavior, persistence model, domain service, repository,
  exception hierarchy, or audit model solely to reduce MCP catalog size.
- No change to ADR-026 authentication/authorization semantics.
- No new secret-delivery mechanism and no token material in MCP config, tool
  inputs, logs, process argv, or error responses.
- No migration from the repo-local Node MCP adapter to another runtime.
- No replacement of the Ground Control workflow tools with raw REST calls.
