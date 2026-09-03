# Repository-Neutral PR-Body Rendering Preflight

Issue: #1199
Requirement: none

This note fixes the architecture boundaries for making `gc_render_pr_body`
honest across repositories. It does not change the renderer, policy checks,
workflow configuration, or PR template, and it is not an implementation plan.

## Decision

`gc_render_pr_body` keeps ownership of the stable `/implement` evidence
envelope: issue linkage, requirement UIDs, ADR impact, change summary,
traceability markers, documentation outcome, and changelog mode. Stack-specific
test tools, migration filenames, framework layering rules, audit annotations,
and documentation paths are not part of that envelope.

The Test Plan, Ground Control Checks, migration reminder, and Checklist must use
repo-neutral attestations that describe gates the workflow actually enforces.
In particular:

- the completion attestation means the effective configured completion gate
  (`workflow.completion_command`, with `workflow.test_command` as its existing
  fallback) passed;
- the policy attestation means the normalized `workflow.policy_command` passed;
- merely declaring `lint_command` or `format_command` does not prove that the
  workflow ran it, so the renderer must not check either independently;
- `change_class: source+migration` may select a generic migration-verification
  reminder, but must not imply Java, Flyway, or named test classes;
- checklist claims must be universally meaningful workflow evidence, not
  Ground Control's Java/domain architecture rules; and
- exact test evidence remains caller-supplied through the existing scrubbed
  `test_notes` seam. It is evidence text, not an executable command; the render
  boundary must add an explicit size bound consistent with the final body cap.

Configured command values must not be copied into the PR body. They are
repo-authored execution configuration and may contain internal paths,
environment bindings, or shell syntax. The durable body should name the gate
semantically, preserving ADR-029's issue-#1429 decision for policy commands.

## Configuration and Concept Boundaries

`.ground-control.yaml` remains the only repository configuration boundary
(ADR-027). The MCP library must obtain its normalized view through
`getRepoGroundControlContext`; the renderer must not read or parse YAML itself,
and the skill must not infer stack or language from filenames, command text, or
repository name.

Keep these concepts separate:

- an execution command says how a trusted workflow boundary runs a gate;
- an attestation says that the gate passed without publishing its command;
- `change_class` shapes workflow evidence and changelog behavior; it is not a
  language, framework, ORM, or migration-tool discriminator;
- `test_notes` records run-specific evidence; it is not a second workflow
  configuration schema; and
- the PR-body policy recognizes the stable evidence envelope; it does not
  decide which test framework a repository uses.

Do not introduce a stack/language flag for this issue. Removing stack-specific
claims solves the contract without creating a taxonomy that would immediately
grow Java, Python, JavaScript, Django, Spring, and migration-tool combinations.

## Canonical Incumbents to Reuse

- `mcp/ground-control/lib/ground-control-config.js` plus
  `normalizeWorkflowConfig` are the strict schema and defaulting boundary.
- `getRepoGroundControlContext` owns repository-root resolution, config loading,
  normalization, and structured invalid/missing status.
- `validatePrBodyInput` owns renderer cross-field validation; the zod tool
  schema remains the transport-shape boundary.
- `buildPrBody` remains the pure renderer, and `runRenderPrBody` remains its
  repository-aware orchestration and structured-result boundary.
- `detectSensitiveBodyContent` is the final secret-pattern scrub before a body
  can reach the privileged GitHub writer.
- `checkPrBodyShape` is the MCP structural guard;
  `tools/policy/authz_matrix.py::check_pr_body` is the repo-native CI/local
  policy guard. The subprocess fixture that sends the actual JavaScript render
  through the Python predicate remains the cross-language drift detector.
- `runCreateSynchronizedImplementPr` remains the only write path. It rechecks
  body shape, sensitive and reserved content, repository context, authorized
  workspace/repository identity, branch synchronization, and PR title before
  its argv-based `gh pr create` call.
- `.github/PULL_REQUEST_TEMPLATE.md` is the synchronized human-facing template,
  not input to the renderer and not a second machine-readable schema.

The JavaScript and Python shape checks may necessarily express the same small
stable markers in two languages. Do not grow that duplication into two copies
of a configurable test/checklist schema. Keep the renderer-to-policy subprocess
test as the executable parity contract.

## Security and Cross-Cutting Layers

