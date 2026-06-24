# ADR-054: Documentation coverage gate

## Status

accepted

## Date

2026-05-23

> **Style sync for issue #751 (2026-06-14):** Repository-wide Vale cleanup normalized punctuation in documentation prose. This ADR's documentation coverage gate stays the same.

> **Sync note for issue #1107 (2026-06-14):** The `gc_requirement` history/timeline reads gained an `expand` passthrough (MCP `lib.js`/`index.js`) so callers can fetch full, untruncated audit-diff field values; the new audit-diff API surface is documented in `docs/API.md`. The `classifyChangedSurface` surface vocabulary and `outcome_required` mapping are unchanged.

> **Sync note for issue #1106 (2026-06-15):** The MCP–backend write-contract drift gate exported `TO_CAMEL` / `OPAQUE_VALUE_KEYS` from `mcp/ground-control/lib.js` and corrected the drifted `GOVERNANCE_FIELDS` allowlists (consumed by the new `mcp/ground-control/openapi-contract.test.js`), and fixed the `gc_control` / `gc_asset` / `gc_risk_governance` adapter field allowlists and Zod shapes to match the backend DTOs. No new public `gc_*` tool is registered; the `classifyChangedSurface` surface vocabulary, `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged. The new gate's own documentation lives in `docs/DEVELOPMENT_WORKFLOW.md`, `mcp/ground-control/README.md`, and the ADR-034 amendment.

> **Sync note for issue #1180 (2026-06-18):** Added optional `short_code` field to `.ground-control.yaml` config parsing (`parseGroundControlYaml` in `mcp/ground-control/lib.js`): validated as uppercase alphanumeric 1–8 characters, absent defaults to null, surfaced via `getRepoGroundControlContext`. The implement-workflow skills (`step-01-issue-branch-resolution.md`, `step-20-close-issue-on-merge.md`) were updated to rename the tmux session when `$TMUX` is set and `cfg.short_code` is non-null. No new public `gc_*` tool is registered; the `classifyChangedSurface` surface vocabulary and `outcome_required` mapping are unchanged.

> **Sync note for issue #1176 (2026-06-15):** Extended `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY` with three new enum-contract entries (`VerificationStatus`, `AssuranceLevel`, `MethodologyFamily`) so ADR-034's enum-mirror gate covers GRC verification enums. Added corresponding TypeScript union types and constant arrays to `frontend/src/types/api.ts`, mirrored the enum values at the MCP layer, and updated MethodologyProfile interface field types from string to MethodologyFamily per ADR-034. The classifier, Vale rules, `.vale.ini`, and `docs/DOC_STYLE.md` style rules are unchanged.

## Context

Changes that modify workflow behavior, MCP tool surfaces, config parsing,
policy, ADRs, public APIs, or user-visible behavior require corresponding
documentation updates. Without a mechanical gate, documentation drifts from
the code it describes. The `workflow.pr_title` block in `step-09-pr-body.md`
referenced a config key that `normalizeWorkflowConfig` did not parse, and this
went undetected until issue #896.

Three checks need to hold at the end of every `/implement` run:

1. The changed-surface classifier identifies which documentation targets are
   in scope.
2. The PR body and final report carry a structured `documentation_outcome`
   field recording what happened: docs updated, docs verified unchanged, or
   docs intentionally not updated with an authorized rationale.
3. The prose quality of any modified docs meets the project style standard
   (Google Developer Documentation Style Guide for voice; Diátaxis for
   structure).

ADR-027 establishes `.ground-control.yaml` and `gc_get_repo_ground_control_context`
as the agent-neutral config contract. ADR-029 mandates that durable evidence
belongs in the PR and issue-thread records, not in optional free-form summaries.
ADR-036 requires deterministic renderers for PR bodies and final reports so
omissions are visible at the tool boundary.

## Decision

A documentation coverage gate is added to the `/implement` workflow with three
executable layers:

**Layer 1: changed-surface classifier (`classifyChangedSurface` in lib.js).**
A closed-vocabulary function maps repo paths to surface classes and documentation
targets. Surface classes: `workflow`, `mcp_tool`, `config_parser`, `policy`,
`adr`, `public_api`, `user_visible`, `doc`, `unclassified`. When any path
classifies as one of the first five non-doc surfaces, `outcome_required` is
true and the PR body must carry a `documentation_outcome` field.

**Layer 2: structured outcome field in PR body and final report.**
`validatePrBodyInput` and `validateFinalReportInput` accept an optional
`documentation_outcome: { outcome, rationale? }` field. The outcome enum is
closed: `updated`, `verified_unchanged`, `not_updated_authorized`. Only the
third value permits a rationale string (1-2000 characters); the other two
reject it (strict). When `outcome_required` is true and the field is absent,
the renderer rejects the input rather than posting an incomplete record.

**Layer 3: Vale prose linter wired into `make policy`, CI, and pre-commit.**
Vale with the `errata-ai/Google` package enforces the Google Developer
Documentation Style Guide on docs modified in the current diff. The binary is
pinned to a specific version, verified by SHA-256 checksum, and installed by
`tools/install-vale.sh` to `.tools/vale/` (gitignored). The pre-commit hook
installs Vale automatically on first need rather than skipping; agents and
contributors do not bypass the gate by virtue of a fresh clone.

**House-style overrides (`GoogleProject/` namespace).** The `.vale/styles/GoogleProject/`
directory is the registry for project-specific rules that augment the upstream
`errata-ai/Google` package. The first such rule is `EmDashDensity`: an
occurrence-based check scoped to paragraph, `max: 1`, `level: error`, that
flags paragraphs containing more than one em-dash. The rule pairs with the
em-dash density guidance in `docs/DOC_STYLE.md §Em-dash density` and runs at
error level so the on-touch ratchet enforces density compliance the same way
it enforces every other Google rule: any doc touched in a PR must satisfy the
budget. Future house-style overrides (passive-voice budget, sentence length,
hedging patterns) land as sibling YAML files in the same
namespace with no additional plumbing.

