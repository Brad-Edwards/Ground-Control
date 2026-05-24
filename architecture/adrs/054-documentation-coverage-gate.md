# ADR-054: Documentation coverage gate

## Status

accepted

## Date

2026-05-23

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

**Layer 1—changed-surface classifier (`classifyChangedSurface` in lib.js).**
A closed-vocabulary function maps repo paths to surface classes and documentation
targets. Surface classes: `workflow`, `mcp_tool`, `config_parser`, `policy`,
`adr`, `public_api`, `user_visible`, `doc`, `unclassified`. When any path
classifies as one of the first five non-doc surfaces, `outcome_required` is
true and the PR body must carry a `documentation_outcome` field.

**Layer 2—structured outcome field in PR body and final report.**
`validatePrBodyInput` and `validateFinalReportInput` accept an optional
`documentation_outcome: { outcome, rationale? }` field. The outcome enum is
closed: `updated`, `verified_unchanged`, `not_updated_authorized`. Only the
third value permits a rationale string (1-2000 characters); the other two
reject it (strict). When `outcome_required` is true and the field is absent,
the renderer rejects the input rather than posting an incomplete record.

**Layer 3—Vale prose linter wired into `make policy`, CI, and pre-commit.**
Vale with the `errata-ai/Google` package enforces the Google Developer
Documentation Style Guide on docs modified in the current diff. The binary is
pinned to a specific version, verified by SHA-256 checksum, and installed by
`tools/install-vale.sh` to `.tools/vale/` (gitignored). The pre-commit hook
installs Vale automatically on first need rather than skipping; agents and
contributors do not bypass the gate by virtue of a fresh clone.

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
- Google Developer Documentation Style Guide: https://developers.google.com/style
- Diátaxis: https://diataxis.fr/
- Vale: https://vale.sh/
- errata-ai/Google Vale package: https://github.com/errata-ai/Google
