# Documentation after the re-platform: preflight for issue #650

This note bounds the documentation cleanup against the repository inspected on
2026-09-05. It adds no runtime decision or implementation plan. The issue is the
delivery contract; it has no attached requirement.

## Authority and retirement

Current documentation describes the Node Ground Control MCP server, repository
files, and GitHub records. Use the existing architecture overview, MCP README,
registered tools in `mcp/ground-control/tools/`, and executable gates as evidence.
The `lib.js` barrel also exports compatibility code: an export, old test, or
accepted ADR alone does not establish a supported product surface.

Retain useful decisions at stable ADR paths, with a prominent historical or
partially superseded scope note and a matching index entry. Preserve their original
reasoning, dates, and identifiers. Mixed records need section-level boundaries:
retiring deployment does not retire release automation, and retiring the measurement
product does not remove optional MCP telemetry. Do not blanket-retire workflow ADRs
or renumber existing duplicate ADR numbers. Cite exact filenames.

Delete obsolete operating instructions whose subject is wholly removed. Any retained
historical page must identify the removed system and point to the current reference;
an archive directory or index label alone is insufficient when readers land directly
on a page. Resolve incoming links and heading fragments, including references from
retained history. Historical code links need a verified immutable revision or an
explicitly historical path description, not a false current-tree link.

## Canonical documentation boundaries

- `README.md` owns entry points and the repository map; its `Repository map` heading,
  directory tokens, and links are consumed by `tools/policy/repo_map.py` (ADR-095).
- `docs/architecture/ARCHITECTURE.md` owns the current system boundary;
  `mcp/ground-control/README.md` owns setup and the tool reference. Derive tool names
  from registration and schemas, not old REST endpoints or copied tool counts.
- `docs/DEVELOPMENT_WORKFLOW.md` owns the human workflow reference; `docs/WORKFLOW.md`
  can be a small navigation page. Executable skills and MCP gates retain their roles.
  Consolidation must preserve lane distinctions, authorization, review separation,
  and issue-thread records rather than create a second workflow definition.
- Requirement shape belongs in `docs/requirements/README.md`, implemented by
  `mcp/ground-control/lib/requirement-files.js` and checked by
  `tools/policy/requirement_specs.py`. Preserve leading flat frontmatter, exact UID
  paths and ids, supported status values, section headings, and traceability syntax.
  The exporter is historical provenance for that shape, not a command to restore.

Retired requirements can remain at their UID paths with `status: ARCHIVED` and a
retirement explanation. Separate obsolete code/test evidence from the parser's
`## Traceability` section, for example under `## Historical traceability`; preserve
issue provenance. A banner or parenthetical title on a traceability bullet does not
stop the reader treating it as a live link. Do not infer current implementation from
archived evidence or fabricate replacement links. Assess mixed requirements
individually; do not change all lifecycle states as a text cleanup. ADR-093's #1541
amendment keeps requirement edits in the delivery PR and post-merge verification at
the immutable merge revision. A requirement-free run adds no invented UID assertion.

## Cross-cutting contracts