**Scope: whole file on first touch.** Vale lints any `.md` / `.markdown` file
that appears in the current diff (added, copied, modified, or renamed vs the
base ref) in its entirety, not line-by-line. A one-line edit to a previously
untouched document brings the whole file into scope; all of its style
violations must be fixed in that PR. Untouched docs are not linted. This
"ratchet on touch" produces a finite migration trajectory: each touched file
becomes permanently compliant, and the codebase converges as docs are edited
in the normal course of work. Line-range / hunk-aware linting, for example
via reviewdog, was considered and rejected; it permits prose rot to persist in
touched files indefinitely.

The canonical documentation style is: Google Developer Documentation Style
Guide for voice, tense, and concision; Diátaxis (`tutorial / how-to / reference
/ explanation`) for structure. Docs describe the system as it ships on the
current commit. Roadmaps, phase tables, and forward guidance belong in tracking
issues.

A new MCP tool `gc_documentation_coverage` exposes the classifier to agents:
input `{ repo_path, changed_paths[] }`, output
`{ ok, classifications[], outcome_required, suggested_doc_targets[] }`.

**MCP-surface additions are classified `mcp_tool`.** New `gc_admin` actions
(for example `replace_research_intake` in issue #999) or any future
`gc_*` tool registered in `mcp/ground-control/index.js` inherit the existing
`mcp_tool` classification on path basis; the closed-vocabulary classifier
does not need an update per-action. The gate-sync rule
(`doc-coverage-gate-sync` in `architecture/policies/adr-policy.json`) fires
whenever the listed trigger paths change so this ADR and `DOC_STYLE.md` stay
current with the actual classifier surface.

## Consequences

- PRs that modify a classified surface must supply `documentation_outcome` or
  the PR-body renderer rejects the input.
- `not_updated_authorized` requires a bounded rationale string; silent omission
  is not possible.
- Vale failures gate `make policy` on docs modified in the diff.
- The `workflow.pr_title` parser gap is fixed as the concrete drift example
  this gate exists to prevent.
- Existing docs migrate organically when modified; no bulk rewrite is required.
- The doc-target map is data-driven: adding a new surface class is a single
  table edit in `classifyChangedSurface`.

## Alternatives considered

- **Prose-only enforcement** (skill instructions telling the agent to check
  docs): rejected. Prose instructions cannot be mechanically verified and
  accumulate silent drift. The preflight note for this issue explicitly
  prohibits "a broad natural-language style reviewer as the enforcement layer."
- **Separate database table for documentation state**: rejected per the
  preflight non-goals. The PR body and final report are the durable records
  (ADR-029); a second store would create reconciliation problems.
- **Lint the whole doc tree on every run**: rejected. Bulk rewrites risk losing
  intent in existing prose. Diff-scoped linting achieves organic migration
  without the risk.
- **Hunk-aware linting (reviewdog or line-range Vale)**: rejected. Reduces the
  migration cost of touching old docs but lets pre-existing prose rot stay
  in touched files forever, defeating the ratchet. Whole-file-on-touch is the
  deliberate cost.
- **Graceful skip when Vale is not installed locally**: rejected. Lets agents
  and contributors commit unlinted prose on fresh clones, which is the
  failure mode the gate exists to prevent. The pre-commit hook installs Vale
  via `tools/install-vale.sh` on first need.

## References

- ADR-027: `.ground-control.yaml` and `gc_get_repo_ground_control_context` are
  the agent-neutral config contract.
- ADR-029: The GitHub issue thread is the durable workflow record.
- ADR-036: Per-step routing, deterministic record-rendering tools, and
  per-step telemetry.
- Issue #896: Enforce documentation coverage + style as an explicit workflow
  step.
- Issue #863 / GC-T004 C8: extended the MCP `gc_risk_governance` Zod shape and
  the `TO_CAMEL` map in `mcp/ground-control/lib.js` for typed reassessment
  triggers and the `reassessment_required_at` response field. The change is
  an additive surface extension governed by the same gate; the underlying
  classifier already covered `mcp/ground-control/lib.js` as a `config_parser`
  surface, so no classification update was needed.
- Google Developer Documentation Style Guide: https://developers.google.com/style
- Diátaxis: https://diataxis.fr/
- Vale: https://vale.sh/
- errata-ai/Google Vale package: https://github.com/errata-ai/Google

## Amendments

**2026-06-23 (release PR exempt from the per-PR body contract).** The `dev` -> `main` release PR aggregates feature PRs that each already satisfied the PR-body contract on the way into `dev`, so re-imposing it failed every release PR on `pr-requirement-uid` / `pr-ground-control-checks` / the `## Documentation` outcome. `main()` in `tools/policy/checks.py` now resolves the PR base/head (`_resolve_pr_refs`) and, for a release PR (`base == main` and `head == dev`, via `_is_release_pr`), skips `check_pr_body` and passes `pr_body=None` to the documentation-coverage check; the changed-file checks (changelog, migration, enum/controller parity, etc.) still run on the aggregate diff. The surface classifier (`classifyChangedSurface`), `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged.

**2026-06-23 (Flyway migration immutability guard).** Added a `migration-immutability` check to `run_migration_policy` in `tools/policy/checks.py`: any migration file already present on the released baseline (`origin/main`) that is modified or removed in the diff fails `make policy`, since Flyway validates checksums on every startup and editing an applied migration crashes every database that already ran it (the V043/V045 production incident, which a fresh-DB smoke test structurally cannot catch). New forward migrations are exempt because they are absent from the baseline. This is a migration-policy-surface extension; the documentation-coverage classifier (`classifyChangedSurface`), `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged.

**2026-06-23 (GHCR namespace drift gate - issue #953, GC-P022).** Added `run_ghcr_namespace_drift` to `tools/policy/checks.py`: a static post-condition that scans a fixed inventory of deploy/CI/doc artifacts (`Makefile`, `.github/workflows/ci.yml`, the `deploy/docker/.env.*` templates and compose/deploy scripts, `deploy/scripts/deploy.sh`, the deployment docs, and ADR-030) and fails `make policy` when any references a non-canonical `ghcr.io/<ns>/ground-control` namespace (canonical: `autarchy-ai`). The check exists because the CI publish namespace silently diverged from the deploy-host image pin across the KeplerOps → Brad-Edwards → autarchy-ai org moves, so `docker compose pull` kept resolving a frozen image for ~10 days while the healthy old container kept the deploy health check green (#953). `CHANGELOG.md` is excluded (historical release notes); test files are excluded (their negative-case fixtures legitimately carry non-canonical literals). This is a deploy-policy-surface extension; the documentation-coverage classifier (`classifyChangedSurface`), `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged.

**2026-06-23 (deploy artifact consistency gate - issue #855, GC-P023).** Added `run_deploy_artifact_consistency` to `tools/policy/checks.py`: a static post-condition over the operator-driven deploy surface that fails `make policy` when (a) a second contradictory env template (`.env.template`) or the dead duplicate wrapper (`deploy/scripts/deploy.sh`) reappears, (b) `deploy/docker/env.schema` drifts from the production compose contract (a `${VAR}` dereferenced with no default but not marked `REQUIRED`, or absent entirely) or stops marking `GC_IMAGE` `FLOATING_TAG`, (c) `deploy/docker/MANIFEST.sha256` no longer matches the canonical artifacts byte-for-byte (regenerate with `make deploy-manifest`), or (d) the operator wrapper `scripts/deploy.sh` reimplements the `docker compose pull/up` rollout primitives that belong only in the canonical `deploy/docker/deploy.sh`. The check exists because the red-dragon deploy broke silently many times on artifact drift with no single source of truth (#855); `env.schema` is now the one contract shared by this gate and the deploy-time `validate-env.sh`. The GHCR-namespace inventory was updated for the removed `.env.template` and the relocated wrapper path. This is a deploy-policy-surface extension; the documentation-coverage classifier (`classifyChangedSurface`), `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged.

**2026-06-22 (issue #1162 gc_create_github_issue undefined fix).** Fixed the `gc_create_github_issue` tool handler in `mcp/ground-control/index.js`, which forwarded its raw `{uid, project, repo, labels, extra_body}` args straight into `createGitHubIssue` (which expects `{title, body, labels, repo}`) and therefore created issues with literal `undefined` title and body and no traceability link. Added a `createGitHubIssueFromRequirement` orchestration helper in `mcp/ground-control/lib.js` that fetches the requirement by UID, renders the title and body via the existing `formatIssueBody` (now reading the API-normalized `folder_title` field that `toSnakeCase` produces), and auto-creates the IMPLEMENTS (ACTIVE) / DOCUMENTS (otherwise) traceability link, surfacing a `traceability_error` instead of silently succeeding on partial failure. The tool description string was updated to reflect the DRAFT auto-link and partial-failure behavior. These are MCP tool-surface / bug-fix changes only; the `classifyChangedSurface` surface vocabulary, `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged.

**2026-06-22 (issue #260 GC-T005 risk appetite & tolerance).** Added a new `gc_analyze` kind `appetite_evaluation` (registered in `mcp/ground-control/index.js`) with the matching `analyzeRiskAppetiteEvaluation` helper in `mcp/ground-control/lib.js`, and a new `risk_appetite_profile` entity on `gc_risk_governance` (`mcp/ground-control/gc-risk-governance.js`) with its CRUD helpers and `GOVERNANCE_FIELDS` / `GOVERNANCE_STATUS_ENUMS` allowlists in `lib.js`. Added the `appetite_key` / `methodology_family` / `appetite_statement` / `tolerance_thresholds` / `effective_from` / `effective_to` entries to the shared `TO_CAMEL` map so the snake_case MCP fields round-trip to the backend camelCase DTO, and matching `RiskAppetiteProfileRequest` / `UpdateRiskAppetiteProfileRequest` write-contract blocks in `mcp/ground-control/openapi-contract.test.js`. Documentation lives in `docs/API.md` (`/api/v1/risk-appetite-profiles` CRUD and `GET /api/v1/analysis/grc/appetite-evaluation`) and `docs/architecture/ARCHITECTURE.md` (risk appetite & tolerance section). These are MCP config-parser / tool-surface additions; the `classifyChangedSurface` surface vocabulary, `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged.

**2026-05-26 (issue #989).** The `gc_integration_manager` MCP tool (`mcp/ground-control/gc-integrate.js`) and the `gc_integration_manager` entry in `mcp/ground-control/index.js` are new tool surfaces added under this issue. The doc-coverage gate (`doc-coverage-gate-sync` policy rule) triggers on changes to `mcp/ground-control/lib.js` and `mcp/ground-control/index.js`; the tool's documentation lives in `mcp/ground-control/README.md § gc_integration_manager` and `docs/DEVELOPMENT_WORKFLOW.md § /integrate`. No change to the Vale rule set, the `tools/install-vale.sh` installer, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-05-26 (issue #989 follow-up).** Fixed a wrapper-layer regression where `gc_render_pr_body` and `gc_post_final_report` did not propagate the optional `documentation_outcome` field. The Zod input schemas omitted the field and the destructure-and-call did not forward it, so the renderer never emitted the `## Documentation` section that this ADR's policy gate requires. Both wrappers now accept `documentation_outcome` (object with `outcome` enum and optional `rationale`) and pass it through to `runRenderPrBody` / `runPostFinalReport`. Unit tests in `lib.test.js::runRenderPrBody` cover the three rendering paths and the omission case.

**2026-05-26 (issue #989 merge carve-out).** The `lib.js` change in this commit adds `INTEGRATION_MANAGER_MERGE_STRATEGIES` and extends `normalizeIntegrationManagerConfig` with the `merge_strategy` field. These changes are to the integration manager config parser, not to any documentation coverage gate surface. No change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` is required.

**2026-06-15 (issue #1168).** `tools/policy/checks.py` gained `run_workflow_routing_contract` and its `parse_routing_agents` helper - a guardrail that asserts the async-poll `/implement` routing stages in `.ground-control.yaml` resolve to `agent: parent`. The change is unrelated to documentation coverage: the surface classifier (`run_documentation_coverage_check`), the Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged. `docs/DOC_STYLE.md` is updated only to list `tools/policy/checks.py` among the gate-surface trigger paths it previously omitted.

**2026-05-26 (issue #989 SDK schema hotfix).** Re-registered `gc_integration_manager` via `server.tool(name, desc, zodShape, handler)` so the SDK's `safeParseAsync` path resolves; the prior `server.registerTool({inputSchema: <raw JSON Schema>})` form crashed every invocation with `v3Schema.safeParseAsync is not a function`. The fix touches `mcp/ground-control/index.js` only; no change to the Vale rule set, the `.vale.ini` configuration, the doc-coverage classifier, or any documentation target surface.

**2026-05-28 (issue #720 FAIR risk scenario refactor).** The `gc_risk_scenario` MCP tool field renames (`threat_source`→`threat`, `threat_event`→`method`, `affected_object`→`asset`, `consequence`→`effect`) required updating the `TO_CAMEL` mapping in `mcp/ground-control/lib.js` to remove obsolete snake_case bindings and add the new derived field `fair_sentence` mapping. Additionally, `tools/policy/checks.py` was updated to recognize `mcp/ground-control/gc-risk-scenario.js` as a valid MCP-adapter file (alongside `gc-risk-governance.js`) for the `controller-parity` policy check. These are config-parser and policy surfaces; no change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-06-11 (issue #1100 GRC reconciliation gate).** The `gc_assert_grc_reconciled` MCP tool is registered in `mcp/ground-control/index.js` and implemented in `mcp/ground-control/lib.js`. The doc-coverage gate triggers on these paths; the tool's documentation lives in `docs/DEVELOPMENT_WORKFLOW.md` (GRC reconciliation gate row added to the per-step optimization table) and `skills/implement/steps/step-17-verify.md` (Step 6 added for `gc_assert_grc_reconciled`). The tool-enumeration example list in `docs/DOC_STYLE.md` was extended to name `gc_assert_grc_reconciled` (and a pre-existing duplicated paragraph there consolidated); no new Vale rule, `tools/install-vale.sh` installer change, `.vale.ini` change, or new DOC_STYLE style rule was required. The new tool surface is an additive `mcp_tool` class extension covered by the existing classifier path logic.

**2026-06-15 (issue #1169).** The action-multiplexed MCP tool descriptions in `index.js` and `gc-risk-governance.js` gained per-action required-field enumeration, and `gc_risk_governance` create actions gained `reqArg` guards. This is an MCP tool-surface description/validation change; the documentation-coverage classifier, Vale rule set, and `DOC_STYLE.md` style rules are unchanged.

**2026-05-29 (issue #721 GC-T014 NIST SP 800-30 assessment).** Added a new `gc_analyze` kind `nist_assessment` (registered in `mcp/ground-control/index.js`) and the matching `analyzeNistAssessment` helper in `mcp/ground-control/lib.js`. Extended `OPAQUE_VALUE_KEYS` in `lib.js` with methodology-defined value-bag keys (`inputFactors` / `computedOutputs` / `uncertaintyMetadata` / `inputSchema` / `outputSchema` / `treatmentStrategyVocabulary`) so NIST profile-defined inner keys (`threat_event_relevance`, legacy `threat_source_relevance`, `likelihood_initiation`, `likelihood_adverse_impact`, etc.) reach the caller verbatim, per the GC-T014 preflight note. Extended `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY` with four NIST tag enums (`ThreatEventKind`, `ThreatSourceRelevance`, `NistLikelihoodBand`, `NistImpactBand`) so ADR-034's enum-mirror gate covers them. Documentation lives in `docs/API.md` (`GET /api/v1/analysis/grc/nist-sp-800-30`) and the tool description in `mcp/ground-control/index.js`. The 2026-06 source-alignment fix only updates the protected opaque-key examples and keeps the existing classifier behavior. No change to the Vale rule set, `.vale.ini`, or the classifier.

**2026-05-29 (issue #721 follow-on, MCP test regression fix).** The `gc_risk_scenario` FAIR-CRST rename in #720 removed the `threat_source` → `threatSource` and `threat_event` → `threatEvent` entries from `TO_CAMEL` in `mcp/ground-control/lib.js`. The `gc_threat_model` tool still uses those snake_case field names on its public surface (per ADR-034); Jackson was silently dropping the fields on the wire so threat models created via MCP shipped without the threat source or event. Restored both mappings. Also corrected the `gcAuditZodShape` "preserves every backend create body field through Zod parse" test to supply `phases` in the input. Zod by design drops absent optional fields from the parsed object, so the original test was self-defeating. No change to the Vale rule set, `.vale.ini`, the classifier, or `docs/DOC_STYLE.md`.

**2026-05-29 (issue #748 GC-Q010 Threat Modeling Workspace).** The `getThreatModelWorkspace` function was added to `mcp/ground-control/lib.js` as a thin API client for the new `GET /api/v1/threat-models/workspace` endpoint. This is an additive API-client surface (mirrors the pattern for `createThreatModelLink`, `listThreatModelLinks`, etc.); the underlying classifier already covers `mcp/ground-control/lib.js` as a surface, so no classification update is needed. No change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-05-29 (issue #719 GC-T012 multi-framework risk terminology crosswalk).** Added the `NORMALIZED_CONCEPTS` and `CROSSWALK_VOCABULARY_SURFACES` constant arrays to `mcp/ground-control/lib.js` mirroring the two new Java enums (`NormalizedConcept`, `CrosswalkVocabularySurface`) on `MethodologyProfile`. Extended `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY` with the two new rows so ADR-034's enum-mirror gate covers them. Extended the `gc_risk_governance` `methodology_profile` Zod shape with an optional `crosswalk_entries` array. Documentation lives in `docs/API.md` (`MethodologyProfileRequest` / `CrosswalkEntry` field reference) and `docs/architecture/ARCHITECTURE.md` (`MethodologyProfile` aggregate section). These are config-parser, policy-inventory, and MCP-adapter surfaces; no change to the Vale rule set, `.vale.ini`, the classifier, or `docs/DOC_STYLE.md`.

**2026-05-29 (issue #747 GC-Q009 Risk Scenario Workspace).** The `getRiskScenarioWorkspace` function was added to `mcp/ground-control/lib.js` as a thin API client for the new `GET /api/v1/risk-scenarios/workspace` endpoint, and the `gc_risk_scenario_workspace` tool was registered in `mcp/ground-control/index.js`. These are additive API-client and tool-registration surfaces; the underlying classifier already covers `mcp/ground-control/lib.js` and `mcp/ground-control/index.js`. No change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-06-13 (issue #749 GC-Q011 Control Assurance Workspace).** The `getControlAssuranceWorkspace` function was added to `mcp/ground-control/lib.js` as a thin API client for the new `GET /api/v1/controls/workspace` endpoint, and the `gc_control_assurance_workspace` tool was registered in `mcp/ground-control/index.js`. These are additive API-client and tool-registration surfaces. Documentation lives in `docs/API.md`, `mcp/ground-control/README.md`, and `docs/architecture/ARCHITECTURE.md`; the classifier already covers the MCP trigger paths. No change to the Vale rule set, `.vale.ini`, the classifier, or `docs/DOC_STYLE.md`.

**2026-06-15 (issue #1173).** Corrected the gc_risk_governance methodology_profile and verification_result MCP create/update field allowlists in lib.js (and the Zod shape + reqArg guards in gc-risk-governance.js) to match the backend DTOs. This is an MCP config-parser/tool-surface fix; the documentation-coverage classifier, Vale rule set, and DOC_STYLE.md style rules are unchanged.

**2026-06-13 (issue #750 GC-Q012 Evidence and State Explorer).** The `getEvidenceStateWorkspace` function was added to `mcp/ground-control/lib.js` as a thin API client for the new `GET /api/v1/evidence-state/workspace` endpoint, and the `gc_evidence_state_workspace` tool was registered in `mcp/ground-control/index.js`. These are additive API-client and tool-registration surfaces. Documentation lives in `docs/API.md`, the tool description in `mcp/ground-control/index.js`, and the workspace architecture entry in `docs/architecture/ARCHITECTURE.md`; the classifier already covers the MCP trigger paths. No change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-05-30 (issue #1058 traceability + post-merge close gate at MCP tool layer).** Added `runAssertTraceabilityReconciled` and `runCloseIssueAfterMerge` to `mcp/ground-control/lib.js`, registered the matching `gc_assert_traceability_reconciled` and `gc_close_issue_after_merge` MCP tools in `mcp/ground-control/index.js`, and extended `runPostFinalReport` to refuse without the `traceability_reconciled` phase marker. Added `run_traceability_reconciliation_gate_contract` to `tools/policy/checks.py` as the prose-side guardrail for the four anchor files (`skills/implement/SKILL.md`, `skills/implement/steps/step-17-verify.md`, `skills/implement/steps/step-19-final-report.md`, `skills/implement/steps/step-20-close-issue-on-merge.md`). The tool documentation lives in the tool descriptions in `mcp/ground-control/index.js` and in the skill prose under `skills/implement/`. These are MCP-adapter, config-parser, and policy surfaces; no change to the Vale rule set, the `tools/install-vale.sh` installer, the `.vale.ini` configuration, or `docs/DOC_STYLE.md`.

**2026-06-10 (issue #1099 threat/risk screening gate).** Added `runPostGrcScreening` to `mcp/ground-control/lib.js` and registered the matching `gc_post_grc_screening` MCP tool in `mcp/ground-control/index.js`. The tool implements the `/implement` Step 3.5 GRC screening gate per ADR-057, posting a schema-versioned durable record to the GitHub issue thread. Updated `skills/implement/SKILL.md` to include the new step in the step-list table and added `docs/WORKFLOW.md` to document the screening gate in Phase 3 (step 4 in the numbered development loop). Added a new step file `skills/implement/steps/step-03.5-grc-screening.md` with the step contract, verdicts, and decision logic. These are MCP-adapter, config-parser, and skill-prose surfaces; no change to the Vale rule set, the `.vale.ini` configuration, the doc-coverage classifier, or `docs/DOC_STYLE.md`.
**2026-06-10 (SonarCloud gate remediation for #1085).** The dev->main SonarCloud cleanup refactored internals of `mcp/ground-control/lib.js` (split `runAssertTraceabilityReconciled` and `runCloseIssueAfterMerge` into module-scope helpers to lower cognitive complexity, and added a null-deref guard) and applied behavior-preserving smell fixes in `mcp/ground-control/index.js`. The documentation-coverage classifier, its surface set, the thresholds, the Vale rule set, and `.vale.ini` are unchanged; this is a behavior-preserving refactor of code the classifier already covers, so no classification update is needed.

**2026-06-11 (issue #1101 quality-gate evaluation at the completion gate).** Added `runAssertQualityGates` (and its pure `buildQualityGateAssertion` transform) to `mcp/ground-control/lib.js` and registered the matching `gc_assert_quality_gates` MCP tool in `mcp/ground-control/index.js`. The tool wraps the existing server-side `QualityGateService.evaluate` contract and is wired into the `/implement` completion gate (Step 6) so a failing project quality gate blocks the run; documentation lives in `docs/DEVELOPMENT_WORKFLOW.md`, `docs/WORKFLOW.md`, and `skills/implement/steps/step-06-completion-gate.md`. A new `COVERAGE`/`metricParam=DOCUMENTS` quality gate ("Active DOCUMENTS Coverage") was also declared in `tools/ground_control/policy.json`. **That gate measures requirement-link documentation coverage (the share of ACTIVE requirements carrying a `DOCUMENTS` traceability link) and is distinct from ADR-054's documentation-coverage classifier gate, which classifies changed file paths and runs Vale; the two must not be conflated.** These are MCP-adapter, policy, and skill-prose surfaces; the doc-coverage classifier, its surface set, the thresholds, the Vale rule set, the `tools/install-vale.sh` installer, and `.vale.ini` are unchanged. No new `docs/DOC_STYLE.md` style rule is established; only the workflow-gate tool-enumeration example list there is extended to name `gc_assert_quality_gates`.

**2026-06-13 (issue #1156 final-report outcome and next-issue recommendation).** Added the `plain_english_outcome` final-report field to `mcp/ground-control/lib.js` and `mcp/ground-control/index.js`, with `/implement` requiring it and `/quickfix` leaving it optional. Also extended `gc_close_issue_after_merge` to return `next_issue_recommendation` or an explicit no-recommendation/failure reason after a merge-verified close succeeds. The matching prose anchors live in `docs/DEVELOPMENT_WORKFLOW.md`, `docs/WORKFLOW.md`, ADR-021, ADR-029, ADR-036, and the implement/quickfix skill prose; `tools/policy/checks.py` keeps those workflow surfaces synced. These are MCP-adapter and workflow-policy changes. The documentation-coverage classifier, Vale rule set, installer, and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md` style rule is established.

**2026-06-13 (issue #1114 GC-GRC-001 derivation adapter port).** Added the `/api/v1/derivations` REST surface, the `gc_derivation` MCP adapter, derivation API helpers in `mcp/ground-control/lib.js`, and the `/api/v1/derivations` read prefix in `gc_query`. Documentation lives in `docs/API.md` for the REST schema, `mcp/ground-control/README.md` for the tool catalog and query allowlist, and the `gc_derivation` adapter description for the MCP action contract. These are public API, MCP-adapter, and config-parser surfaces. The doc-coverage classifier, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md` style rule is established.

**2026-06-13 (issue #1155 CI strictness baseline).** Added `run_ci_strictness_contract` to `tools/policy/checks.py` so `make policy` verifies the CI strictness surfaces: selected pre-commit hygiene and secret-scan hooks run in the CI policy job, the Sonar job waits for the quality gate and invokes `tools/sonar/assert_no_new_issues.py`, and `.github/branch-protection-baseline.json` records strict required checks for `main` and `dev` with admin bypass retained. Documentation lives in `docs/DEVELOPMENT_WORKFLOW.md` and `tools/sonar/README.md`. This is a policy-surface extension; the doc-coverage classifier, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged. No new `docs/DOC_STYLE.md` style rule is established.

**2026-06-14 (issue #1102 documentation coverage status-hole closure).**
The active `DOCUMENTS` coverage gate now has two status-independent
enforcement points. First, `/implement` Step 6 calls
`gc_assert_quality_gates` with the issue's `in_scope_requirements[]`; when an
enabled `COVERAGE` gate with `metricParam=DOCUMENTS` and
`scopeStatus=ACTIVE` exists, the tool verifies each in-scope requirement has a
`DOCUMENTS` traceability link regardless of DRAFT or ACTIVE status. Second,
`RequirementService` rejects DRAFT-to-ACTIVE transitions for requirements
missing a `DOCUMENTS` link while that gate is active, including per-item
failures in bulk transition. This closes the prior escape where keeping a
requirement DRAFT kept it out of active-status project coverage until after
the completion gate had already passed. The backend uses the existing
quality-gate and traceability repositories; no new coverage schema, endpoint,
or frontend-only validation layer was added.

**2026-06-14 (issue #689 GC-Q003 Traceability Matrix).** The `getTraceabilityMatrix` function was added to `mcp/ground-control/lib.js` as a thin API client for the new `GET /api/v1/requirements/matrix` endpoint, and the `gc_traceability_matrix` read tool was registered in `mcp/ground-control/index.js`. These are additive API-client and tool-registration surfaces. Documentation lives in `docs/API.md` and the tool description in `mcp/ground-control/index.js`; the classifier already covers the MCP trigger paths. The doc-coverage classifier, Vale rule set, `tools/install-vale.sh` installer, and `.vale.ini` configuration are unchanged; no new `docs/DOC_STYLE.md` style rule is established.

**2026-06-14 (next-issue recommendation skips umbrella/tracking issues).**
Refined the `gc_close_issue_after_merge` next-issue recommendation in
`mcp/ground-control/lib.js` so an umbrella or tracking issue is never handed
back as the next thing to pick up after a merge-verified close. The new pure
helpers `isUmbrellaNextIssueCandidate` and `selectNextIssueRecommendation` drop
a candidate when it carries an `epic`/`umbrella`/`tracking`/`meta` marker
label, a `Tracking:`/`Epic:`/`Umbrella:` or bracketed title prefix,
GitHub-native sub-issues (`sub_issues_summary.total > 0`), or a body task list
that checks off five or more child issues. The task-list threshold separates a
coordinating tracking issue (dozens of issue-referencing checkboxes) from a
leaf requirement issue (a handful of acceptance-criteria checkboxes that
reference no issues). This refines the credible-next-issue filter added for
#1156; the matching prose anchor is the recommendation source description in
`skills/implement/steps/step-20-close-issue-on-merge.md`, and the changelog
fragment records the temporal change. These are MCP-adapter and
workflow-policy changes. The documentation-coverage classifier, its surface
set, the thresholds, the Vale rule set, the `tools/install-vale.sh` installer,
and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md` style rule is
established.

**2026-06-14 (issue #1103 Phase D consolidation).** Added `runAssertCompletion` to `mcp/ground-control/lib.js` and registered `gc_assert_completion` in `mcp/ground-control/index.js`. Updated `tools/policy/checks.py` to point the traceability-gate contract check at the consolidated `step-17-completion.md` surface (now requiring `gc_assert_completion`, `traceability_reconciled`, and `plain_english_outcome`). These are MCP-adapter and policy-surface changes. The documentation-coverage classifier, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md` style rule is established.

**2026-06-14 (issue #1104 MCP tool-usage telemetry).** Added the internal
handler-boundary telemetry wrapper `installToolTelemetry` and the `err()`
`_meta` outcome-code channel to `mcp/ground-control/index.js`, and an
admin-token routing entry for the aggregate read path to `requiresAdminRole`
in `mcp/ground-control/lib.js`. The new `McpTelemetryController` exposes
`POST /api/v1/mcp-tool-usage/events` (capture, any authenticated session) and
`GET /api/v1/mcp-tool-usage` (aggregate, ROLE_ADMIN gated in `ApiPathMatrix`
because it exposes cross-project operational telemetry); the read prefix is
added to the `gc_query` allowlist (`gc-query.js`, `mcp/ground-control/README.md`,
ADR-035). Documentation lives in `docs/API.md`, ADR-059, and the changelog
fragment. Capture is internal to the adapter (no new public `gc_*` tool is
registered), so the doc-coverage classifier surface set, the Vale rule set, the
`tools/install-vale.sh` installer, and `.vale.ini` are unchanged; the
`docs/DOC_STYLE.md` MCP-shape-extensions list is extended to record the surface
addition. No new `docs/DOC_STYLE.md` style rule is established.

**2026-06-16 (issue #723 GC-T011 Open FAIR quantitative risk analysis).** Added
`analyzeFairQuantitative` adapter helper to `mcp/ground-control/lib.js` and
registered `fair_quantitative` in the `ANALYZE_KINDS` array and `gc_analyze`
tool description in `mcp/ground-control/index.js`. The backend surface is
`GET /api/v1/analysis/grc/fair-quantitative` (documented in `docs/API.md`).
This follows the `gc_analyze`-kind extension pattern already established for
`nist_assessment` (GC-T014 / #721) and recorded in `docs/DOC_STYLE.md §
MCP-shape-extensions`. The documentation-coverage classifier, Vale rule set,
`tools/install-vale.sh`, and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md`
style rule is established.

**2026-06-18 (issue #1181 model-tier refresh).** The `mcp/ground-control/lib.js`
change in this commit bumps the `CLAUDE_MODEL_BY_TIER.high` routing-default
constant from `claude-opus-4-7` to `claude-opus-4-8` (with the matching
high-tier `.ground-control.yaml` stages `planning` and `review_cycle_1_consume`).
This is a one-line routing-default model-id change, not a documentation-coverage
gate surface: the `run_documentation_coverage_check` classifier, the Vale rule
set, `tools/install-vale.sh`, and `.vale.ini` are unchanged, and no new
`docs/DOC_STYLE.md` style rule is established.

**2026-06-18 (issue #1181 telemetry consistency fields).** A second
`mcp/ground-control/lib.js` change under #1181 adds `expected_model` and
`model_matches_expected` to the `/implement` step-telemetry record (schema
bumped to `gc.implement.telemetry/v2`), documented in ADR-036's telemetry
contract. This is an internal telemetry-record field addition, not a
documentation-coverage gate surface: the `run_documentation_coverage_check`
classifier, the Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are
unchanged, and no new `docs/DOC_STYLE.md` style rule is established.

**2026-06-20 (issue #266 GC-H006 threat-control mapping).** Extended
`gc_risk_control_mapping` in `mcp/ground-control/index.js` and
`mcp/ground-control/lib.js` to support `ThreatModel` as a third analysis-side
endpoint. Changes: (1) `threat_model_id` added to the Zod schema as an optional
UUID field; (2) `"unmapped-threats"`, `"threat-unmapped-controls"`, and
`"threats-insufficient-effectiveness"` added to `RISK_CONTROL_MAPPING_ACTIONS`;
(3) three matching query params (`min_effectiveness`, `as_of`,
`freshness_window_days`) added; (4) `getUnmappedThreats`,
`getThreatUnmappedControls`, and `getThreatsInsufficientEffectiveness` helper
functions added to `lib.js`; (5) `threat_model_id` threaded through the
`create` action body. These are additive extensions to an existing
`gc_risk_control_mapping` action-multiplexed tool; the underlying classifier
already covers both MCP trigger paths. Documentation lives in `docs/API.md`
and `docs/architecture/ARCHITECTURE.md`. The doc-coverage classifier, Vale rule
set, `tools/install-vale.sh`, and `.vale.ini` are unchanged; no new
`docs/DOC_STYLE.md` style rule is established.

**2026-06-20 (issue #763 GC-I004 continuous compliance monitoring).** Added
`analyzeComplianceMonitoring` adapter helper to `mcp/ground-control/lib.js` and
registered `continuous_compliance_monitoring` in the `ANALYZE_KINDS` array and
`gc_analyze` tool description in `mcp/ground-control/index.js`. The backend
surface is `GET /api/v1/analysis/grc/compliance-monitoring` (documented in
`docs/API.md`). This follows the `gc_analyze`-kind extension pattern already
established for `fair_quantitative` (GC-T011 / #723) and recorded in
`docs/DOC_STYLE.md § MCP-shape-extensions`. The documentation-coverage
classifier, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are
unchanged; no new `docs/DOC_STYLE.md` style rule is established.

**2026-06-21 (issue #746 GC-I017 FAIR-CAM control analytics).** Added
`analyzeFairCamControlAnalytics` adapter helper to `mcp/ground-control/lib.js` and
registered `fair_cam_control_analytics` in the `ANALYZE_KINDS` array and
`gc_analyze` tool description in `mcp/ground-control/index.js`. The backend
surface is `GET /api/v1/analysis/grc/fair-cam-control-analytics` (documented in
`docs/API.md`). This follows the `gc_analyze`-kind extension pattern established
for `fair_quantitative` (GC-T011 / #723), `continuous_compliance_monitoring`
(GC-I004 / #763), and recorded in `docs/DOC_STYLE.md § MCP-shape-extensions`.
The documentation-coverage classifier, Vale rule set, `tools/install-vale.sh`,
and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md` style rule is
established. Pre-push review follow-up refined the same MCP surfaces only: the
`gc_analyze` tool description and the `analyzeFairCamControlAnalytics` helper
comment now note that the FAIR-CAM scope filters compose as an intersection and
that `methodology_profile_id` is an applied filter. No new kind, endpoint, or
style rule; `docs/API.md` carries the matching contract update.

**2026-06-20 (issue #1194 dev-start plan gate).** Added the optional `workflow.dev_start_gate` parser to `mcp/ground-control/lib.js`, wired `gc_post_implementation_plan` to refuse invalid enabled gate sections before posting a plan marker, and extended `gc_render_pr_body` in `mcp/ground-control/index.js` with an optional `dev_start_gate` Markdown section. The workflow contract lives in `skills/implement/steps/step-04-planning.md` and `skills/implement/steps/step-09-pr-body.md`; the tool descriptions and parser validation are the MCP surface. These are workflow, MCP-adapter, and config-parser surfaces; no change to the Vale rule set, the `tools/install-vale.sh` installer, or the `.vale.ini` configuration.

**2026-06-21 (issue #1167 controller @WebMvcTest mapping by FQCN).** Rewrote `run_controller_contracts` in `tools/policy/checks.py` (and the parallel `ControllerPolicyTest` ArchUnit-style test) to resolve a controller's `@WebMvcTest` companion by the controller's fully qualified class, derived from its repo path and matched against each test's `@WebMvcTest(...)` annotation resolved through that file's `import`, instead of the controller's bare filename stem. The stem heuristic collided on same-named controllers in different packages (`api/audit/AuditController` versus `api/audits/AuditController`), causing a false `controller-webmvctest-update` failure and letting the wrong test spuriously satisfy the check. The `controller-webmvctest-update`, `controller-webmvctest-missing`, and `controller-webmvctest-annotation` codes are unchanged. The parser matches dotted Java identifiers and strips the `.class` suffix in code so the regular expressions stay linear-time (no super-linear backtracking, Sonar S8786). The documentation-coverage classifier (`classifyChangedSurface`), `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md` style rule is established.

**2026-06-22 (issue #963 gc_assert_completion phase parameter).** Added a `phase` parameter (`"pre_merge"` | `"post_merge"`, default `"post_merge"`) to the `gc_assert_completion` MCP tool surface in `mcp/ground-control/index.js` and threaded it through `runAssertCompletion` / `runPostFinalReport` / `buildFinalReport` in `mcp/ground-control/lib.js` (post-merge merge-gate; pre-merge readiness record). This is an MCP-adapter surface change; per the `docs/DOC_STYLE.md` "MCP tool surface" convention the addition is recorded in this ADR and the `changelog.d/963.changed.md` fragment, and the required agent behavior lives in `skills/implement/steps/step-17-completion.md`. The documentation-coverage classifier (`classifyChangedSurface`), `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md` style rule is established.

**2026-06-23 (issue #1197 Requirement UID identity).** Extended `mcp/ground-control/lib.js` and `mcp/ground-control/index.js` with two additive MCP surface changes: (1) `gc_requirement` create now accepts `uid_prefix` as a mutually exclusive alternative to `uid`, with `uid_prefix` added to the `TO_CAMEL` map and the `ENTITY_FIELDS` allowlist; (2) `gc_get_traceability_by_artifact` and the internal `checkOrphanedIssueLinks` helper now accept an optional `project` parameter forwarded as a `?project=` query string to scope the reverse lookup. Documentation lives in `docs/API.md`, `mcp/ground-control/README.md`, `architecture/adrs/060-requirement-uid-identity.md`, `docs/architecture/ARCHITECTURE.md`, and `docs/DEVELOPMENT_WORKFLOW.md`. The documentation-coverage classifier (`classifyChangedSurface`), `outcome_required` mapping, Vale rule set, `tools/install-vale.sh`, and `.vale.ini` are unchanged; no new `docs/DOC_STYLE.md` style rule is established. Review-fix refinement (same issue, same date): the `gc_get_traceability_by_artifact` tool description and the `getTraceabilityByArtifact` helper comment were clarified to state the reverse lookup is *always* project-scoped: `RequirementController` resolves a single project (or fails with `project_required` in a multi-project instance) and the service no longer falls back to an unscoped query; `docs/API.md` carries the matching contract wording.
