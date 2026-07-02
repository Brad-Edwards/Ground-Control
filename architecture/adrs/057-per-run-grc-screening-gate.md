# ADR-057: Per-run GRC screening gate in /implement

## Status

accepted

## Date

2026-06-10

> **Amended and superseded in part by ADR-058 (2026-06-12):** This ADR remains
> the v1 screening-record decision and the historical basis for Step 3.5, but
> ADR-058 replaces the long-term target contract. The v2 GRC program computes
> screening from derived facts as `impact_set`, `gap_set`, and `stale_set`.
> A missing threat-model baseline no longer produces a passing `no_baseline`
> declination; it creates a scoped gap set that must be modeled, controlled, or
> dispositioned before completion. `not_security_relevant` becomes a derived
> empty-impact/empty-gap result rather than an agent assertion.

## Context

The June 6 redesign revert (issue #1089) demonstrated that prose-only workflow gates can be short-circuited - an agent can skip a prose instruction without any tooling enforcement. The GRC-in-the-loop requirement (GC-O012) mandates that every `/implement` run classify its change surface against the project's threat/risk workspaces before planning, but a prose instruction alone carries no enforcement weight.

Additionally, the existing `/implement` workflow has no structured record of whether a given run was screened for security relevance. Post-run assertions (issue #1100) need a machine-parseable record to verify that the gate ran and what verdict it produced.

## Decision

Introduce a dedicated screening step (Step 3.5, stage id `grc_screening`) in the `/implement` workflow that runs between codebase assessment (Step 3) and planning (Step 4).

**Verdict taxonomy.** The step records one of three verdicts:
- `security_relevant`: the change touches a security-relevant surface; threat-model entries, risk scenarios, controls, and `targetType=CODE` links were created, updated, or confirmed during the run.
- `not_security_relevant`: the change does not touch a security-relevant surface; a one-line rationale is required (no silent skip).
- `no_baseline`: the project has no threat-model baseline; this is an explicit declination, not a clean verdict.

**Marker family.** The record is posted to the issue thread via `gc_post_grc_screening` using marker family `<!-- gc:grc-screening ... -->`, distinct from `gc:phase`, `gc:decision-record`, and `gc:final-report`, so downstream sweeps and the companion server-side assertion (issue #1100) can detect the gate without ambiguity.

**Schema-versioned record.** The record carries `schema: gc.implement.grc-screening/v1` and structured fields for `verdict`, `rationale`, entity refs (`entities_created`, `entities_updated`, `entities_confirmed`), and `code_links`. This shape lets issue #1100 verify reconciliation without scraping free prose, and lets future verdict extensions or entity families be added by extending the typed-ref enum.

**Renderer-tool boundary (per ADR-036).** Only the `gc_post_grc_screening` MCP tool posts the durable record via argv-shaped `gh api`. Skill steps must not use `gh issue comment`, `curl`, or raw GitHub API calls for this record. The tool validates, renders, runs the sensitive-content filter and body-size cap, and writes the `grc_screening` phase marker on success.

**Reserved-marker injection guard.** Caller-controlled fields (rationale, entity UIDs, code_link target identifiers) are checked for reserved `<!-- gc:` marker sequences before any network I/O, consistent with the `runPostDecisionRecord` pattern.

**Routing.** The `grc_screening` stage is added to `DEFAULT_IMPLEMENT_ROUTING_STAGES` (tier: medium) and to `.ground-control.yaml` routing.stages.

**Completion assertion (issue #1100).** The companion assertion is `gc_assert_grc_reconciled`, a deterministic MCP tool consumed by `/implement` Step 17 after the existing Ground Control traceability verification. It verifies the canonical `gc:grc-screening` record rather than introducing a second screening schema. For `security_relevant`, it re-fetches the named threat-model entries, risk scenarios, controls, and their `targetType=CODE` links from the Ground Control REST API and refuses when a claimed entity or link is absent. Missing-link failures must identify the owner entity UID, target identifier, and link type/target type so the caller can repair the graph. `not_security_relevant` and `no_baseline` are successful assertion outcomes, but the returned envelope echoes the verdict so the completion record can carry the declination explicitly. The assertion may use existing entity-by-UID and link-list endpoints; add backend read support only when the existing API cannot answer a required check without trusting caller input.

**Assertion phase marker.** A successful assertion writes a distinct `grc_reconciled` phase marker. Downstream report gating may require both `traceability_reconciled` and `grc_reconciled`; do not overload the existing `grc_screening` marker, which only means the screening record was posted, not that its claimed links were re-verified.

## Consequences

- Every `/implement` run on a project with a threat-model baseline must post a screening record before the planning step. Empty-baseline projects post a `no_baseline` declination.
- Issue #1100 (server-side assertion) can parse the `gc:grc-screening` marker and verify the record is present and structurally valid before permitting certain downstream actions.
- The step adds one MCP tool call per run (two for `security_relevant` runs that need GRC writes). The cost is acceptable for the assurance gain, and the stage is routed at tier medium.
- `no_baseline` is intentionally a distinct verdict from `not_security_relevant` so missing baselines are surfaced rather than silently treated as clean.
- The June 6 revert lesson is operationalized: the gate is enforced at the tool layer, not by prose instruction alone.

## Amendment: v2 derivation-backed screening (GC-GRC-009, 2026-07-02)

GC-GRC-009 implements the ADR-058 §5 target contract for Step 3.5. The v1
functions above remain for reading historical/in-flight records; new screening
posts use the v2 path.

**Computed classification, not agent assertion.** `gc_post_grc_screening` no
longer accepts a caller `verdict` / `entities_*` / `code_links`, and the touched
surface is never caller-supplied (there is no `paths` override, so a caller
cannot narrow or forge it). It computes the touched surface from the git diff
(base defaults to merge-base with the repo base branch; the only caller scope
inputs are verifiable `*_commit_sha` refs and a `derivation_run_id`), reads the
existing GRC `targetType=CODE` link graph and the latest/pinned derivation run
plus architecture-model snapshot, and computes the classification via a pure,
unit-tested `classifyGrcScreening`.

**Three sets.** `impact_set` (existing entities whose CODE links overlap touched
paths), `gap_set` (touched security-relevant surfaces with no coverage), and
`stale_set` (ACTIVE linked entities whose linked code changed; DRAFT entities
are not yet a baseline, so they are impacted but not stale).

**`no_derivation_coverage` replaces the `no_baseline` pass.** A touched source
surface with no derivation coverage (including the empty/absent-baseline case)
is a `gap_set` entry with reason `no_derivation_coverage`, recorded alongside a
capture limit. Adapter absence is never silent, and there is no passing
`no_baseline` verdict.

**Source/non-source boundary reuses the repo's own path policy.** Security
relevance is never inferred from a filename. Non-source classes (docs, ADRs,
notes, skills/workflow prose, changelog fragments, repo metadata, tests, and the
policy/test tool paths), the same classes `tools/policy/checks.py` treats as
non-application-source, are excluded from the touched surface. Every remaining
application-source path is classified by coverage.

**Schema-versioned, reproducible record.** The record carries
`schema: gc.implement.grc-screening/v2`, the three sets, deterministic GC-GRC-007
candidate threats and GC-GRC-008 candidate controls (when a snapshot exists to
enumerate against), and a `provenance` block (base/head SHAs, derivation run and
snapshot ids, pack ids/versions/checksums, capture limits) from which the
classification is reproducible. Only stable keys, UIDs, repo-relative paths, ids,
versions/checksums, and rule ids are rendered, never raw diffs, secrets, or
tool output. The reserved-marker/HTML-delimiter guard, sensitive-content filter,
and body-size cap run before posting, shared with the v1 poster.

**Reconciliation branches on record schema and recomputes from the final diff.**
`gc_assert_grc_reconciled` reads the parsed record's schema: v1 records keep the
original verify-what-was-claimed verdict path. For v2 records it does NOT trust
the stored sets (which may have been computed early, before the final diff
existed): it recomputes the classification from the final diff (the record's
recorded base commit to the current HEAD) against the live GRC graph and derived
facts, and **blocks on the freshly computed `gap_set`** (`grc_not_reconciled`,
with each gap surface + reason enumerated in the failure envelope). This kills
the free pass at reconciliation and closes the freshness hole: source added
after screening ran is compared back to the model, so final code cannot bypass
`gap_set`. The `stale_set` is reported but not independently blocking here;
per-surface coverage teeth (ACTIVE threat + IMPLEMENTED control + efficacy test,
and stale-set addressing) are **GC-GRC-012**'s completion gate, not GC-GRC-009.
The only authorized bypass of a gap_set remains `gc_post_final_report`'s
`phaseOverride` (an audited disposition); no per-tool override is added.
