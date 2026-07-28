# ADR-092: Enforce the 500-LOC file-size limit in repo policy

- **Status:** Accepted
- **Date:** 2026-07-28
- **Issue:** #1467
- **Supersedes:** none

## Context

`docs/CODING_STANDARDS.md` has set a 500-line file-size limit (Sonar `python:S104`
and its per-language equivalents) since the standards were written, but nothing
enforced it. By the time this was measured, **60 files totalling 69,821 lines were
over it**, and the largest were the ones edited most often.

Size was not a cosmetic problem. Decomposing the 20,634-line
`mcp/ground-control/lib.js` under #1355 surfaced two concrete failures:

- Several policy checks located their subject by reading `lib.js` as a single
  file. Once it became a barrel, those checks found no implementation and would
  have **passed silently**: a gate reporting green because it was looking at the
  wrong file.
- The measurement catalogue drift scan understood only inline `.station("x")`
  literals, so station ids named through a lookup table were invisible to it.

Both were possible because one file held so many unrelated surfaces that no
reviewer could hold its contents in mind. This work reproduced the same class of
failure twice more: splitting `mcp/ground-control/index.js` broke
`run_implement_execution_contract`, which read `index.js` alone to confirm two
MCP tools were registered, and it would have reported the contract satisfied by
absence. And `node --test` was found to exit **0** when a `describe()` callback
throws, so a suite broken by a bad import passed CI while printing `not ok`.

A limit nothing enforces is not a limit; it is a comment that drifts.

## Decision

Add a repo-native gate, `tools/policy/file_size.py`, run from
`tools/policy/checks.py::main` and therefore from `make policy` and CI.

1. **Scope.** Tracked source files with a `.java`, `.js`, `.mjs`, `.cjs`, `.ts`,
   `.tsx`, `.py` or `.kts` suffix. Enumeration is `git ls-files`, not a working-tree
   glob, so build output and `node_modules` are never this repo's to answer for.
   Generated trees (`contracts/gen/`) are excluded: only their generator can
   change them.

2. **Two-sided enforcement.** An oversized file that is not listed in
   `tools/policy/file_size_grandfather.json` fails the build. A **listed file that
   is no longer oversized, or no longer exists, also fails.** Without the second
   rule the list becomes a place to park debt forever; with it the list can only
   shrink, and it reaches empty exactly when the work is finished.

3. **Every entry states a reason.** Entries are `{path: reason}`, and an entry
   without a real reason is itself a violation. The legitimate reason is that
   another in-flight change already decomposes the file and doing it twice would
   destroy one of the two decompositions, not that a split was inconvenient.

4. **The gate lives in its own module.** `tools/policy/checks.py` is itself over
   the limit and is being decomposed on another branch. Putting the new check in
   a new module keeps this change from growing the file it is meant to shrink,
   and keeps its tests out of the equally oversized `tools/tests/test_policy.py`.

## Consequences

- New oversized files fail `make policy` and CI at the point they are introduced.
- The grandfather list is a visible, shrinking inventory of remaining debt rather
  than an invisible drift. It cannot be widened silently: adding a path is a
  reviewable diff carrying a written justification.
- A file being decomposed elsewhere can be listed without blocking unrelated work,
  and the stale-entry rule forces the listing to be removed when that work lands.
- Checks that locate their subject by reading one known file are now a known
  hazard. `run_implement_execution_contract` was corrected under this issue to read
  `index.js` together with the `tools/*` modules it registers through; the same
  question should be asked of any check that reads a single path.
- Splitting is constrained by the rules recorded in `docs/CODING_STANDARDS.md`:
  derive seams from the dependency graph rather than section comments, keep the
  original path as a barrel so the public surface and existing tests are
  unchanged, export a name only if it was public or a sibling needs it,
  decompose a single over-limit declaration rather than only the file, and use a
  comment-aware scanner so delimiters inside comments and literals cannot corrupt
  the result.

## Alternatives considered

- **Rely on SonarCloud's `S104`.** Sonar reports it, but as an issue on new code
  rather than a build gate, and it did not stop 60 files from drifting past the
  limit. A repo-native check fails locally in `make policy` before the push.
- **A one-sided allowlist.** Simpler, but an allowlist that never has to shrink is
  indistinguishable from raising the limit for the listed files.
- **Enforce only on changed files.** Cheap, but it lets an oversized file survive
  indefinitely as long as nobody touches it, which is the state that produced the
  69,821-line backlog.
