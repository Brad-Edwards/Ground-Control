# Derivation-Backed Change Screening Preflight

Issue: #1122
Requirement: GC-GRC-009

This is architecture guardrail guidance for replacing Step 3.5's
self-certified GRC verdict with derivation-backed change screening. It is not
an implementation plan.

## Boundary

The change belongs to the existing derivation-backed GRC engine and
`/implement` workflow-gate surface. It must compute classification from
derived facts, architecture-model coverage, existing GRC links, and
deterministic threat/control candidates. It must not create a second
agent-owned security verdict store, a second threat/control schema, or a
workflow database aggregate.

The durable workflow record remains the GitHub issue-thread
`gc:grc-screening` record. The authoritative domain state remains in the
existing derivation, architecture-model, threat-model, risk-scenario, control,
risk-control mapping, and link aggregates. The issue-thread record is evidence
that the gate computed and posted its result; it is not the source of truth for
the GRC graph.

## Architecture Decisions

- Treat ADR-058 as the target contract and ADR-057 as the historical v1 record.
  New Step 3.5 runs must not emit a passing `no_baseline` result. Missing or
  empty baseline becomes a scoped `gap_set` over the touched
  security-relevant surface.
- Keep one deterministic classification contract that can serve both the
  `/implement` gate and the future standalone assessment lane. If
  classification logic grows beyond record rendering, put the pure
  classification behind the existing service/engine boundary rather than
  embedding it only in skill prose or GitHub-comment rendering.
- Version the screening payload instead of mutating the v1 shape in place. A v2
  record should carry `impact_set`, `gap_set`, `stale_set`, candidates,
  derivation provenance, coverage/capture-limit evidence, and rule-pack
  versions/checksums in machine-readable fields. Rendered Markdown is only a
  human view over that data block.
- Keep the MCP server as the workflow trust boundary for GitHub side effects.
  Agents may supply repo/issue/scope inputs, but the tool computes the
  classification, validates the payload, renders the record, filters sensitive
  content, caps the body size, posts via the existing `gh api` path, and writes
  the phase marker.

## Incumbents To Reuse

- `gc_derivation` / `DerivationService`: run DIFF-scoped derivation and read
  persisted `DerivationRun`, `SystemModelFact`, `DerivationCaptureLimit`, and
  boundary-model snapshots. Reuse its commit/path/language/surface validation,
  declared-boundary loading from `.ground-control.yaml`, payload key filters,
  adapter routing, `ActorHolder` audit capture, and architecture-model
  snapshot creation.
- `ArchitectureModelService` and boundary-model snapshots: use stable model
  element and boundary identities as the classification unit. Do not classify
  security relevance from filenames alone.
- `gc_threat_enumeration` / `ThreatEnumerationService`: attach deterministic
  GC-GRC-007 candidate threats with pack id, resolved version, checksum,
  snapshot id, model version, producing rule id, matched facts, and limitations.
- `gc_control_identification` / `ControlIdentificationService`: attach
  deterministic GC-GRC-008 candidate controls and control-design gaps. Confirmed
  coverage remains through `RiskControlMapping` and `ThreatModelLink`, not a
  screening-only relationship.
- Current GRC workflow record helpers in `mcp/ground-control/lib.js`:
  `buildGrcScreeningMarker`, the structured data-block parser/serializer,
  reserved-marker rejection, `detectSensitiveBodyContent`, GitHub body-size cap,
  `ensureGitRepo`, `getOwnerRepo`, `readIssueCommentBodies`, and
  `postPhaseMarker`.
- Existing REST client helpers and entity/link tools for threat models, risk
  scenarios, controls, and `targetType=CODE` links. Backend services remain the
  authority for project scoping, entity existence, target resolution, and link
  validity.

## Cross-Cutting Layers

- **MCP input validation:** use Zod for bounded tool inputs: `repo_path`,
  positive `issue_number`, project, commit/base commit, derivation scope,
  path/language/surface arrays, and rule-pack selectors. Do not accept caller
  supplied verdicts, sets, candidates, or provenance as authoritative facts.
- **Backend validation:** pass derivation and GRC reads/writes through existing
  controllers and services so Bean Validation, `ProjectService`, domain
  exceptions, transaction boundaries, and repository queries stay authoritative.
  Duplicate only the minimal MCP shape checks needed before calling REST.
