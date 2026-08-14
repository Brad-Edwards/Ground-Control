# Ground Control

[![CI](https://github.com/autarchy-ai/Ground-Control/actions/workflows/ci.yml/badge.svg)](https://github.com/autarchy-ai/Ground-Control/actions/workflows/ci.yml)

Ground Control is an **MCP server for the `/implement` workflow**, a gated,
agentic development loop that gives coding agents full codebase context, from
use case to implementation, and keeps the coding agent separated from its
reviewers.

Requirements, ADRs, and use cases live as **files in the consuming repo**
(`docs/requirements/<UID>/requirement.md`, `architecture/adrs/*.md`). There is no
backend, database, or web console: the MCP server is the only running service,
and it reads and writes those files and the GitHub issue thread directly. The
optional [Graphify](https://github.com/Graphify-Labs/graphify) index is available
for code+docs comprehension when an agent wants it, it is not required.

> This repository was re-platformed from a graph-native GRC/requirements product
> to the MCP server alone (issue #1500). The requirements-as-files record is
> [ADR-093](architecture/adrs/093-requirements-specs-as-code.md); the optional
> comprehension index is [ADR-094](architecture/adrs/094-graphify-comprehension-index.md).

## What the MCP server does

The surviving tool surface (~27 tools, down from 215) is exactly what the
`/implement` workflow needs, and every tool operates over `gh`/`git`/files, no
backend:

- **Orchestration**, `gc_implement_mechanical` drives the mechanical bands
  (bootstrap, verify, publish, monitor, readiness, finalize); `gc_codex_job`
  carries the long async actions; `gc_get_repo_ground_control_context` reads
  `.ground-control.yaml`.
- **Git / GitHub mechanics**, branch prep, issue pickup, issue-thread reads,
  base sync, synchronized PR creation, PR-body rendering, issue close, and
  issue creation from a requirement file.
- **CI / quality signals**, `gc_watch_ci_run` (GitHub) and
  `gc_watch_sonar_analysis` (direct), read live.
- **Reviewer separation**, the codex review, architecture-preflight, and
  verify tools, the test-quality review tools, and the review-cap disposition,
  the coding agent never reviews its own work.
- **Durable records**, plan, decision records, execution obligations, and the
  final report all post to the GitHub issue thread (ADR-029).

Requirement status and traceability are recorded by the agent directly in the
requirement file, reviewed in the PR like any other change.

## Getting started

**Prerequisites:** Node.js 20+, `gh` CLI (authenticated), `git`.

```bash
git clone https://github.com/autarchy-ai/Ground-Control.git
cd Ground-Control
make ground-control-mcp-install   # npm ci in mcp/ground-control
```

The server is configured in `.mcp.json` and works automatically with Claude
Code (and Codex / Cursor per ADR-027). See the
[MCP server docs](mcp/ground-control/README.md) for the full tool reference.

## Development

```bash
make mcp-test     # MCP node --test suite (primary test gate)
make mcp-lint     # ESLint on the MCP server (also run by `make policy`)
make policy       # repo-native ADR/workflow/spec guardrails + MCP lint + Vale
make vale-lint    # prose lint on changed docs
make graphify     # (optional) rebuild the disposable Graphify index
```

Run `make help` to see all targets.

## Documentation

| Document | Description |
|----------|-------------|
| [MCP Server](mcp/ground-control/README.md) | Tool reference, workflows |
| [Development Workflow](docs/DEVELOPMENT_WORKFLOW.md) | The `/implement` loop |
| [Coding Standards](docs/CODING_STANDARDS.md) | Style and testing policy |
| [Graphify](docs/GRAPHIFY.md) | Optional comprehension index |
| [ADRs](architecture/adrs/) | Architecture Decision Records |
| [Contributing](CONTRIBUTING.md) | Setup, workflow, PR process |
| [Changelog](CHANGELOG.md) | Release history |

## License

[MIT](LICENSE)
