# Sonar Watcher Token Provisioning Preflight

Issue: #946
Requirement: none

Scope-monitor follow-up: [issue #1559 preflight](sonar-scope-monitor-preflight.md)
separates verified path exclusion from missing credentials and reserves
readiness acceptance for #1533. Its applicability check precedes requiring a
token for an unnecessary request; the launch-directory credential boundary
below remains unchanged.

This note fixes the architecture boundary for making the Step 11 SonarCloud
observation available on every supported MCP host. It is design guidance, not
an implementation plan.

## Decisions

### Reuse the MCP launch-workspace `.env` contract

The declared local source for `SONAR_TOKEN` is the consuming repository's
gitignored `.env`, read by the Ground Control MCP process from its launch
workspace. `index.js::loadDotenvFromCwd` and `.env.example` already establish
that contract, including the compatibility rule that a non-empty process value
wins. The implementation must make this existing source effective and tested
for the Sonar watcher; it must not add a token value or interpolation syntax to
tracked `.ground-control.yaml`, `.mcp.json`, Codex `config.toml`, a skill, or a
tool argument.

The launch workspace is the correct scope. Ground Control captures that same
workspace for `/implement`, and one MCP process must not discover credentials
by following an arbitrary caller-supplied `repo_path`. A target repository's
tracked `sonarcloud` block selects the non-secret project and organization; it
does not select, contain, or reference credential material.

Keep one credential-loading contract. Do not add a Sonar-only dotenv parser,
credential store, shell wrapper, or subprocess. If the startup loader needs to
be made importable or testable, preserve its current precedence and parsing
semantics rather than creating a second source resolver. Provisioning or
rotating `.env` requires restarting the MCP server; operator documentation and
the missing-token recovery action must say so explicitly.

### Keep operation availability separate from a Sonar verdict

`runWatchSonarAnalysis` owns observation and returns `ok=false` when it could
not obtain a verdict. `runMonitor` must branch on that operation status before
evaluating `quality_gate`, issue count, or hotspot count. In particular,
`sonar_watch_token_missing` is a provisioning fault and an infrastructure
blocker requiring the operator to provision `SONAR_TOKEN` in the launch-root
`.env`, restart the MCP server, and retry the monitor. It is not a finding, a
failed quality gate, or a Sonar fix cycle.

Only an observed Sonar response may produce `pass` or `fail`: `quality_gate OK`
plus zero issues and zero hotspots passes; an observed non-OK gate or non-zero
count fails and enters the existing finding repair loop. A configured repo can
skip Sonar only when its parsed `.ground-control.yaml` has no `sonarcloud`
block. A green GitHub check never substitutes for the server-side observation.

The mechanical envelope must preserve the stable watcher error and return an
operator-directed recovery action for the missing-token case. Continue using
`failure()` for bounded, sensitive-content-scrubbed messages and continue
recording the station attempt as `not_evaluable`. Do not introduce another
exception hierarchy, generic blocker framework, finding disposition, or
execution-obligation type for this deterministic provisioning fault.

## Canonical Incumbents And Cross-Cutting Boundaries

- `index.js::loadDotenvFromCwd`, `.env.example`, `.gitignore`, and the optional-
  environment section of `mcp/ground-control/README.md` are the existing host
  secret distribution contract. **Superseded by issue #1562:** shell inheritance
  is no longer a compatibility override. `<launch directory>/.env` is the only
  source, the loader is `mcp/ground-control/lib/server-env.js`, and the per-host
  file this note's sibling decision introduced no longer exists. See
  `launch-directory-env-authority-preflight.md`. The Sonar decisions in this note
  are unaffected.
- `.ground-control.yaml::sonarcloud` and
  `normalizeSonarcloudConfig` remain the non-secret repository configuration
  boundary. Unknown keys fail closed; do not add `token`, `token_file`, or a
  secret reference to this tracked schema.
- The `gc_watch_sonar_analysis` Zod registration remains a thin tool adapter.
  Credentials are server-owned and therefore absent from its input schema.
  `validateWatchSonarAnalysisInput`, `ensureGitRepo`, and the existing
  SonarCloud config reader remain the semantic and repository checks.
- `runWatchSonarAnalysis`, `_sonarFetchWithRetry`, and `_sonarAuthHeader` remain
  the single Sonar client path. The token may enter only the in-process HTTP
  Authorization header, never a URL, query string, process argv, export,
  telemetry event, log, or returned envelope.
- `_writeSonarExport` keeps its containment check, retention cap, and
  gitignored `.gc/sonar` persistence. Exports contain Sonar findings and
  hotspots only, never headers or credentials.
- `runMonitor`, `sonarStationResult`, and `failure()` remain the workflow and
  error-envelope boundary. `ok`, `stationResult`, and the Sonar verdict are
  separate axes; ADR-090's `not_evaluable` is the incumbent classification for
  an unavailable observation.
- `skills/implement/steps/step-11-sonarcloud.md` is the canonical driver
  guidance. The `/quickfix` Step Q11 reference must remain semantically aligned
  and must not retain or reintroduce an agent-side `curl`/REST fallback.
- ADR-036 owns the server-side watcher and header-only secret rule. Its
  operational wording and the MCP/operator documentation must agree with the
  launch-root `.env` provisioning and restart requirement.

There is no backend controller, DTO, service, repository, database, or GitHub
issue-thread persistence change in this issue. The MCP server remains the only
running service; no privileged `gh`, `git`, or `curl` path moves into an agent
sandbox.

## Security And Validation Guardrails

- The secret file remains untracked and operator-owned. Documentation must
  require restrictive permissions (`chmod 600`) and must never show a live
  token. Existing repository secret scanners remain the tracked-file backstop,
  not permission to commit a placeholder that resembles a credential.
- Do not infer a credential path from `repo_path`, accept an absolute secret
  path from the model, or search parent/home directories. This prevents a
  watcher call that appears read only from becoming an arbitrary host-file
  read.
- Preserve environment-over-file precedence so an explicitly injected host
  value is not overwritten. Treat an absent or empty resolved value identically
  and report only the variable name and recovery action.
- Fetch failures may contain HTTP status and bounded context, but no request
  headers, token value, raw response body, stack trace, or full environment may
  cross the watcher or mechanical error envelopes.
- Tool-input Zod validation and the pure semantic validator remain distinct:
  the former checks transport shape; the latter keeps direct library callers
  safe. Do not duplicate either schema in the credential loader.

## Extensibility Seam

The seam is the existing launch-workspace dotenv loader plus an in-process
lookup by a declared variable name. It should remain usable by the MCP server's
other optional credentials without making Sonar select arbitrary files or
without turning `.ground-control.yaml` into a secret manifest. A future host
runtime can supply the same variables through its own secret injection, while
the repository-local `.env` remains the runtime-independent local source.

Do not introduce a generalized credential-provider abstraction for this one
consumer. If a later issue adds a genuinely different provider (for example an
OS keyring or mounted secret directory), it can extend the startup boundary
once multiple call sites justify that abstraction.

## Required Regression Boundaries

- Exercise the actual MCP startup boundary with ambient `SONAR_TOKEN` absent
  and a launch-root `.env` value present; prove the watcher authenticates
  without exposing the value. Also pin process-value precedence and the
  absent/empty-token envelope.
- Cover `runMonitor` separately for missing-token `ok=false`, another
  non-verdict watcher failure, observed findings, clean observation, and the
  no-`sonarcloud` skip. Assert station result and `next_action`, not only
  top-level `ok`.
- Keep the existing mocked Sonar pagination/retry, zero-issue/hotspot,
  header-only, export, and config-parser coverage. Tests use stubs only; no live
  Sonar credential or network dependency enters the suite.
- Policy/documentation coverage must catch drift among `.env.example`, MCP
  runtime docs, ADR-036, Step 11, Quickfix Q11, and the registered tool
  description where those surfaces describe provisioning or recovery.

## Gotchas And Anti-Patterns

- Do not treat `ok=false` as `sonar_findings_open`, increment the five-cycle
  findings cap, or tell the implementer to change code.
- Do not treat a passing GitHub Actions or SonarCloud check-run as proof of
  zero open issues and hotspots.
- Do not place the token in `.ground-control.yaml`, `.mcp.json`, Codex
  `config.toml`, command argv, skill text, GitHub comments, `.gc/sonar` exports,
  telemetry, or test fixtures containing credential-like live material.
- Do not add a caller-supplied `token`, `token_path`, header, or environment map
  to the MCP tool schema.
- Do not load `.env` relative to arbitrary `repo_path`, silently walk upward,
  or broaden host filesystem access to discover credentials.
- Do not duplicate Sonar polling, validation, retry, or workflow branching in
  the skill. The tool layer owns behavior; prose names the returned states and
  recovery.

## Non-Goals

- No weakening, skip, or fallback of the Step 11 server-side issue and hotspot
  scrape.
- No change to SonarCloud quality-gate semantics, finding severities, hotspot
  handling, pagination, retry timing, or the five-cycle repair cap.
- No token retrieval from GitHub Actions secrets, GitHub check payloads, a
  backend, database, vault, keyring, or remote secret manager.
- No general credential-store framework, configuration interpolation system,
  new exception hierarchy, or new persistence model.
- No expansion of the watcher to repositories outside the existing MCP
  launch-workspace and workflow authorization contracts.
