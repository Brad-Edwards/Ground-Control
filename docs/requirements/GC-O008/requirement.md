---
id: GC-O008
title: "Repo-Local Ground Control Configuration Surface"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-04-12T03:02:56.383138Z
updated_at: 2026-04-12T03:05:51.790533Z
---

# GC-O008 — Repo-Local Ground Control Configuration Surface

## Statement

Every repository that participates in the Ground Control agentic development workflow shall carry a root-level `.ground-control.yaml` configuration file as its canonical workflow integration surface, optionally supported by a `.gc/` directory for files the yaml references. The configuration shall declare at minimum the Ground Control project identifier (required) and schema_version (required), and may optionally declare: the canonical GitHub owner/repo; the workflow commands (test, completion, lint, format) the agentic skills should use for that repo; SonarCloud integration (project key and organization); and a path to a plan-rules file that the `/implement` skill treats as mandatory per-repo plan constraints. The Ground Control MCP server's `gc_get_repo_ground_control_context` tool shall read `.ground-control.yaml` from a supplied repo path, validate it against the schema, resolve referenced plan-rules files by inlining their content, and return the full workflow config in a single response. When the file is missing or invalid, the tool shall return a diagnostic status (`missing_ground_control_yaml` or `invalid_ground_control_yaml`) and a `suggested_ground_control_yaml` starter template so agents can self-service migration. The previous convention of parsing an AGENTS.md "Ground Control Context" yaml block is superseded by this surface.

## Rationale

The /implement and /ship skills (GC-O007) need per-repo workflow values — test command, completion command, SonarCloud key, framework-specific plan rules — that cannot live in the skill itself without re-introducing the repo-duplication problem the Gated Agentic Development Loop was meant to eliminate. Hardcoding these values in each repo's copy of the skill files caused drift: shifter and shifter-k8s both shipped with `KeplerOps_Ground-Control` as their SonarCloud key for months because they were copied from Ground-Control without being updated. Centralizing the workflow skills at user level (~/.claude/skills/) is only viable if repos expose a stable, machine-readable config surface that the central skills can read via one MCP call. The prior convention (parsing AGENTS.md YAML blocks) was fragile: it mixed machine-readable config with human-readable prose, supported only the project identifier, and offered no place to put workflow commands, sonarcloud settings, or plan rules. A dedicated `.ground-control.yaml` with a defined schema and a `.gc/` folder for larger files gives every repo a clean contract with the Ground Control MCP server, gives skills a single lookup path, and gives the team one diff point when workflow defaults need to change. The schema is versioned (schema_version field) so it can evolve without breaking existing repos.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (Ground Control MCP server: parseGroundControlYaml and getRepoGroundControlContext)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/server-runtime.js` (Ground Control MCP server: gc_get_repo_ground_control_context tool registration; moved out of index.js when the entry point became an environment bootstrap (issue #1562))
- DOCUMENTS → ADR `ADR-023` (Repo-Local Workflow Config via .ground-control.yaml)
- TESTS → TEST `mcp/ground-control/lib.parsegroundcontrolyaml.test.js` (.ground-control.yaml parsing and schema validation tests)
- TESTS → TEST `mcp/ground-control/lib.getrepogroundcontrolcontext.test.js` (getRepoGroundControlContext tests (config resolution, diagnostics, suggested starter yaml))
