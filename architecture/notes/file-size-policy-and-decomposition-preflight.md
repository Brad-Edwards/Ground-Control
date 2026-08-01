# File Size Policy and Decomposition Preflight

Issue #1467 makes the existing 500-line coding standard enforceable and
decomposes the remaining oversized first-party source files. This note fixes
the repo-wide boundaries before implementation. It does not split source files
or add the policy check.

## Sequencing and Scope

- The issue inventory assumes the decomposition on #1355. That branch is not
  yet in `dev` as of this preflight. Reconcile #1467 against `dev` after #1355
  lands; do not split the old `lib.js` or policy monolith a second time.
- Treat the issue table as a bootstrap observation, not the scanner's
  authority. The current checkout already has oversized first-party files that
  are absent from the table, while `contracts/gen/typescript/api.ts` is
  generated and must not be hand-split.
- The acceptance criterion applies to tracked, first-party executable source
  and test code. Generated outputs, vendored dependencies, build output,
  binary/reference material, migrations, and prose are different artifact
  classes. Exclusions must be explicit and narrow rather than inferred from
  whether a current file would otherwise fail.
- The canonical hook path is `.claude/hooks/git-merge-guard.py`; the inventory
  spelling without the leading dot is not a second source root.

## Policy Contract

The new check belongs in the existing `bin/policy` and `tools/policy` path and
runs through `make policy`. It is a whole-tree invariant, not a
changed-files-only check.

- Discover tracked files from Git with fixed argv. Do not walk untracked local
  files, follow source symlinks, or interpolate paths into a shell command.
- Keep the recognized code suffixes and generated/vendor exclusions in one
  strict policy-owned configuration surface. Adding the next supported source
  language or generated tree is a configuration change plus policy tests, not
  a second scanner.
- Count physical text lines, including a final line without a trailing newline.
  This matches the counts used by the issue and is deliberately independent of
  formatter, comment, or statement density.
- Fail closed when configuration is missing or malformed, a candidate source
  cannot be read as text, a candidate escapes the repository, a source symlink
  is encountered, or discovery resolves no files. Reuse the scan-floor
  discipline introduced on #1355 so "looked at nothing" cannot report green.
- Report deterministic, sorted repo-relative paths with measured and allowed
  counts. Keep output bounded; source contents, environment values, and file
  contents do not belong in the violation.

The temporary grandfather surface must be monotonic:

- Each entry names one tracked repo-relative source file and its current line
  ceiling. Unknown keys, duplicate paths, absolute paths, `..` traversal,
  excluded/generated paths, and non-source suffixes are invalid.
- A grandfathered file may shrink but may not exceed its recorded ceiling.
- An entry becomes invalid as soon as the file is deleted, renamed, generated,
  or reaches 500 lines. This forces the list to shrink with each batch and
  prevents stale exemptions.
- A file over 500 lines that is not grandfathered is a failure, whether or not
  it changed in the current diff.
- The standard 500-line limit has one machine-readable owner. If the human
  coding-standard table repeats the number, a policy test must keep the two in
  agreement.

Do not put this inventory into `architecture/policies/adr-policy.json`. That
file owns changed-surface companion-document rules. File-size discovery and
temporary exemptions are a different policy concept.

## Decomposition Boundaries

Preserve the original import, executable entry point, public class, and public
export surface. A barrel or façade may become small, but it must not become a
second implementation owner.

- Derive module groups from declaration-level dependencies and strongly
  connected components, as on #1355. Section comments and equal-sized line
  chunks are not dependency information. The resulting production module graph
  must be acyclic.
- Keep compatibility barrels at existing JavaScript and Python import paths.
  New behavior goes in the concern-owning module rather than regrowing the
  barrel.
- Keep Java public service methods on the existing service surface where
  controllers and other domain consumers already depend on it. Extract
  cohesive collaborators behind that façade without creating new aggregates
  or bypassing repositories.
