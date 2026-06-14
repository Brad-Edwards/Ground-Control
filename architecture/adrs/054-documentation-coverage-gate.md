# ADR-054: Documentation coverage gate

## Status

accepted

## Date

2026-05-23

> **Style sync for issue #751 (2026-06-14):** Repository-wide Vale cleanup normalized punctuation in documentation prose. This ADR's documentation coverage gate stays the same.

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

**2026-05-26 (issue #989).** The `gc_integration_manager` MCP tool (`mcp/ground-control/gc-integrate.js`) and the `gc_integration_manager` entry in `mcp/ground-control/index.js` are new tool surfaces added under this issue. The doc-coverage gate (`doc-coverage-gate-sync` policy rule) triggers on changes to `mcp/ground-control/lib.js` and `mcp/ground-control/index.js`; the tool's documentation lives in `mcp/ground-control/README.md § gc_integration_manager` and `docs/DEVELOPMENT_WORKFLOW.md § /integrate`. No change to the Vale rule set, the `tools/install-vale.sh` installer, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-05-26 (issue #989 follow-up).** Fixed a wrapper-layer regression where `gc_render_pr_body` and `gc_post_final_report` did not propagate the optional `documentation_outcome` field. The Zod input schemas omitted the field and the destructure-and-call did not forward it, so the renderer never emitted the `## Documentation` section that this ADR's policy gate requires. Both wrappers now accept `documentation_outcome` (object with `outcome` enum and optional `rationale`) and pass it through to `runRenderPrBody` / `runPostFinalReport`. Unit tests in `lib.test.js::runRenderPrBody` cover the three rendering paths and the omission case.

**2026-05-26 (issue #989 merge carve-out).** The `lib.js` change in this commit adds `INTEGRATION_MANAGER_MERGE_STRATEGIES` and extends `normalizeIntegrationManagerConfig` with the `merge_strategy` field. These changes are to the integration manager config parser, not to any documentation coverage gate surface. No change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` is required.

**2026-05-26 (issue #989 SDK schema hotfix).** Re-registered `gc_integration_manager` via `server.tool(name, desc, zodShape, handler)` so the SDK's `safeParseAsync` path resolves; the prior `server.registerTool({inputSchema: <raw JSON Schema>})` form crashed every invocation with `v3Schema.safeParseAsync is not a function`. The fix touches `mcp/ground-control/index.js` only; no change to the Vale rule set, the `.vale.ini` configuration, the doc-coverage classifier, or any documentation target surface.

**2026-05-28 (issue #720 FAIR risk scenario refactor).** The `gc_risk_scenario` MCP tool field renames (`threat_source`→`threat`, `threat_event`→`method`, `affected_object`→`asset`, `consequence`→`effect`) required updating the `TO_CAMEL` mapping in `mcp/ground-control/lib.js` to remove obsolete snake_case bindings and add the new derived field `fair_sentence` mapping. Additionally, `tools/policy/checks.py` was updated to recognize `mcp/ground-control/gc-risk-scenario.js` as a valid MCP-adapter file (alongside `gc-risk-governance.js`) for the `controller-parity` policy check. These are config-parser and policy surfaces; no change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-06-11 (issue #1100 GRC reconciliation gate).** The `gc_assert_grc_reconciled` MCP tool is registered in `mcp/ground-control/index.js` and implemented in `mcp/ground-control/lib.js`. The doc-coverage gate triggers on these paths; the tool's documentation lives in `docs/DEVELOPMENT_WORKFLOW.md` (GRC reconciliation gate row added to the per-step optimization table) and `skills/implement/steps/step-17-verify.md` (Step 6 added for `gc_assert_grc_reconciled`). The tool-enumeration example list in `docs/DOC_STYLE.md` was extended to name `gc_assert_grc_reconciled` (and a pre-existing duplicated paragraph there consolidated); no new Vale rule, `tools/install-vale.sh` installer change, `.vale.ini` change, or new DOC_STYLE style rule was required. The new tool surface is an additive `mcp_tool` class extension covered by the existing classifier path logic.

**2026-05-29 (issue #721 GC-T014 NIST SP 800-30 assessment).** Added a new `gc_analyze` kind `nist_assessment` (registered in `mcp/ground-control/index.js`) and the matching `analyzeNistAssessment` helper in `mcp/ground-control/lib.js`. Extended `OPAQUE_VALUE_KEYS` in `lib.js` with methodology-defined value-bag keys (`inputFactors` / `computedOutputs` / `uncertaintyMetadata` / `inputSchema` / `outputSchema` / `treatmentStrategyVocabulary`) so NIST profile-defined inner keys (`threat_source_relevance`, `likelihood_initiation`, `likelihood_adverse_impact`, etc.) reach the caller verbatim, per the GC-T014 preflight note. Extended `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY` with four NIST tag enums (`ThreatEventKind`, `ThreatSourceRelevance`, `NistLikelihoodBand`, `NistImpactBand`) so ADR-034's enum-mirror gate covers them. Documentation lives in `docs/API.md` (`GET /api/v1/analysis/grc/nist-sp-800-30`) and the tool description in `mcp/ground-control/index.js`. No change to the Vale rule set, `.vale.ini`, the classifier, or `docs/DOC_STYLE.md`.

**2026-05-29 (issue #721 follow-on, MCP test regression fix).** The `gc_risk_scenario` FAIR-CRST rename in #720 removed the `threat_source` → `threatSource` and `threat_event` → `threatEvent` entries from `TO_CAMEL` in `mcp/ground-control/lib.js`. The `gc_threat_model` tool still uses those snake_case field names on its public surface (per ADR-034); Jackson was silently dropping the fields on the wire so threat models created via MCP shipped without the threat source or event. Restored both mappings. Also corrected the `gcAuditZodShape` "preserves every backend create body field through Zod parse" test to supply `phases` in the input. Zod by design drops absent optional fields from the parsed object, so the original test was self-defeating. No change to the Vale rule set, `.vale.ini`, the classifier, or `docs/DOC_STYLE.md`.

**2026-05-29 (issue #748 GC-Q010 Threat Modeling Workspace).** The `getThreatModelWorkspace` function was added to `mcp/ground-control/lib.js` as a thin API client for the new `GET /api/v1/threat-models/workspace` endpoint. This is an additive API-client surface (mirrors the pattern for `createThreatModelLink`, `listThreatModelLinks`, etc.); the underlying classifier already covers `mcp/ground-control/lib.js` as a surface, so no classification update is needed. No change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-05-29 (issue #719 GC-T012 multi-framework risk terminology crosswalk).** Added the `NORMALIZED_CONCEPTS` and `CROSSWALK_VOCABULARY_SURFACES` constant arrays to `mcp/ground-control/lib.js` mirroring the two new Java enums (`NormalizedConcept`, `CrosswalkVocabularySurface`) on `MethodologyProfile`. Extended `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY` with the two new rows so ADR-034's enum-mirror gate covers them. Extended the `gc_risk_governance` `methodology_profile` Zod shape with an optional `crosswalk_entries` array. Documentation lives in `docs/API.md` (`MethodologyProfileRequest` / `CrosswalkEntry` field reference) and `docs/architecture/ARCHITECTURE.md` (`MethodologyProfile` aggregate section). These are config-parser, policy-inventory, and MCP-adapter surfaces; no change to the Vale rule set, `.vale.ini`, the classifier, or `docs/DOC_STYLE.md`.

**2026-05-29 (issue #747 GC-Q009 Risk Scenario Workspace).** The `getRiskScenarioWorkspace` function was added to `mcp/ground-control/lib.js` as a thin API client for the new `GET /api/v1/risk-scenarios/workspace` endpoint, and the `gc_risk_scenario_workspace` tool was registered in `mcp/ground-control/index.js`. These are additive API-client and tool-registration surfaces; the underlying classifier already covers `mcp/ground-control/lib.js` and `mcp/ground-control/index.js`. No change to the Vale rule set, the `.vale.ini` configuration, or `docs/DOC_STYLE.md` itself.

**2026-06-13 (issue #749 GC-Q011 Control Assurance Workspace).** The `getControlAssuranceWorkspace` function was added to `mcp/ground-control/lib.js` as a thin API client for the new `GET /api/v1/controls/workspace` endpoint, and the `gc_control_assurance_workspace` tool was registered in `mcp/ground-control/index.js`. These are additive API-client and tool-registration surfaces. Documentation lives in `docs/API.md`, `mcp/ground-control/README.md`, and `docs/architecture/ARCHITECTURE.md`; the classifier already covers the MCP trigger paths. No change to the Vale rule set, `.vale.ini`, the classifier, or `docs/DOC_STYLE.md`.

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
