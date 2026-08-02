---
id: GC-P028
title: "Source File Size Limit Enforced in Repo Policy"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 2
created_at: 2026-07-28T21:50:31.089153Z
updated_at: 2026-07-28T21:50:50.449736Z
---

# GC-P028 — Source File Size Limit Enforced in Repo Policy

## Statement

No tracked source file in the repository shall exceed 500 lines, and the limit shall be enforced by a repo-native policy check rather than documented alone. The check shall apply to `.java`, `.js`, `.mjs`, `.cjs`, `.ts`, `.tsx`, `.py` and `.kts` files enumerated via `git ls-files` (tracked files only, so build output and dependency trees are out of scope), excluding machine-generated trees whose content only their generator can change. Enforcement shall be two-sided: an oversized file that is not listed in the grandfather list fails the build, AND a listed file that is no longer oversized — or no longer present — also fails. Every grandfather entry shall carry a written reason; an entry without one is itself a violation. The list is therefore shrink-only and reaches empty when the work is complete, which is its finished state.

## Rationale

`docs/CODING_STANDARDS.md` set this limit from the outset but nothing enforced it, and 60 files totalling 69,821 lines drifted past it — the largest being the ones edited most often. Size proved not to be cosmetic: decomposing a 20,634-line module surfaced policy checks that located their subject by reading one file and would have passed silently once that file became a barrel, and a drift scan blind to anything but inline literals. The same class of failure recurred twice more during the decomposition itself — a registration-contract check that would have reported success by absence, and `node --test` exiting 0 on a throwing `describe()`. A limit nothing enforces is a comment that drifts. The two-sided rule is what distinguishes this from an allowlist: without the stale-entry half, the grandfather list becomes a place to park debt permanently; with it, the list can only shrink. Anchors the issue #1467 file-size structural gate (tools/policy/file_size.py, ADR-092) for traceability per the /implement structural-gate planning rule.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/092-file-size-limit-gate.md` (ADR-092: Enforce the 500-LOC file-size limit in repo policy)
- DOCUMENTS → DOCUMENTATION `docs/CODING_STANDARDS.md` (Coding standards: the file-size limit is enforced in policy, plus the splitting rules)
- IMPLEMENTS → CODE_FILE `tools/policy/file_size.py` (File-size limit gate: two-sided enforcement over tracked source files)
- TESTS → TEST `tools/tests/test_file_size_policy.py` (File-size gate tests: unlisted-oversized fails, stale entry fails, reason required, exclusions, repo is clean)
- IMPLEMENTS → CONFIG `tools/policy/file_size_grandfather.json` (Grandfather list (empty): the shrink-only exemption register the gate enforces)
- IMPLEMENTS → GITHUB_ISSUE `1467` (Decompose the 60 files exceeding the 500-LOC limit and enforce it in policy)
