# ADR-051: SonarCloud gate recalibration (proposed)

- Status: PROPOSED - no live gate change shipped with this ADR
- Date: 2026-05-19
- Issue: #931

## Context

The /implement workflow's Step 11 enforces a "drive SonarCloud to zero" rule:
every open finding on the PR (regardless of severity, type, or pre-existing
state) must be fixed before merge. Recent commit history shows this rule
generates 1–3 "Fix SonarCloud findings (cycle N)" commits per PR - by
inspection, the heaviest single CI/Sonar cost generator in the workflow.

The empirical observation is that the gate is partly miscalibrated:

- **High-signal findings** - security hotspots, bugs, vulnerabilities,
  cognitive-complexity violations, BLOCKER/CRITICAL smells - track real defect
  risk. Driving these to zero is correct.
- **Long-tail INFO/MINOR code smells** - naming conventions, nullable-return
  micro-patterns, very short methods, "consider replacing X with Y"
  recommendations - are marginal signal. Many of them are pre-existing on the
  PR's diff area (Sonar reports them on lines the PR touched even when the
  PR did not introduce them). Fixing them generates churn for low defect-risk
  payoff.

The current rule also lacks a sharp coverage signal - coverage is part of
the Sonar quality gate but the rule "no open issues" is the bind, not
coverage.

## Decision

**No live change in this PR.** This ADR captures the proposal; activation
requires explicit user authorization.

The proposed recalibrated gate:

| Component | Current rule | Proposed rule |
|-----------|--------------|---------------|
| BLOCKER / CRITICAL smells | Zero open | **Zero open** (kept) |
| Security hotspots | Zero `TO_REVIEW` | **Zero `TO_REVIEW`** (kept) |
| Bugs / vulnerabilities | Zero open | **Zero open** (kept) |
| Cognitive complexity violations | Zero open | **Zero open** (kept) |
| MINOR / INFO smells (introduced by diff) | Zero open | **Zero new in diff; pre-existing not required to fix** |
| MINOR / INFO smells (pre-existing) | Zero open | **Allowed; tracked but not gated** |
| Coverage | Sonar quality gate | **Diff coverage ≥ 80% on changed Java files** |

The "no new MINOR/INFO smells in diff" rule still drives the long tail toward
zero over time - every PR introduces nothing new - without forcing the
current PR to clear unrelated pre-existing smells the diff didn't introduce.

## Consequences

If activated:

- Per-PR Sonar fix cycles drop substantially (estimated ~60–70% reduction in
  "Fix SonarCloud findings" commits) on PRs that don't introduce new high-
  severity findings.
- Pre-existing MINOR/INFO smell debt is acknowledged but not fixed in-line
  with feature work. Either (a) the workflow gains a separate "Sonar
  cleanup" lane that periodically clears the long tail, or (b) the
  debt is tolerated indefinitely. Most mature teams pick (a) - a periodic
  cleanup PR that touches no functional code.
- Diff coverage becomes a first-class gate. The current rule treats coverage
  as part of the quality-gate-pass-through; making it explicit lets us
  calibrate the threshold (80% is a defensible starting point).

If not activated:

- The current rule continues to generate churn for marginal signal.
- The /implement workflow's principal-engineer recalibration (#931) lands
  the prompt/envelope work but leaves the largest cycle-cost generator
  unchanged.

## Implementation sketch (if activated)

1. Update `cfg.sonarcloud` in `.ground-control.yaml` with an optional
   `severity_floor` field (default: existing behavior preserved when absent).
2. Update `tools/policy/checks.py` Sonar checks to consume the new field and
   apply the recalibrated predicate.
3. Update `skills/implement/SKILL.md` Step 11 to describe the new gate.
4. Update SonarCloud project quality gate at the SonarCloud admin level
   (separate config from the repo; coordinated with whoever owns the
   organization).
5. Run the existing test suite plus a sample PR to validate.

## Alternatives considered

- **Keep "drive to zero" but add a per-PR "skip pre-existing" annotation.**
  Adds workflow surface area (a new annotation), still requires the agent
  to triage pre-existing findings. Strictly worse than the recalibration
  for the same outcome.
- **Drop SonarCloud entirely; rely on Pitest + Codex review.** Pitest
  measures test effectiveness, not the static-analysis signal Sonar provides
  (security hotspots, cognitive complexity, language smells). Mutation
  testing and static analysis are complementary, not substitutes. Out of
  scope.

## Out of scope (deliberately)

- Activating this gate. Activation requires explicit user authorization at
  the issue-thread level.
- Changing `sonar-project.properties` or the SonarCloud organization-level
  quality gate.
- Adding the periodic "Sonar cleanup" lane (mentioned above as a likely
  follow-on if activation lands).

## References

- Issue #931 - Recalibrate AI review + preflight toward principal-engineer
  judgment. Section "Test tooling → SonarCloud gate recalibration."
- ADR-029 - Issue thread gate model (the cycle-bloat problem this ADR
  contributes to addressing).
- ADR-036 - Per-step routing + telemetry (operational cost reduction
  framework this ADR sits inside).
