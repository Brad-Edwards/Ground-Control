# Documentation style

> **Sync note for issue #1107 (2026-06-14):** The audit-diff API reference (`docs/API.md`) and the `gc_requirement` MCP tool description were reviewed against these rules when the requirement history/timeline `expand` parameter was added. No style rule changed.

> **Sync note for issue #1106 (2026-06-15):** The new MCP write-contract gate docs (`docs/DEVELOPMENT_WORKFLOW.md`, `mcp/ground-control/README.md`, and the ADR-034 amendment) were reviewed against these rules. No style rule changed.

## Rules

Docs describe the system as it ships on the current commit. Write in present
tense. Use active voice. Be concise: remove any sentence that does not add
information the reader needs to understand the feature, architecture, or
contract.

Strip:

- Fluff: restatement of context the reader already has, throat-clearing,
  hedging prose.
- Forward guidance: "future work," "this feature is planned."
- Roadmapping: phase tables, milestone summaries. Roadmaps belong in
  tracking issues.
- Meta-commentary: "this document explains," "the next section covers." If a
  choice needs explaining, the rationale lives in an ADR.

### Em-dash density

Prefer commas, semicolons, periods, or parentheses for clause breaks. Reach
for an em-dash only when the construction genuinely demands the heavier break:
a parenthetical that requires emphasis, or a sharp pivot that a comma or
semicolon cannot carry.

Soft budget: at most one em-dash per paragraph, typically zero. If a paragraph
has two, rewrite one.

Em-dash chains (`X - Y - Z`) should almost always be reordered into separate
clauses.

This pattern was surfaced in shifter #704, where agent-written prose accumulated
56 em-dash occurrences across five documents in a single PR. The
`GoogleProject.EmDashDensity` Vale rule enforces the per-paragraph budget
mechanically at error level; touched docs that exceed the budget fail the
prose-lint gate. See `.vale/styles/GoogleProject/EmDashDensity.yml`.

## Style anchors

