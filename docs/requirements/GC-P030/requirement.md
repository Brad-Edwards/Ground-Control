---
id: GC-P030
title: "Required Status Contexts Enforced in Repo Policy"
status: ACTIVE
type: CONSTRAINT
priority: SHOULD
wave: 2
created_at: 2026-09-05T00:00:00Z
updated_at: 2026-09-05T00:00:00Z
---

# GC-P030 — Required Status Contexts Enforced in Repo Policy

## Statement

The repository's required status checks shall be declared once, and the
correspondence between that declaration and the jobs that actually produce those
checks shall be enforced by a repo-native policy check rather than documented
alone. The check shall be two-sided over the declared required-context set: a
declared context that no pull-request-triggered job in `.github/workflows/`
produces fails the build, AND a branch-protection baseline whose context set
differs from the declaration in either direction also fails. The check shall
additionally assert that every protected branch declares strict required status
checks. Contexts posted by a hosted application rather than by a job in this
repository shall be exempt from needing a local producer only through an explicit
allowlist, and that allowlist shall itself fail when an entry is no longer a
required context, so the exemption set is shrink-only.

## Rationale

Issue #650. A required status check with no job behind it never reports, so every
pull request waits forever on a check that cannot arrive. This has now happened
twice. Issue #1461 removed the CI `mutation` job but left its context declared,
and `tools/tests/test_ci_topology.py` was written to stop that recurring. The
#1500 re-platform then deleted the `build`, `frontend`, `integration`, `test`,
and `verify` jobs and deleted those topology tests along with the CI surface they
covered, so all five contexts stayed declared in
`.github/branch-protection-baseline.json` and in
`tools/policy/checks.py::CI_STRICTNESS_REQUIRED_CONTEXTS` with nothing left to
produce them, and nothing detected it.

The lesson is that the gate must not live inside the surface it guards. This
check is anchored on the declaration and the workflow files rather than on any
single CI topology, so deleting a job cannot delete the check that notices. It is
the same class of failure the repository-map freshness gate (GC-P029, ADR-095)
and the file-size limit gate (GC-P028, ADR-092) exist to prevent, and it takes
the same shape of fix: a repo-native `make policy` and CI gate with two-sided,
shrink-only enforcement.

The two-sidedness matters in both directions. An unproduced context blocks all
merges, which is loud but total. A context quietly dropped from the required set
is the gate-weakening direction and is silent, which is worse.

## Traceability

- IMPLEMENTS → ADR `architecture/adrs/091-ci-verification-topology.md` (ADR-091: CI verification topology, amended for the required-context gate)
- IMPLEMENTS → CODE_FILE `tools/policy/ci_strictness.py` (run_ci_required_context_contract — two-sided baseline/producer check)
- IMPLEMENTS → CODE_FILE `tools/policy/core.py` (CI_STRICTNESS_REQUIRED_CONTEXTS / CI_STRICTNESS_BRANCHES — the single declaration)
- IMPLEMENTS → CODE_FILE `tools/policy/cli.py` (gate registration in the bin/policy run)
- IMPLEMENTS → CONFIG `.github/branch-protection-baseline.json` (the versioned required-context baseline the gate compares)
- TESTS → TEST `tools/tests/test_policy_ci_required_contexts.py` (unproduced context, baseline drift, non-strict branch, missing branch, shrink-only allowlist, scan floor)
- DOCUMENTS → DOCUMENTATION `docs/ci/CI_PIPELINE.md` (required contexts and their producing jobs)
- DOCUMENTS → DOCUMENTATION `docs/DEVELOPMENT_WORKFLOW.md` (repo-native policy layer)
- IMPLEMENTS → GITHUB_ISSUE `650` (Reconcile or retire stale pre-#1500 documentation)
