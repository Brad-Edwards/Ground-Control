# Graphify: the code + docs comprehension index

Graphify ([Graphify-Labs/graphify](https://github.com/Graphify-Labs/graphify)) is Ground Control's
**disposable, opt-in** knowledge index over the source tree and the specs-as-code files. It parses
code (tree-sitter AST) and documentation into a traversable graph so a coding agent can reason from
**use case → implementation → test** by traversal instead of grepping. It replaces the retired
context-graph machinery ([ADR-094](../architecture/adrs/094-graphify-comprehension-index.md),
[ADR-093](../architecture/adrs/093-requirements-specs-as-code.md)).

It is a **cache, never the record.** Git (the code, `architecture/adrs/`, and
`docs/requirements/`) is the source of truth. Graphify's `graph.json` is regenerated on demand and
is git-ignored. If Graphify breaks or is dropped, nothing is lost but a convenience index.

## Install (once, per developer)

```bash
uv tool install graphifyy      # recommended; or: pipx install graphifyy / pip install graphifyy
```

The PyPI package is `graphifyy` (double-y); the command is `graphify`.

## Build the index

```bash
graphify extract . --code-only --update      # from the repo root; or: make graphify
```

`--code-only` uses deterministic AST parsing with **no LLM calls and no credentials**, which is the
default for Ground Control. Output lands in `graphify-out/` (git-ignored): `graph.json` (queryable),
`graph.html` (visualization), `GRAPH_REPORT.md` (summary). Scope is controlled by `.graphifyignore`.

## Query it

```bash
graphify query "what connects the exporter to the requirements read path?"
graphify path "RequirementsMarkdownExportService" "AnalysisService"
graphify explain "RequirementsMarkdownExportRunner"
```

Or expose it to an agent over MCP:

```bash
python -m graphify.serve graphify-out/graph.json           # stdio, one per developer
```

## Optional: rebuild on commit

```bash
graphify hook install      # post-commit / post-checkout rebuild + graph.json union merge-driver
```

This is **opt-in** and off by default; it installs local git hooks only. CI never depends on
Graphify, and the index is never a runtime dependency of Ground Control.

## Guardrails

- **Disposable and rebuildable.** `graphify-out/` is git-ignored; never commit `graph.json`.
- **Not proof.** Graphify edges are tagged `EXTRACTED` (explicit) or `INFERRED` (derived) and are a
  comprehension aid, not coverage/authorization/audit/release proof. The deterministic
  `run_requirement_specs_frontmatter_check` policy lint is the source for any retained guarantee.
- **No credentials in the repo, no network-required CI path, no runtime service.**