- A class-level or method-level `@Transactional` boundary, including
  `readOnly` and AGE's repeatable-read isolation, is behavior. Moving code to a
  non-proxied helper or relying on Spring self-invocation silently changes it.
- Keep repository queries project-scoped. A split is not permission to load an
  entity by global id and validate project ownership later.
- Keep command records and aggregate invariants authoritative. Do not copy
  validation into extracted helpers, controllers, or transport adapters.
- Split tests by behavior or fixture concern. Preserve `@WebMvcTest` controller
  slices, integration tags, Testcontainers boundaries, and existing assertions.
  Shared fixtures are appropriate only when genuinely reused; avoid stateful
  test base classes and numbered test fragments.
- Any policy or contract check that reads one original file must be made
  package-aware before that file becomes a barrel. Its extraction must assert a
  nonzero expected floor. A recursive scan without a floor merely moves the
  false-green failure.

## Existing Cross-Cutting Contracts to Reuse

### Backend

- `api/ -> domain/ <- infrastructure/` remains ArchUnit-enforced.
  Controllers continue to call services; services own aggregate orchestration;
  repositories own persistence queries.
- Preserve command DTOs and Bean Validation at the write boundary. Domain
  violations continue through `GroundControlException` subclasses,
  `GlobalExceptionHandler`, and `ErrorResponse`.
- Preserve `ActorHolder` and `ActorFilter` audit identity propagation, plus
  existing structured SLF4J event names and low-cardinality fields. Extracted
  collaborators must not lose the actor or emit request payloads.
- `AgeGraphService` remains the infrastructure adapter for `GraphClient` and
  `MixedGraphClient`. Preserve property-key allowlisting, graph-name and UID
  validation, parameter binding, projection limits, transaction
  synchronization, and third-party exception translation.
- Preserve existing repositories, Envers access, schema validators, and
  aggregate ownership. Decomposition does not justify a new repository,
  exception hierarchy, validation framework, or persistence model.

### MCP and Node

- Keep `mcp/ground-control/index.js` as the executable package entry point and
  preserve every tool name, description, Zod input shape, admin-registration
  condition, handler envelope, and startup behavior.
- Reuse `request`, `RequestError`, `addAuthorizationHeader`, `pick`,
  `reqArg`, `TO_CAMEL`, and the action-specific body allowlists. The
  OpenAPI-contract and tool-description tests remain the drift authority; do
  not create local schema mirrors in extracted registration modules.
- Keep privileged GitHub and Git effects in the MCP server and invoke them with
  argv arrays. `gc-integrate.js` splits must preserve repository identity
  checks, locks, branch/mode guards, CI and Sonar watchers, and exported schema
  constants.
- Node tests may split along existing `describe` concerns, but must keep
  importing the public barrel when the behavior under test is public. Helper
  modules must not match the `*.test.js` runner glob unless they are tests.

### Frontend

- Pages continue to consume `apiFetch`, TanStack Query hooks and query keys,
  project context, and the generated API types re-exported by
  `frontend/src/types/api.ts`.
- Extract view components and local pure helpers along UI concerns. Do not add
  direct `fetch`, duplicate request DTOs, hand-mirrored enums, or a second
  client-side cache merely to reduce a page's line count.
- Preserve route exports, loading/error/empty states, mutation invalidation,
  accessibility semantics, and live-stream fallback behavior.

### Python and Hook Surfaces

- `zotero_ingest.py` keeps environment credentials inside the existing Zotero
  client boundary and reuses the citation HTTP client, identifier parser, OA
  policy cache, timeouts, response caps, and stable result/error dictionaries.
  Credentials must not enter argv, returned errors, or logs.
- `.claude/hooks/git-merge-guard.py` remains the registered executable
  entry point. Any extracted parser/evaluator code must preserve fail-closed
  stdin handling, fixed-argv Git probes, timeouts, protected-branch constants,
  shell-expansion rejection, exit codes, and non-sensitive denial messages.

## Cross-Cutting Layers the Change Passes

