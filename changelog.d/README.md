# Changelog fragments

This directory holds **per-PR changelog fragments** consumed by
[`towncrier`](https://towncrier.readthedocs.io/) at release time. The
convention exists so concurrent PRs can never conflict on the same line
range of `CHANGELOG.md` - every change writes to its own fragment path.

The fragment workflow is required by ADR-021 (Phase B, amended by issue
#848) and enforced by:

- `tools/policy/checks.py::run_changelog_fragment_check` (`make policy`).
- `.claude/hooks/verify-implementation.sh` (host-local Stop hook on
  `/implement` runs).

## When you need a fragment

Add a fragment when your PR changes **application source**:

- `backend/src/main/**`, `backend/src/test/**`
- `frontend/src/**`
- `mcp/**`
- `tools/**` outside `tools/policy/` and `tools/tests/`

You **do not** need a fragment for:

- CI-only diffs (`.github/workflows/**` only).
- Documentation-only diffs (`docs/**`, `architecture/**`, `README.md`,
  `CONTRIBUTING.md`, `.gc/**`, `skills/**`).
- Repo metadata changes (this directory's tooling, `.gitattributes`,
  etc.).

There is **no "pure refactor" carve-out**. The enforcement is
path-based - `tools/policy/checks.py` cannot tell a behavior-preserving
refactor from a feature change, and an unenforceable carve-out is a
contract you cannot rely on. A refactor that touches application
source still files a fragment, even if it's a one-line
`### Changed - Internal refactor of X (no functional change)`. Cheap
to write; eliminates a class of "where did this change come from?"
questions at release time.

The policy check encodes the same decision: it skips diffs that don't
touch any application-source path, and otherwise requires a fragment.

## Filename convention

```
changelog.d/<issue>.<type>.md       # PR closes a GitHub issue
changelog.d/+<slug>.<type>.md       # PR has no anchoring issue
```

- `<issue>` is the GitHub issue number this PR closes (no `#`, no
  zero-padding).
- `<slug>` is a short kebab-case description prefixed with `+`. The
  `+` sorts the slug-form fragments after issue-form fragments and
  signals "no anchoring issue."
- `<type>` is one of the six Keep-a-Changelog categories:
  - `security`
  - `added`
  - `changed`
  - `deprecated`
  - `removed`
  - `fixed`

Examples:

```
changelog.d/848.added.md
changelog.d/932.fixed.md
changelog.d/+towncrier-adoption.added.md
```

The parser is strict: anything outside this grammar is rejected by
`make policy` with `changelog-fragment-invalid-name` so towncrier can't
silently skip a misspelled fragment.

## Fragment contents

One short Markdown bullet (or a few - towncrier wraps them). Write the
release-notes line: *what changed for the user*, not *what the diff
did*. Towncrier collates the line under the appropriate `### <Type>`
heading at release time, appending `(#<issue>)` automatically when the
filename carries an issue number.

```markdown
Adopt towncrier-style changelog fragments. PRs now drop a fragment under
`changelog.d/` instead of editing `CHANGELOG.md` directly; release-time
`towncrier build` collates fragments into the changelog.
```

## Release-time collation

At release time, the maintainer runs (per-repo trigger - manual, on
tag, on push to `main`, however the repo prefers):

```bash
uvx towncrier build --version <X.Y.Z> --date $(date -u +%Y-%m-%d) --yes
```

`towncrier` reads `towncrier.toml`, inserts the new section into
`CHANGELOG.md` directly after the
`<!-- towncrier release notes start -->` marker, and removes the
fragments it consumed. Commit the result. PRs that landed since the
last release are now in the changelog.

## Why this directory exists

A historical pattern across Keep-a-Changelog projects: every PR
hand-edits the top of `CHANGELOG.md` with a new `## [X.Y.Z]` entry.
The first PR lands. The second PR's `CHANGELOG.md` now conflicts with
`dev` on the same line range. The PR author rebases, CI re-runs
(15 minutes), `dev` advances again, conflict again, rebase again. On a
fast-moving day this becomes a rebase storm - the conflict is purely
structural (two PRs cannot edit the same line range without
conflicting), and the time burned on it is wasted.

Fragment files avoid the conflict by giving every PR its own path:
`changelog.d/848.added.md` and `changelog.d/932.fixed.md` are different
files, so two PRs can land them concurrently without touching the same
line range. Used by pip, pytest, attrs, Twisted, and many others for
the same reason.
