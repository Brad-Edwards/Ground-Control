# Launch-Directory Environment Authority Preflight

Issue: #1562
Requirement: none

This note fixes the configuration boundary for the Ground Control MCP server.
It supersedes only the environment-source and auth-provenance guidance added for
issue #946; the Sonar gate-classification decisions from that issue remain
binding. This is design guidance, not an implementation plan.

## Decisions

### One launch-bound source

The server's initial working directory is the configuration scope. Its exact
`<launch directory>/.env` file is the only source of Ground Control-owned
configuration and credentials. The server must not walk upward, derive a
credential path from a tool's `repo_path`, or read a home-, user-, machine-, or
checkout-global fallback. A caller cannot retarget configuration after startup.

The server may still start without the file. A tool or subprocess-backed
operation that requires an absent value must stop before its side effect and
return a bounded error naming the missing variable (or supported alternative
set) and `<launch directory>/.env`. Optional settings keep their documented
defaults or disabled behavior. Missing, empty, malformed, and unreadable input
must never reactivate an inherited value.

### Authoritative binding, not dotenv fallback

Keep one finite inventory of the environment names Ground Control deliberately
reads or forwards. At startup, inherited values for that inventory are removed
before values from `.env` are installed. The loader must accept only inventoried
keys; an unrelated `.env` entry must not replace `PATH`, `HOME`, or another OS
runtime value. Conversely, the whole inherited environment must not be cleared:
ordinary `node`, `git`, `gh`, `codex`, and `claude` execution still needs
runtime state such as `PATH`, `HOME`, locale, temporary-directory settings, and
platform-specific process variables.

This inventory is the source/provenance boundary, not a second value-validation
schema. Existing consumers continue to validate their values: for example,
`parseCodexTimeoutMs` owns timeout bounds, review-size/parallel consumers own
their supported numeric meanings, and Claude auth selection remains in
`reviewEngineEnv`. Continue using the existing `parseEnvFileLine` grammar; do
not add a second dotenv parser or shell-evaluation semantics.

