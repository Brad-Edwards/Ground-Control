# ADR-095: Keep the README repository map fresh with a repo policy gate

- **Status:** Accepted
- **Date:** 2026-08-19
- **Issue:** #543
- **Supersedes:** none

## Context

The root `README.md` describes the product and quick-start but had no map of the
repository itself, so a contributor asking "where does a new X go" had to discover
`docs/`, `architecture/adrs/`, `architecture/policies/adr-policy.json`, and the rest
by hand (audit finding A1-12, #543).

A navigation section added once and never enforced drifts the same way the 500-LOC
limit did before ADR-092 gave it a gate: a new top-level directory is added and never
listed, a listed directory is removed and its row lingers, or a link rots because the
repo has no automated Markdown link checker. The re-platform (#1500, ADR-089) is the
concrete precedent: it deleted `backend/`, `frontend/`, and the database while the
onboarding docs kept describing them, which is exactly what makes a stale map worse
than no map.

## Decision

Add the map to `README.md` and enforce it with a repo-native gate,
`tools/policy/repo_map.py::run_repository_map_freshness_check`, run from
`tools/policy/cli.py::main` and therefore from `make policy` and CI.

1. **Completeness (two-sided).** Every tracked top-level directory (the first path
   segment of `git ls-files`) that is not in an explicit exclusion set must appear in
   the map as an inline-code `` `name/` `` token, and every `` `name/` `` the map lists
   must be a real tracked top-level directory. Neither a missing row nor a phantom row
   passes.

2. **The exclusion set shrinks, and every entry is justified.** `.claude`, `.cursor`,
   `.gc`, `.serena`, and `.vale` are agent/editor/lint tooling configuration, not
   source surfaces a contributor navigates, so they are excluded, each with a written
   reason in `MAP_EXCLUDED_DIRS`. `.github` is deliberately *not* excluded: CI,
   templates, and CODEOWNERS are contributor-relevant. An excluded directory that no
   longer exists is itself a violation, so the set cannot outlive what it names.

3. **Links resolve.** Every repo-relative Markdown link in the map section must exist
   on disk. There is no Markdown link checker in the repo, so the map's own links
   (the "authoritative reference doc" A1-12 asks for) are verified here.

The gate is anchored on requirement **GC-P029** for traceability, matching the
GC-P028 ⇄ ADR-092 file-size-gate precedent.

## Consequences

- Adding a top-level directory now fails `make policy` until the map (and, if it is
  tooling-only, the reasoned exclusion set) is updated in the same change: the
  intended one-row extension seam.
- The map's links cannot silently rot; a moved or deleted target fails the gate.
- The map is scoped to orientation and stays distinct from the README Documentation
  table (the reading index). The gate checks structure (directory coverage and link
  resolution), not prose quality, which review and Vale already cover.
- This ADR reverses, on purpose, the #543 architecture preflight's "no policy check /
  no ADR" proportionality recommendation. Correctness and drift-resistance were judged
  to outweigh the low change rate of the surface; the same call the file-size gate made.

## Alternatives considered

- **Documentation-only, no gate (the preflight's recommendation).** Lowest effort, but
  an unenforced map drifts, and the re-platform showed how badly stale onboarding docs
  mislead. Rejected in favor of the root-cause fix.
- **A generated directory map.** A generator would remove hand-maintenance but also the
  one-line human descriptions that make the map useful, and it is far more machinery
  than a low-change-rate navigation surface warrants. The gate enforces the hand-written
  map instead of replacing it.
- **Rely on Vale or a generic link checker.** Vale lints prose, not structure, and the
  repo ships no Markdown link checker. A focused policy check covers both the directory
  drift and the link resolution in one place already wired into `make policy`.