- **Auth surface:** backend calls remain under the shared authenticated
  `/api/v1/**` matrix; pack writes remain admin-only through the pack registry.
  The screening gate should not add a bypass route, unauthenticated endpoint, or
  direct database/AGE query path.
- **Error surface:** backend failures continue through `GroundControlException`
  subclasses, `GlobalExceptionHandler`, and `ErrorResponse`; MCP/tool refusals
  return structured `{ok:false,error,message,next_action}` envelopes. Do not add
  a screening-specific exception hierarchy or raw stack traces to issue comments.
- **Secret and OS exposure:** the issue-thread record and any argv-visible
  `gh api` body must never contain raw diffs, file contents, environment dumps,
  bearer tokens, secret values, raw scanner output, stderr, or stack traces.
  Record stable keys, UIDs, repo-relative paths, run ids, snapshot ids, pack
  versions, checksums, rule ids, and bounded rationales instead.
- **Marker integrity:** every rendered caller-controlled or derived free-text
  field must pass the reserved `<!-- gc:` and HTML-comment delimiter checks
  before posting. Adding a rendered v2 field means adding it to that single
  marker-injection guard.
- **Logging/observability:** backend logs should use the existing SLF4J
  structured style and record counts, ids, schema versions, and outcome classes,
  not raw derived payloads or issue-record bodies. ADR-036 step telemetry may
  record timing/outcome, but it is not the GRC evidence record.
- **Testing:** keep MCP renderer/parser/classification tests in the Node test
  suite, pure classification tests close to the engine, and controller tests as
  `@WebMvcTest` slices when REST shape changes affect coverage. Testcontainers
  integration tests are useful but do not replace Sonar-counted slices.

## Extensibility

The durable seam is a versioned screening schema plus a deterministic
classification engine parameterized by:

- derivation scope: `base_commit_sha`, `commit_sha`, paths, languages, surfaces,
  declared boundaries, run id, and architecture-model snapshot id;
- rule-pack selectors: threat pack id/version and control mapping rule-set
  version, with resolved pack checksums in the record;
- coverage policy: which model coverage, threat coverage, control coverage,
  capture-limit, and stale-link conditions become `gap_set` or `stale_set`.

Do not hardcode repository-specific pack ids, boundary names, path globs, or
surface taxonomies inside the record renderer. Those belong in the scope/config
resolution boundary so the standalone assessment lane can reuse the same engine
with a different scope.

## Gotchas And Anti-Patterns

- Do not keep `no_baseline` as a passing verdict under a new name. A missing
  baseline is scoped work for this run.
- Do not reduce `impact_set`, `gap_set`, and `stale_set` to a single verdict.
  They answer different questions: touched existing coverage, uncovered touched
  security surface, and linked entities whose underlying code/model changed.
- Do not treat candidate threats or controls as confirmed graph state. They are
  deterministic recommendations until the existing GRC write paths confirm or
  disposition them.
- Do not treat capture limits as proof of non-relevance. Underivable or
  unsupported touched security surfaces are gaps unless covered by declared
  model facts or authorized disposition.
- Do not duplicate link validation, project scoping, entity lookup, pack
  resolution, rule evaluation, sensitive-content filtering, marker parsing, or
  GitHub posting helpers.
- Do not put enforcement only in `skills/implement/` prose. The tool layer must
  compute and reject invalid records because agent assertion is the failure mode
  GC-GRC-009 removes.
- Do not publish raw matched-fact metadata blindly. Candidate `matchedFacts` may
  be safe when they are stable keys and predicate labels; anything resembling
  source content, secrets, command output, or raw tool payload must stay out of
  issue-thread records.

## Non-Goals

- No implementation of #1122 in this note.
- No new GRC screening JPA aggregate, migration, workflow table, Temporal
  worker, or repo-local security model file.
- No replacement of existing derivation, architecture-model, threat-model,
  risk-scenario, control, risk-control mapping, or link aggregates.
- No runtime/DAST/instrumentation scope; ADR-058 keeps this gate in the
  build-time repository derivation lane.
- No new privileged agent-side GitHub write path, token exposure path, secret
  configuration surface, or local durable state directory.