- **Tool input shape:** zod rejects invalid issue numbers, change classes,
  requirement identifiers, and structured traceability before the handler.
  Library validation retains cross-field rules such as changelog mode versus
  change class. The implementation must bound `test_notes`, array cardinality,
  array element size, and final rendered-body size no later than the renderer;
  `gc_create_synchronized_implement_pr` already caps its body at 65,535
  characters, so a renderer success must not create an artifact that the next
  canonical boundary necessarily rejects. Shared constants should keep zod and
  library checks aligned. Repository-derived content must not bypass either
  layer.
- **Repository/config shape:** `ensureGitRepo` and
  `getRepoGroundControlContext` bind the read to the repository root. Strict
  unknown-key rejection and normalized defaults remain authoritative. An
  invalid context must produce a stable renderer error and no body.
- **Secret and public-content boundary:** render first, then run the existing
  sensitive-content detector over the complete body, including `test_notes`.
  Do not log the body or normalized command values. The GitHub PR body is public
  durable content for many repositories.
- **Authorization boundary:** rendering is read-only and grants no GitHub
  authority. PR creation stays in the MCP service, which binds the launch
  workspace and origin identity before using ambient `gh` credentials. No
  agent-side `gh`, `git`, or `curl` path is added.
- **OS exposure:** `runCreateSynchronizedImplementPr` currently passes the body
  as a `gh` argv element. That avoids shell interpretation but can expose the
  body to process inspection. Only already-scrubbed, publication-safe Markdown
  may cross this boundary; raw configured commands, environment values, and
  secrets must not.
- **Policy validators:** the rendered body must pass both JavaScript structural
  validation and Python `check_pr_body`, followed by the existing no-deferral
  policy path. The human template and policy fixtures must stay synchronized
  with the same repo-neutral claims.
- **Error envelope:** expected config/input/policy failures return bounded
  `{ok:false, error, message, next_action?}` results. Do not let YAML contents,
  commands, the PR body, child-process argv, or environment state leak through
  generic exception messages.
- **Logging/observability:** no new logger, telemetry event, or durable marker is
  warranted. Existing tool results are sufficient; body content and command
  configuration are not telemetry dimensions.
- **Persistence:** there is no backend, database, DTO, repository, migration, or
  Envers surface. The eventual GitHub PR body is the only durable artifact this
  renderer produces.

## Extensibility Seam

The current issue should prefer the repo-neutral envelope over a new checklist
DSL. If multiple repositories later demonstrate a real need for additional
attestations, the seam belongs in a strictly normalized `workflow.pr_body`
block returned by `getRepoGroundControlContext`, not in skill prose and not as
caller-supplied stack flags. Such entries must be bounded, single-line,
deduplicated publication text; they must never be executable commands or raw
Markdown sections. The pure renderer may consume that normalized view, while
the Python policy continues to validate stable structural markers rather than
duplicating every repository's configured prose.

This leaves one obvious future variation available without conflating
execution configuration with presentation policy, while avoiding a one-repo
abstraction now.

## Whole-Repository Synchronization Surface

The eventual behavior change intersects the tool registration and zod schema,
the PR-body library, repository-context loading, JavaScript unit tests, the
JavaScript-to-Python compose fixture, Python PR-body policy and fixtures, the
human PR template, Step 9 workflow guidance, and the ADR-029/ADR-036 workflow
contract prose. The `workflow-guardrail-sync` policy requires workflow docs and
a gate-model record to move with PR-template changes. Documentation coverage
must be declared if the configuration parser is touched.

## Non-Goals and Anti-Patterns

- No implementation of issue #1199 in this preflight.
- No stack detector, language enum, framework matrix, migration-tool registry,
  or parser for `.gc/plan-rules.md` or the PR template.
- No second `.ground-control.yaml` parser and no renderer-local defaults that
  can disagree with `normalizeWorkflowConfig`.
- No publication of configured command strings merely to make the body look
  concrete.
- No checkbox for a command or quality signal the workflow did not actually
  run, including standalone lint/format commands or post-creation CI results.
- No weakening of requirement UID, ADR impact, traceability, changelog,
  documentation outcome, no-deferral, secret scrub, synchronized-branch, or
  repository-identity gates.
- No backend/domain/persistence/security-chain changes and no new exception or
  logging hierarchy.
