---
id: GC-P029
title: "Repository Map Freshness Enforced in Repo Policy"
status: ACTIVE
type: CONSTRAINT
priority: SHOULD
wave: 2
created_at: 2026-08-19T00:00:00Z
updated_at: 2026-08-19T04:00:32Z
---

# GC-P029 — Repository Map Freshness Enforced in Repo Policy

## Statement

The root `README.md` shall carry a "Repository map" section that names each
top-level directory of the repository with a one-line explanation, and the map's
correspondence to the actual repository shall be enforced by a repo-native policy
check rather than documented alone. The check shall be two-sided over the tracked
top-level directories (the first path segment of `git ls-files`): a tracked
top-level directory that is not in an explicit, reason-annotated exclusion set and
is not listed in the map fails the build, AND a directory the map lists that is not
a tracked top-level directory also fails. Every exclusion-set entry shall carry a
written reason and shall itself fail when the directory it names no longer exists,
so the exclusion set is shrink-only. Every repo-relative Markdown link in the map
section shall resolve on disk, since the repository ships no Markdown link checker.
Agent/editor/lint tooling directories (`.claude`, `.cursor`, `.gc`, `.serena`,
`.vale`) are the excluded configuration surfaces; `.github` is contributor-relevant
and shall be listed.

## Rationale

Issue #543 (audit finding A1-12). The root `README.md` had no repository map, so a
contributor had to discover the layout by hand. A navigation section added once and
never enforced drifts: the re-platform (#1500, ADR-089) deleted `backend/`,
`frontend/`, and the database while the onboarding docs kept describing them, which
is what makes a stale map worse than none. This is the same class of failure the
500-LOC file-size gate (GC-P028, ADR-092) exists to prevent, and it takes the same
shape of fix: a repo-native `make policy` / CI gate with two-sided, shrink-only
enforcement. Anchors the issue #543 repository-map structural gate
(`tools/policy/repo_map.py`, ADR-095) for traceability per the /implement
structural-gate planning rule.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/095-repository-map-freshness-gate.md` (ADR-095: Keep the README repository map fresh with a repo policy gate)
- IMPLEMENTS → CODE_FILE `tools/policy/repo_map.py` (Repository-map freshness gate: two-sided directory coverage, shrink-only exclusions, link resolution)
- IMPLEMENTS → CODE_FILE `tools/policy/cli.py` (Registers run_repository_map_freshness_check in the policy run)
- TESTS → TEST `tools/tests/test_policy_repo_map.py` (Gate tests: missing dir fails, phantom fails, stale exclusion fails, broken link fails, real repo is clean)
- DOCUMENTS → DOCUMENTATION `README.md` (Repository map section)
- IMPLEMENTS → GITHUB_ISSUE `543` (#543 [Arch] A1-12 — No root-level repository map / contributor onboarding section)