- **Security and authorization:** no route or authority changes are intended.
  Backend traffic still passes the bearer/session security chains and
  `ApiPathMatrix`; MCP writes still pass Zod/action allowlists and backend
  authorization; the merge guard still denies ambiguous commands.
- **Secret handling:** no new environment keys or config bindings are needed.
  Existing Ground Control, GitHub, Sonar, and Zotero tokens stay in their
  current environment or HTTP-header boundaries and out of process argv and
  diagnostics.
- **Configuration shapes:** no new Spring `@ConfigurationProperties` class,
  MCP tool argument, or frontend schema is warranted. The only new shape is
  the strict, local file-size policy configuration and temporary grandfather
  data.
- **OS and filesystem exposure:** the policy reads tracked repo-contained text
  only. Existing MCP and hook subprocesses retain fixed argv, bounded timeouts,
  containment checks, and no `shell=True`.
- **Error envelopes:** backend behavior remains
  `GroundControlException -> GlobalExceptionHandler -> ErrorResponse`; MCP
  behavior retains `ok`/`err` and `RequestError`; citation and hook result/exit
  contracts remain stable.
- **Persistence and transactions:** no migration or schema change is intended.
  Repository project scope, aggregate ownership, transaction propagation,
  Envers behavior, and AGE isolation are preserved.
- **Logging and observability:** preserve existing event names, MDC actor and
  request context, and bounded error output. A behavior-neutral split adds no
  telemetry schema or new log payload.

## Verification Guardrails

Each reviewable batch must prove both the local split and the surrounding
contract:

- compare pre-split and post-split public JavaScript/Python export names and
  preserve Java public method signatures used by controllers and consumers;
- run the narrow suite for the split concern, then the language/package suite;
- run ArchUnit and `@WebMvcTest` slices for backend moves, frontend lint/test/
  build for page moves, Node tests plus MCP/OpenAPI contract tests for MCP
  moves, and hook tests for merge-guard moves;
- search for path-sensitive source readers and update them to the package-aware
  source set with a scan floor;
- verify every production file and every declaration is within its applicable
  size bound; a file split does not excuse a single function over the existing
  100-line function limit; and
- finish with `make policy`. When the implementation changes
  `tools/policy/**`, record the policy addition in ADR-054 and keep
  `docs/DOC_STYLE.md` synchronized as required by the existing ADR guard.

## Gotchas and Anti-Patterns

- Do not make the policy diff-scoped, seed it only from the pasted 60 paths, or
  accept a zero-file scan.
- Do not exempt generated files by broad directory names such as `tools/**`,
  `mcp/**`, or `backend/**`.
- Do not let a grandfathered file grow, keep a stale entry, or make exemptions
  wildcard-based.
- Do not split by comments, line ranges, alphabetical order, or numbered
  "part" files when a domain or dependency seam exists.
- Do not widen module exports to make sibling imports convenient. Export only
  the previous public surface or the narrow internal seam the graph requires.
- Do not create duplicate Zod/DTO schemas, validation branches, exception
  hierarchies, HTTP clients, logging wrappers, repository methods, or query
  caches.
- Do not move domain orchestration into controllers, infrastructure adapters,
  frontend pages, or MCP handlers.
- Do not weaken or rewrite existing assertions solely to accommodate a split.
  Assertion changes require a behavior-specific justification.
- Do not add a generic decomposition framework or shared helper for fewer than
  three real consumers.

## Non-Goals

- No REST, MCP, frontend route, persistence, migration, authorization,
  configuration, telemetry, or public error-shape change.
- No redesign of retained control, evidence, finding, asset, risk, threat,
  research, test, or requirement aggregates.
- No resurrection of the ADR-089 retired composed GRC or next-issue
  recommendation surfaces.
- No hand-editing or arbitrary splitting of generated contract artifacts.
- No function-length, complexity, or coupling policy implementation beyond
  preserving the already documented limits while decomposing declarations.
- No single repo-wide refactor sweep. Batches remain independently reviewable
  and behavior-neutral.