| Layer | Existing boundary the documentation must preserve |
| --- | --- |
| Configuration and paths | `.ground-control.yaml` is read through `gc_get_repo_ground_control_context`; `ground-control-config.js` and the `repo-context*` normalizers define accepted shapes. `resolveRepoRelativePath` and repository identity checks govern paths and targets. Examples must use supported fields and real commands; no alternate config reader or archive schema. |
| Tool validation and authorization | Zod tool registration plus the existing lib handlers remain authoritative. Privileged GitHub/git actions stay in the MCP server, bound to its repository and lane-specific approvals. Removed Spring controllers, security filters, DTOs, and validators are historical, not controls for the stdio service. |
| Secrets and host execution | Use placeholders only. Server startup reads the consuming repository's `.env`, with exported values taking precedence. Optional sink credentials are not a mandatory backend login. Preserve authenticated `gh`, argv-based server operations, and the minimal reviewer environment in `codex-engine-env.js`; never put tokens in command arguments or broaden environment forwarding. Do not inspect private host files to validate old runbooks. |
| Errors and observability | `tools/respond.js` owns MCP error envelopes; issue writers reuse existing input validation, sensitive-content and reserved-marker checks. `telemetry.js` records a closed event shape and fails open. Document stable failures without copying raw exceptions, environment dumps, payloads, or token-bearing output. Existing helpers are not a universal guarantee that arbitrary raw diagnostics are safe. |
| Persistence and completion | Requirement files and ADRs are reviewed Git records; durable workflow decisions live on the issue thread (ADR-029). Reuse the requirement reader and structured issue-record writers. Optional Graphify output is disposable comprehension data, never traceability or authorization proof (ADR-094). |
| Policy and verification | Reuse `make policy`, ADR coupling rules, documentation outcomes, the README map gate, requirement frontmatter checks, Vale, and existing skill/tool contract tests. Secret scanning remains in pre-commit and CI. Do not disable a gate to make a retirement pass. |

## Repository-wide traps

- `architecture/policies/adr-policy.json` currently requires both workflow pages
  when skill/guardrail surfaces change. Retaining a navigation page does not remove
  that future coupling. Any consolidation of the rule must preserve the owning ADR
  and policy tests. Editing `docs/DOC_STYLE.md` itself triggers the ADR-054 sync rule;
  ADR changes also have a documentation outcome and ADR-index target.
- `doc-coverage.js` contains a stale target named
  `architecture/adrs/027-ground-control-yaml-context-contract.md`; the existing ADR is
  `architecture/adrs/027-agent-neutral-implement-workflow-packaging.md`. Do not create
  a second ADR to satisfy a stale reference. Check policy/config consumers as well as
  Markdown links before moving documentation.
- `make policy` validates links only within the README repository map, not all
  documentation links or heading fragments. Link acceptance needs separate evidence
  for retained pages and incoming references. Traceability identifiers are typed:
  GitHub issue numbers are not filesystem paths.
- Vale checks entire touched documents, including old ADR prose. A one-line
  historical banner does not restrict lint to that line. Keep existing style rules;
  do not introduce broad archive exclusions to conceal failures.
- `skill-tool-registration-contract.test.js` scans tool names throughout executable
  skill prose, including examples and historical text. A warning banner does not
  exempt obsolete tool names; historical narratives belong outside active skills.
- `.env.example`, agent adapters, skills, templates, design indexes, research notes,
  and CI documentation also contain current-facing instructions. The collector
  runbook names a removed setup script; the deployment skill, REST examples, and
  database export command still describe deleted targets. Runtime/package manifests,
  the Makefile, `.github/workflows/`, `.github/CODEOWNERS`, `.pre-commit-config.yaml`,
  and the MCP client configuration are consumers or evidence too. The citation
  companion, research skills, knowledge ingest, and optional telemetry need
  individual review.
- `AGENTS.md` still names `make sync-ground-control-policy` and `make policy-live`,
  but neither target exists. The remaining `tools/ground_control` scripts target
  removed backend APIs. A reachable MCP process is not that backend; do not revive
  it or claim a live-policy check ran.

## Extensibility and non-goals

Use existing `docs.*` reference paths and `workflow.*_command` settings as the seams
for consuming repositories. Keep the schema's allowed key set unchanged; examples
distinguish consumer-configured commands from this repository's Make targets.
Stable entry pages and links let future tooling or workflow lanes evolve in their
own reference without copying setup, schemas, or gate logic across documents.

This issue does not redesign tool behavior, workflow gates, authentication, deployment,
telemetry, requirement parsing, or policy architecture. No new service, controller,
DTO, exception hierarchy, documentation framework, graph, or validation layer is
needed. Correct a stale consumer path only where necessary for the documentation
change, with its existing checks. Do not edit the generated changelog, restore
removed machinery, or turn historical decisions into current operating instructions.
