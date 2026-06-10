"""FastMCP server exposing five citation/Zotero tools.

The agent's role here is identifier-shaped (DOI / arXiv / PMID / ISBN / search
string). Every step of bibliographic-data production happens through deterministic
external services (Crossref, OpenAlex, Unpaywall, Zotero translation-server,
Zotero Web API), so the agent never generates citation data from memory.
"""

from __future__ import annotations

import argparse
import sys
from typing import Any

from mcp.server.fastmcp import FastMCP

from . import oa, resolve, search, zotero_ingest

mcp = FastMCP("citation")


@mcp.tool()
def cite_resolve(identifier: str) -> dict[str, Any]:
    """Resolve a prefixed persistent identifier to canonical CSL-JSON.

    Supported prefixes: doi:, arxiv:, pmid:, isbn: (best-effort).
    Examples:
      cite_resolve("doi:10.1186/1748-5908-5-69")
      cite_resolve("arxiv:1706.03762")
      cite_resolve("pmid:19490148")
    Returns the source's canonical CSL-JSON in `csl`, or an error dict.
    No metadata is generated from memory.
    """
    return resolve.resolve_identifier(identifier)


@mcp.tool()
def cite_search(query: str, limit: int = 10, source: str = "openalex") -> dict[str, Any]:
    """Search for candidate works by title / keywords / author.

    source: "openalex" (default, broadest) or "crossref" (DOI registry).
    Returns ranked candidates with DOI, title, authors, year, venue, score.
    Use when you have partial info but no identifier; pick a candidate and
    call cite_resolve or zotero_add with its DOI.
    """
    return search.search(query, limit=limit, source=source)


@mcp.tool()
def zotero_add(
    identifier: str,
    collection_key: str | None = None,
    tags: list[str] | None = None,
) -> dict[str, Any]:
    """Ingest a citation into the user's Zotero library via translation-server.

    Idempotent on DOI: returns the existing key if the item is already in the
    library. Translation-server must be running (see error hint if not).
    `tags` must not include read-status tags (forbidden).
    """
    return zotero_ingest.add_to_zotero(identifier, collection_key=collection_key, tags=tags)


@mcp.tool()
def oa_locate(doi: str) -> dict[str, Any]:
    """Look up OA locations for a DOI via Unpaywall.

    Returns the locations Unpaywall identifies as legitimately open access,
    with host_type, version, and PDF URLs. Side effect: caches the returned
    URLs for 15 minutes so zotero_attach_pdf can enforce its OA-only policy.
    Does not download anything.
    """
    result = oa.unpaywall_lookup(doi)
    if "error" not in result and result.get("locations"):
        zotero_ingest.remember_oa_urls(result["doi"], result["locations"])
    return result


@mcp.tool()
def zotero_attach_pdf(
    zotero_key: str,
    pdf_url: str,
    source_note: str | None = None,
) -> dict[str, Any]:
    """Attach a PDF to an existing Zotero item, OA-only.

    Refuses to download `pdf_url` unless it appears in a recent oa_locate
    result for the parent item's DOI (15-minute cache). Verifies content
    is a PDF before attaching. Never attaches paywalled content.
    """
    return zotero_ingest.attach_pdf(zotero_key, pdf_url, source_note=source_note)


@mcp.tool()
def zotero_search(
    query: str = "",
    limit: int = 10,
    qmode: str = "titleCreatorYear",
    tags: list[str] | None = None,
    item_type: str | None = None,
) -> dict[str, Any]:
    """Search the user's Zotero library for existing items.

    Use this to check what is already in the library before considering
    zotero_add, to find items by partial title / author / year, or to
    list items by tag (e.g., methodology, research-catalog).

    query: free-text query; empty string + tag filter is valid for tag-only listing.
    limit: max results (1-100, default 10).
    qmode: "titleCreatorYear" (default), "title", or "everything". DOI strings
        do not index reliably under "everything" — search by title or author
        instead and check the DOI field on returned items.
    tags: list of tag names that returned items must have (AND filter).
    item_type: optional Zotero itemType filter (e.g., "journalArticle", "report").

    Returns: {results: [{zotero_key, title, creators, year, itemType, DOI, url, tags, collections}, ...], count, ...}.
    Attachments are excluded from results.
    """
    return zotero_ingest.search_zotero(
        query=query, limit=limit, qmode=qmode, tags=tags, item_type=item_type
    )


@mcp.tool()
def cite_forward(doi: str, limit: int = 25) -> dict[str, Any]:
    """Return works that CITE the given DOI — forward citations, via OpenAlex.

    Use for forward snowballing during a literature search. Backward
    citations (works a paper cites) are already available in the
    `reference` array returned by cite_resolve; this closes the other
    direction so snowballing never depends on a hand-search that could
    fabricate "cited by" entries.

    Returns {results: [{doi, title, authors, year, venue, ...}], total,
    cited_by_count, ...} or an error dict.
    """
    return search.search_forward(doi, limit=limit)


def _self_test() -> int:
    """Offline self-test: list tools, verify imports, exit. No network."""
    print("[citation-mcp] self-test starting", file=sys.stderr)
    tools = sorted(t.name for t in mcp._tool_manager.list_tools())
    print(f"[citation-mcp] {len(tools)} tools registered: {tools}", file=sys.stderr)
    expected = {
        "cite_resolve",
        "cite_search",
        "cite_forward",
        "zotero_add",
        "oa_locate",
        "zotero_attach_pdf",
        "zotero_search",
    }
    missing = expected - set(tools)
    extra = set(tools) - expected
    if missing or extra:
        print(f"[citation-mcp] tool set mismatch — missing={missing} extra={extra}", file=sys.stderr)
        return 2
    # Verify imports work
    from . import http as _http, zotero_ingest as _zi

    print(f"[citation-mcp] mailto: {_http.mailto()}", file=sys.stderr)
    print(f"[citation-mcp] translation_server_url: {_zi.translation_server_url()}", file=sys.stderr)
    print("[citation-mcp] self-test passed", file=sys.stderr)
    return 0


def main(argv: list[str] | None = None) -> int:
    """Parse CLI args and run the citation MCP server (or its self-test)."""
    parser = argparse.ArgumentParser(prog="citation-mcp")
    parser.add_argument("--self-test", action="store_true", help="run offline self-test and exit")
    args = parser.parse_args(argv)
    if args.self_test:
        return _self_test()
    mcp.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