The inventory and `.env.example` must be held in parity by a contract test.
The audit must include variables outside the template's current list that live
code reads or intentionally forwards, notably `GH_VERIFY_FINDING_AUTHORS`,
`GC_KNOWLEDGE_INGEST_ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, and the supported
review-engine auth selectors and companion values currently represented by
`reviewEngineEnv` and its tests. The citation variables have real Python
consumers and `.mcp.json` bindings, but they belong to the separate Citation MCP
process; documentation must not imply that the Ground Control server loader
provisions that sibling process. Pre-#1500 backend variables with no executable
consumer are not part of either inventory or template.

### Configuration must load before consumers evaluate

Environment loading is an entry-point bootstrap boundary. It must complete
before the protocol/runtime module graph evaluates any environment-derived
default. This is observable today: `DEFAULT_CODEX_REVIEW_PARALLEL` and
`DEFAULT_CODEX_REVIEW_MAX_DIFF_BYTES` read `process.env` at module evaluation,
which currently happens before the call to `loadServerEnvFiles` in `index.js`.
A source-order call below static imports does not establish the required order.

Keep the loader extracted under `mcp/ground-control/lib/` and unit-testable
without the MCP protocol. A narrow bootstrap-to-runtime import seam is the
future-proof ordering contract: the bootstrap loads the launch file, then loads
the server runtime. Do not distribute dotenv calls across consumers or require
every future setting to remember an import-order workaround.

### Review processes receive declared auth plus required OS state

Remove `REVIEW_ENGINE_ENV_FALLBACK`, its home-directory read, and the
fallback-trigger gate. `reviewEngineEnv` remains the single child-environment
builder for both the test-quality reviewer and the disposition judge. It may
preserve inherited OS execution state, but every Claude configuration or auth
value it deliberately supports must already have passed through the
launch-file-owned inventory. If no supported review auth mode is declared, the
review operation fails before spawning `claude`, naming the alternatives and
the launch `.env`; it must not silently fall through to ambient auth.

Preserve the existing conflict rule that removes `ANTHROPIC_API_KEY` when an
explicit alternate auth mode is selected, unless the supported-auth contract is
deliberately narrowed. Do not introduce a second auth-precedence helper or a new
exception hierarchy. `codexEngineEnv` remains the incumbent minimal environment
for Codex children; any credential it forwards must likewise be launch-file
sourced, while `HOME`, `PATH`, and `CODEX_HOME` remain runtime inputs rather than
Ground Control credential fallbacks.

## Canonical Incumbents and Cross-Cutting Boundaries

- The renamed successor to `lib/host-env.js`, `parseEnvFileLine`, and the thin
  startup entry point form the one environment-loading path. Dependency
  injection of the target environment and launch directory remains the testing
  seam; a home-directory parameter does not.
- `parseCodexTimeoutMs` / `getDefaultCodexTimeoutMs`, the existing review
  parallel and diff-budget consumers, `getRuntimeAllowedAuthors`, and the
  lifecycle emitter remain the value consumers. Source authority must not
  duplicate their semantic validation or optional/default behavior.
- `reviewEngineEnv` is shared by `runSingleClaudeTestQualityReview` and
  `runDispositionJudge`. `codexEngineEnv` is the established credential-
  minimization pattern for Codex subprocesses. Neither tool registration gains
  a caller-supplied environment, token, auth mode, or file path.
- `normalizeSonarcloudConfig` remains the non-secret repository config boundary;
  `validateWatchSonarAnalysisInput` remains the direct-call semantic validator;
  `runWatchSonarAnalysis`, `_sonarAuthHeader`, and the existing retry/pagination
  path remain the sole Sonar client. `SONAR_TOKEN` stays in the in-process
  Authorization header, never argv, URLs, telemetry, exports, logs, or results.
- `classifySonarGateFailure`, `sonarStationResult`, and the mechanical
  `failure()` envelope retain the `not_evaluable` versus findings distinction.
  A missing token remains an operator provisioning fault and consumes no Sonar
  findings cycle.
- Existing MCP `ok`/`err` responses, async-job error bounding/scrubbing, and
  sensitive-content checks remain the error surfaces. Missing-config messages
  contain names and the launch-file path only, never values, the full
  environment, child output, or a stack trace.
- `.gitignore` remains the repository backstop for real `.env` files. No env
  value enters the issue thread, review findings, station records, `.gc/sonar`
  persistence, or tool telemetry. The server continues to own privileged
  argv-based Git/GitHub side effects under ADR-027.
- `mcp/ground-control/package.json` is the server compatibility/version source.
  This changes behavior without changing a tool schema or result type, so the
  repository's documented rule classifies it as a patch bump.

## Security and Validation Layers

1. **File and scope:** resolve exactly one file from the immutable launch
   directory. No home lookup, parent search, arbitrary caller path, symlink-
   discovery feature, shell sourcing, or remote secret retrieval is added.
2. **Binding shape:** `parseEnvFileLine` handles the one accepted grammar, and
   the owned-key inventory prevents `.env` from becoming an arbitrary process-
   environment override. Empty or absent owned values remain absent.
3. **Value validation:** preserve the existing bounded timeout parser, review
   numeric defaults, Sonar input/config validation, and auth-mode selection.
   Source validation must not become a duplicate schema for those meanings.
4. **Subprocess boundary:** preserve enough inherited OS state to execute fixed
   argv commands. Use the existing Codex and Claude child-environment builders;
   credentials stay in environment/profile mechanisms, not argv or prompts.
5. **Network secret use:** Sonar uses its Authorization header; lifecycle and
   telemetry bearer tokens continue through `addAuthorizationHeader`. No token
   is placed in a URL, query, body, or diagnostic.
6. **Error envelopes:** Sonar keeps its structured
   `sonar_watch_token_missing` result and mechanical `not_evaluable` mapping.
   Review-engine absence must cross the existing sync/async MCP error surfaces
   as a bounded operator action, with no secret-bearing fallback error text.
7. **Persistence and observability:** startup may report that the launch file
   was consulted, but never its contents. Tool telemetry, issue-thread records,
   and Sonar exports do not gain configuration fields.

## Required Regression Boundaries

- Unit coverage must prove file-over-ambient authority, removal of an inherited
  owned value when the file omits or empties it, preservation of non-owned OS
  values, exact launch-directory selection, no host/review fallback read, and
  the shared dotenv grammar.
- A real server-start test must prove `.env` is loaded before import-time
  consumers evaluate. Pure loader tests cannot catch the current static-import
  ordering defect.
- Review subprocess tests must cover every supported auth mode, ambient auth
  rejection, the alternate-mode/API-key conflict rule, missing-auth diagnostics,
  input immutability, and use by both Claude spawn sites.
- Existing Sonar header-only, pagination/retry, export-containment, missing-token
  envelope, `not_evaluable`, timeout, and Step 11 `ok:false` tests remain intact.
- A template/inventory contract must prevent `.env.example` from advertising a
  dead key or omitting a supported Ground Control-owned key. Citation keys must
  be checked against their Citation MCP consumers separately.

## Documentation Consistency Guardrail

The issue's named documentation set is not exhaustive. Current normative text
also records the removed design in the issue #946 sync notes at the top of
`architecture/adrs/054-documentation-coverage-gate.md` and
`docs/DOC_STYLE.md`, and in
`architecture/notes/sonar-watcher-token-provisioning-preflight.md`. Review-auth
wording also appears in `architecture/notes/test-quality-review-engine.md`,
`skills/implement/steps/step-06.6-test-quality-review.md`, and the
`gc_test_quality_review` tool description. All current guidance must agree that
configuration comes only from the launch `.env`; historical changelog entries
may remain historical and must not be rewritten as current guidance.

## Gotchas and Anti-Patterns

- Do not treat deleting `hostEnvFilePath` as sufficient while inherited owned
  values still win or survive when `.env` omits them.
- Do not call the loader below a static import graph that already captured env-
  derived defaults.
- Do not clear `process.env`, overwrite OS runtime keys from arbitrary `.env`
  lines, or give every subprocess an empty environment.
- Do not read `.env` relative to a tool's `repo_path`, search upward, consult
  `homedir()`, or retain a hidden compatibility fallback.
- Do not add secrets to `.ground-control.yaml`, `.mcp.json` tool arguments,
  Zod tool schemas, prompts, command argv, issue comments, or telemetry.
- Do not add per-variable dotenv readers, duplicate parsers, a generalized
  credential-provider framework, or another error hierarchy.
- Do not change `lib/sonar-gate.js`, monitor `not_evaluable` semantics, the
  quality-gate timeout fix, or Step 11's `ok:false` branch while correcting
  provisioning messages.
- Do not describe all variables as required. Defaults and disabled optional
  features remain distinct from a tool that cannot run without its credential.

## Non-Goals

- No tool registration, Zod input schema, public result schema, or MCP protocol
  change.
- No change to Sonar gate evaluation, retries, pagination, finding/hotspot
  thresholds, timeout classification, or repair-cycle accounting.
- No backend, database, config service, vault, keyring, machine-wide file, or
  new persistence model.
- No change to Citation MCP environment loading beyond making its separate
  ownership clear in the shared template.
- No editing or discovery of operators' real `.env` files, and no migration of
  dead pre-#1500 backend variables.
- No attempt to replace the external CLIs' own profile formats or the OS
  runtime environment; this change controls only variables Ground Control reads
  or deliberately forwards.

## Design Vocabulary That Applies

- **Boundary contract:** the MCP server remains the only running Ground Control
  service and owns every privileged Git/GitHub side effect. Environment loading
  stays inside that server boundary.
- **ADR-027:** `.ground-control.yaml` remains the agent-neutral workflow context
  contract, while secrets and runtime variables remain outside it; privileged
  side effects continue in the MCP server.
- **Anti-recommendation:** do not introduce a generalized credential-provider
  abstraction for this single launch-file source; keep the existing loader and
  child-environment seams.
- **Anti-recommendation:** do not add prompt/skill claims as the enforcement
  mechanism. The loader, owned-key boundary, spawn environments, and tests must
  enforce the rule.
- **Anti-recommendation:** comments should explain only the non-obvious launch
  authority, import-order, and secret-provenance invariants.