- **Voice and tense:** [Google Developer Documentation Style Guide](https://developers.google.com/style).
  Present-tense default, plain English, concision.
- **Structure:** [Diátaxis](https://diataxis.fr/) - every doc is one of
  `tutorial`, `how-to`, `reference`, or `explanation`. Reference and how-to
  docs do not contain roadmaps or meta-commentary by construction.

## Enforcement

Vale with the `errata-ai/Google` package runs on docs touched in the current
diff via `make policy`, the CI `policy` job, and the pre-commit `vale-prose-lint`
hook. The hook installs Vale via `tools/install-vale.sh` on first need; no
manual `make vale-install` step is required.

Changes to any doc-coverage gate surface - `mcp/ground-control/index.js`,
`mcp/ground-control/lib.js`, `tools/policy/checks.py`, `tools/install-vale.sh`,
`.vale.ini`, or this file - trigger the `doc-coverage-gate-sync` rule per
ADR-054, which requires this file and ADR-054 to stay current with the gate
surface they describe.
Adding a new MCP tool or `gc_admin` action does not require new style rules
here unless the action introduces a new doc-shape (a new request/response
schema, for example) - in that case document the schema under the relevant
service section in `docs/API.md`, which Vale lints on touch.

## Scope: whole file on first touch

When a `.md` / `.markdown` file appears in the current diff (added, copied,
modified, or renamed vs the base ref), Vale lints it in its entirety - not just
the changed lines. A one-line edit to a previously untouched document brings
the whole file into scope; all of its style violations must be fixed in that
PR. Untouched docs are not linted.

The model is "ratchet on touch": each touched file becomes permanently
compliant, and the codebase converges as docs are edited in the normal course
of work. There is no line-range or hunk-aware mode, and there is no carve-out
for "I only changed one paragraph"; if you touch a doc, you own its full
style compliance. See ADR-054 for the rationale behind this trade-off.

## Temporal context

ADRs carry the durable *why*. Release notes and the changelog carry temporal
context. Tracking issues carry roadmaps. Reference docs state the current
contract only.

## Operational lane docs

Operational skill lanes (`/integrate`, `/implement`, `/quickfix`) document
their contracts in `docs/DEVELOPMENT_WORKFLOW.md` and in their `SKILL.md`
files. The style rules above apply to those files the same as to any other
touched `.md` file: present tense, active voice, no forward guidance, at most
one em-dash per paragraph. The `/integrate` lane's `mode=merge` extension is
documented in `docs/DEVELOPMENT_WORKFLOW.md § /integrate § Configuration` and
`skills/integrate/SKILL.md § Invocation`; no separate doc surface is required.

Per-PR documentation outcomes are recorded as a `## Documentation` section in
the PR body and the Step 19 final-report comment. Pass the optional
`documentation_outcome` field to `gc_render_pr_body` or `gc_post_final_report`
when the diff touches a classified surface (per ADR-054). The renderer emits
the section automatically; agents do not hand-author it.

MCP tool registrations in `mcp/ground-control/index.js` are sensitive to
schema shape: `server.tool(name, desc, zodShape, handler)` and
`server.registerTool(name, {description, inputSchema: <Zod schema>}, handler)`
both work, but `server.registerTool({inputSchema: <raw JSON Schema>})` passes
the registration gate and crashes every call with
`v3Schema.safeParseAsync is not a function`. New tools should match the
`server.tool` pattern used by the bulk of the file.

## MCP shape extensions and policy updates are not doc edits

Additive Zod schema fields or new entries in `mcp/ground-control/lib.js`'s
`TO_CAMEL` map (for example, the typed reassessment-trigger shape added for
GC-T004 / C8 in issue #863, or field renames in the `gc_risk_scenario` tool)
do not by themselves require new reference-doc prose. The MCP tool description
string in the corresponding adapter file (for example, `gc-risk-governance.js` or
`gc-risk-scenario.js`) is the contract surface; keep it accurate when adding or
removing fields, and the changelog fragment in `changelog.d/` carries the
temporal record. Similarly, updates to `tools/policy/checks.py` that extend
the list of recognized adapter files (for example, adding `gc-risk-scenario.js`
to the controller-parity check) are policy-surface changes recorded in
amendments to ADR-054, not documentation edits.

New `gc_analyze` kinds backed by a fixed REST endpoint follow the same
convention: a new kind value in `ANALYZE_KINDS` (for example
`nist_assessment` added for GC-T014 / #721; `fair_quantitative` added for
GC-T011 / #723) plus an adapter helper in `lib.js` is documented by the
endpoint entry in `docs/API.md` and the `gc_analyze` tool description string
in `mcp/ground-control/index.js`. No separate user-facing prose page per
kind, no new sections in this style guide.

Per-action required-field enumeration in an action-multiplexed tool's description string (issue #1169) is a contract-surface edit to that tool's description, not a new doc page.

Mirrored API-boundary enum constants follow the same convention: the
`NORMALIZED_CONCEPTS` and `CROSSWALK_VOCABULARY_SURFACES` arrays added to
`mcp/ground-control/lib.js` for GC-T012 / #719 mirror two new Java enums on
`MethodologyProfile` and are documented by the methodology profile entry in
`docs/API.md` and the `gc_risk_governance` tool description in
`mcp/ground-control/index.js`. The static `ENUM_CONTRACT_INVENTORY`
extension in `tools/policy/checks.py` enforces parity across backend, MCP,
and frontend per ADR-034. No new sections in this style guide.

Restoring a `TO_CAMEL` mapping in
`mcp/ground-control/lib.js` (for example, the `threat_source` /
`threat_event` entries the `gc_risk_scenario` rename in #720 dropped but
the `gc_threat_model` tool still needs on its public surface) is a
config-parser fix: the contract surface that names the snake_case fields
is the tool's adapter file (`gc-threat-model.js` or sibling), and the
amendment record lives in ADR-054. No new sections in this style guide.

New API client functions added to `mcp/ground-control/lib.js` (for example,
`getThreatModelWorkspace` for GC-Q010, `getRiskScenarioWorkspace` for GC-Q009,
`getControlAssuranceWorkspace` for GC-Q011, `getEvidenceStateWorkspace` for
GC-Q012, or `getTraceabilityMatrix` for GC-Q003) that directly mirror backend
endpoints follow the same pattern: record the surface addition in the ADR-054
amendment and the changelog fragment; no new DOC_STYLE.md prose is needed unless
a new style rule is being established. The matching `gc_traceability_matrix` read
tool for GC-Q003 mirrors the new `GET /api/v1/requirements/matrix` endpoint and
needs no new style rule.

The GC-GRC-001 derivation API helpers and `gc_derivation` MCP tool follow the
same convention: `docs/API.md` documents `/api/v1/derivations`, the adapter
description documents the MCP action contract, and the changelog fragment
records the temporal change. No new style rule is established here.

CI strictness policy checks in `tools/policy/checks.py` follow the same
documentation pattern: `docs/DEVELOPMENT_WORKFLOW.md` documents the current
merge-gate contract, `tools/sonar/README.md` documents Sonar-specific helper
scripts, and ADR-054 records the policy-surface amendment. No new style rule
is established here.

New /implement workflow-gate MCP tools or fields added to
`mcp/ground-control/lib.js` and `mcp/ground-control/index.js` are documented by
the tool description strings in `index.js` and the skill prose under
`skills/implement/`. Examples include `gc_assert_traceability_reconciled` and
`gc_close_issue_after_merge` for GC-O007 / #1058, `gc_post_grc_screening` for
GC-O012 / #1099, `gc_assert_grc_reconciled` for #1100,
`gc_assert_quality_gates` for #1101, and `plain_english_outcome` /
`next_issue_recommendation` for #1156. The matching policy check in
`tools/policy/checks.py` is the prose-side guardrail. The surface addition is
recorded in the ADR-054 amendment and the changelog fragment; no new
DOC_STYLE.md prose is needed unless a new style rule is being established.

Extensions to existing /implement workflow-gate MCP tools follow the same
documentation pattern. The #1102 `gc_assert_quality_gates` extension adds
PR-scoped `DOCUMENTS` traceability enforcement for in-scope requirements, and
the backend DRAFT-to-ACTIVE transition rule enforces the same requirement-link
contract. The current behavior is documented in `docs/DEVELOPMENT_WORKFLOW.md`,
`docs/API.md`, and `skills/implement/steps/step-06-completion-gate.md`, with
the durable rationale in ADR-054. No new DOC_STYLE.md style rule is established.

The 2026-06-10 SonarCloud remediation (#1085) refactored `mcp/ground-control/lib.js` and `index.js` internals without changing any prose style rule or documented-surface classification; no new DOC_STYLE.md rule is established.

The next-issue recommendation refinement (umbrella/tracking exclusion) follows
the same documentation pattern. `gc_close_issue_after_merge` now skips
umbrella/tracking issues when it picks the issue to recommend after a
merge-verified close. The current behavior is documented in the recommendation
source description in `skills/implement/steps/step-20-close-issue-on-merge.md`,
with the durable rationale in the ADR-054 amendment and the temporal change in
the changelog fragment. This refines an existing workflow-gate tool and touches
no documentation-coverage surface; no new DOC_STYLE.md style rule is
established.

The 2026-06-14 Phase D consolidation (#1103) added `gc_assert_completion` to `mcp/ground-control/lib.js` and `index.js`, updated `tools/policy/checks.py` for the consolidated Step 17 surface, and reorganized the /implement SKILL step prose. No new DOC_STYLE.md style rule is established.

The MCP tool-usage telemetry capture (#1104 / ADR-059) follows the same
documentation pattern. It adds an internal handler-boundary instrumentation
wrapper (`installToolTelemetry`) in `mcp/ground-control/index.js`, an
admin-token routing entry for the aggregate read in
`mcp/ground-control/lib.js`, and the new `McpTelemetryController` endpoints
documented in `docs/API.md`. Capture is internal to the adapter (no new
public `gc_*` tool is registered), so the doc-coverage classifier surface set
is unchanged. The surface addition is recorded in the ADR-054 amendment and
the changelog fragment; no new DOC_STYLE.md style rule is established.

Correcting a `GOVERNANCE_FIELDS` create/update allowlist in `mcp/ground-control/lib.js` to match the backend DTO (issue #1173) is a config-parser fix recorded in an ADR-054 amendment, not a new doc page.

Bumping the `CLAUDE_MODEL_BY_TIER.high` routing-default model id in `mcp/ground-control/lib.js` from `claude-opus-4-7` to `claude-opus-4-8` (issue #1181) is a constant change recorded in an ADR-054 amendment, not a new doc page or style rule.
