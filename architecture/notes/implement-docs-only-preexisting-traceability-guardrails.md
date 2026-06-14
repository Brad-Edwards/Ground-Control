# /implement Docs-Only and Pre-Existing Implementation Guardrails

Issue #801 identifies two workflow-instruction gaps in the canonical
`skills/implement/SKILL.md`: documentation-only deliveries with no meaningful
red-green TDD target, and requirements whose implementing artifacts already
exist outside the current diff.

This note is preflight guidance for the implementation. It does not change the
workflow contract by itself; the canonical skill remains the executable source
of truth per ADR-027.

## Architecture Boundaries

- Keep the fix in the canonical, agent-neutral workflow surface. Do not create
  driver-specific Claude/Codex branches of the `/implement` instructions.
- Reuse the existing Ground Control traceability model from ADR-011:
  `TraceabilityLink`, `ArtifactType`, and `LinkType`. Do not introduce a second
  link vocabulary for "pre-existing implementation" or "documentation-only"
  cases.
- Preserve ADR-029's issue-thread gate model. If the workflow needs an agent to
  record a TDD skip or traceability-backfill rationale, that rationale belongs
  in the issue thread or final workflow report, not a local state file.
- Keep Step 15 before Step 16. `IMPLEMENTS` links still require `ACTIVE`
  requirements through `TraceabilityService`; documentation-only and
  backfilled-link cases do not bypass that invariant.
- Treat reverse artifact lookup as the existing reconciliation primitive:
  `gc_get_traceability_by_artifact` / `GET /requirements/traceability/by-artifact`.
  Do not add another repo scanner, database table, or workflow schema for link
  discovery. The MCP surface answers "is path X already linked?" - it does not
  surface unknown candidates from a requirement statement, so the agent's own
  bounded `git ls-files` / `git grep` (NOT `grep -r`) against the requirement's
  named subsystems and identifiers is what feeds candidates into the
  reverse-lookup. The `git`-aware variants ensure the candidate set only
  contains tracked files; `grep -r` would also walk untracked / generated /
  `.gitignore`'d files and produce traceability candidates for artifacts
  that were never shipped.

## Guardrails

- Documentation-only diffs may skip red-green TDD only when there is no
  executable behavior to drive. The skip must be explicit and must point to the
  structural gate that protects the claim, such as an existing policy check,
  verifier script, schema validation, or documentation lint.
- Do not create substring or snapshot tests whose only purpose is to satisfy
  TDD wording for an ADR, README, changelog, or workflow prose update.
- Traceability reconciliation must distinguish "diff implemented this" from
  "diff documents or finalizes an already-implemented requirement." The latter
  still needs accurate `IMPLEMENTS` coverage on the actual artifacts of record,
  even when those files are not touched by the PR.
- Backfilling links outside the diff must be bounded by the issue or
  requirement's concrete subject matter. It is not permission to compare every
  requirement against every repository file.
- Existing linked artifacts that still satisfy a requirement remain valid. Do
  not churn links merely because the current PR touched a nearby document.

## Non-Goals

- No new Ground Control artifact type, link type, status, exception hierarchy,
  or persistence table.
- No change to `TraceabilityService`'s `IMPLEMENTS`-requires-`ACTIVE`
  enforcement.
- No new workflow engine, local marker file, git note, or driver-local state
  counter.
- No broad rewrite of `/implement`; the intended change is a narrow prose
  clarification that preserves ADR-021, ADR-027, and ADR-029.
