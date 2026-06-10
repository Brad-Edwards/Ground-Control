# citation-mcp

Deterministic citation resolution and Zotero ingest MCP for Ground Control research workflows. Backs onto Crossref, OpenAlex, Unpaywall, the Zotero translation-server, and the Zotero Web API.

Used by the `lit-review`, `lit-review-plan`, `lit-review-search`, `lit-review-argument`, and `lit-review-draft` skills under `skills/` (see ADR-055). Skills reference its tools as `mcp__citation__*`; that prefix depends on the MCP server being registered as `citation` in `.mcp.json`.

## Tools

| Tool | Purpose |
|---|---|
| `cite_resolve` | Resolve `doi:` / `arxiv:` / `pmid:` / `isbn:` identifiers to canonical CSL-JSON. |
| `cite_search` | Search OpenAlex (default) or Crossref for candidate works by title / keywords / author. |
| `cite_forward` | Forward citations (works that cite a given DOI) via OpenAlex—closes backward + forward snowballing. |
| `oa_locate` | Look up OA locations for a DOI via Unpaywall. Caches results 15 min so `zotero_attach_pdf` can enforce OA-only attachment. |
| `zotero_search` | Search the user's Zotero library for existing items. |
| `zotero_add` | Ingest a citation into the user's Zotero library via translation-server. Idempotent on DOI. |
| `zotero_attach_pdf` | Attach an OA PDF to an existing Zotero item. Refuses non-OA URLs (15 min cache from `oa_locate`). |

## Bootstrap

The launcher `mcp/citation/bin/citation-mcp.sh` auto-bootstraps the venv on first run (idempotent), so the `.mcp.json` `citation` entry just invokes the launcher and the venv is created on demand. Manual bootstrap is only needed if you want to run the self-test before Claude Code first starts the server.

```sh
# From repo root—manual bootstrap (optional; the launcher does this lazily)
python3 -m venv mcp/citation/.venv
mcp/citation/.venv/bin/pip install -e mcp/citation/

# Required for zotero_add (one-time, idempotent restart)
docker run -d --rm -p 1969:1969 zotero/translation-server

# Offline self-test—no network, no Zotero
mcp/citation/bin/citation-mcp.sh --self-test
```

## Environment

| Variable | Required for | Default |
|---|---|---|
| `CITATION_MCP_MAILTO` | Crossref / OpenAlex polite-pool identification | `j.bradley.edwards@gmail.com` |
| `PERSONAL_ZOTERO_ID` | `zotero_search`, `zotero_add`, `zotero_attach_pdf` | unset (those tools error without it) |
| `PERSONAL_ZOTERO_KEY` | as above | unset |
| `TRANSLATION_SERVER_URL` | `zotero_add` | `http://localhost:1969` |

The skills set these via the `.mcp.json` `citation` server entry; an operator running the MCP outside of Claude Code provides them via the shell environment.

## Why deterministic

Citation hallucination is the highest-cost failure mode the research workflow guards against. The agent's role here is identifier-shaped (DOI / arXiv / PMID / search string); every step of bibliographic-data production happens through deterministic external services so the agent never generates citation data from memory. See `docs/knowledge/research-workflow/auto-research-requirements-and-oss-assessment.md` for the full build-vs-adopt analysis and ADR-055 for the architectural decision.
