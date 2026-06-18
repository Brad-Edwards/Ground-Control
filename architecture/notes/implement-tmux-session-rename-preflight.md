# /implement tmux Session Rename Preflight

Issue #1180 adds best-effort tmux session renames to `/implement`: once when
the issue is picked up, and once after Phase E confirms the issue is closed.
This note is preflight guidance only. It does not implement the workflow
change, config parser change, or tests.

## Architecture Boundaries

- Keep `.ground-control.yaml` behind `gc_get_repo_ground_control_context`.
  `short_code` is a top-level optional config field beside `project` and
  `github_repo`; the skill must consume `cfg.short_code`, not re-read YAML.
- Keep tmux renaming in the workflow/session layer, not the MCP server. The
  current tmux session is local process state exposed through `$TMUX`; an MCP
  tool may be running in a different process context and should not become a
  terminal-session manager.
- Keep the rename operational-only. It is not a phase marker, not workflow
  state, not telemetry, not traceability, and not evidence on the GitHub issue
  thread.
- Preserve Phase E ordering: the `-done` rename happens only after
  `gc_close_issue_after_merge` returns `ok: true`, including the idempotent
  already-closed path.

## Cross-Cutting Concerns to Reuse

- **Config parser:** extend `parseGroundControlYaml`, `getRepoGroundControlContext`,
  `buildSuggestedGroundControlYaml`, `mcp/ground-control/README.md`, and
  `docs/DEVELOPMENT_WORKFLOW.md` together. Unknown top-level keys are rejected
  today, so adding only step prose will break valid repos that set
  `short_code`.
- **Step 1 cache contract:** add `cfg.short_code` to the Step 1 cached-field
  list and return contract so later steps do not invent another config path.
- **Step files:** update `skills/implement/steps/step-01-issue-branch-resolution.md`
  after sub-step 12 and `skills/implement/steps/step-20-close-issue-on-merge.md`
  after the close tool returns success. Do not add a new stage id or change the
  routing table for this operational no-op.
- **Tests:** cover absent, present, and invalid `short_code` in
  `mcp/ground-control/lib.test.js`, and keep the suggested-YAML coverage test
  aligned with the parser.

## Security Layers In Scope

- **Config shape:** validate `short_code` as a short uppercase identifier, for
  example uppercase letters/digits only with a small max length. Reject
  whitespace, shell metacharacters, slashes, leading dashes, Unicode, and empty
  strings. Absence defaults to `null` and must skip renaming silently.
- **OS/process exposure:** never place secrets in tmux names. The target names
  should be only `<short_code>-<issue_number>` and
  `<short_code>-<issue_number>-done[-n]`, built from validated config and a
  positive integer issue number.
- **Shell execution:** quote every tmux target variable. Do not use `eval`,
  command substitution from untrusted text, or shell-interpolated config beyond
  the validated target string.
- **Failure behavior:** tmux errors, missing `$TMUX`, missing `short_code`, and
  name collisions are non-fatal. Log a concise warning when a requested rename
  is skipped because the target already exists or tmux fails.
- **Error envelopes:** this change is outside backend REST controllers and MCP
  durable-record posting, so it must not introduce a new error envelope,
  exception class, GitHub comment, or backend API response shape.

## Extensibility Guardrail

The seam is the repo-local `short_code` value, not a generic session-naming
DSL. A future variation can add another optional config field for a naming
prefix or phase suffix if there are more consumers; two call-sites are not
enough reason to introduce a helper script, schema hierarchy, or terminal
session abstraction.

## Gotchas and Anti-Patterns

- Do not use `cfg.project` or a requirement UID prefix as the tmux prefix.
  Project ids are lowercase Ground Control identifiers; tmux names requested
  here use a separate short uppercase operator-facing code.
- Do not make a collision at Step 1 fatal. The existing session should keep its
  current name and the workflow should continue.
- Do not rename on error, escalation, capped reviews, or partial runs. The
  issue asks for clean-path pickup and clean-path done names only.
- Do not persist session names to Ground Control, the issue thread, telemetry,
  PR bodies, or changelog fragments.
- Do not add a backend controller, DTO, repository, entity, migration, or MCP
  tool solely for tmux session naming.

## Non-Goals

- No tmux lifecycle management, session creation, attach/detach behavior, or
  cleanup of stale sessions.
- No changes to branch naming, issue labeling, traceability, GRC gates, review
  caps, CI/Sonar behavior, or PR merge policy.
- No attempt to make tmux behavior work outside tmux; `$TMUX` unset is a
  silent skip.
