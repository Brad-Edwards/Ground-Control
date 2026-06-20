"""Citation search across OpenAlex (primary) and Crossref (fallback)."""

from __future__ import annotations

from typing import Any

from . import http

DOI_URL_PREFIX = "https://doi.org/"


def _strip_doi_prefix(doi: str | None) -> str | None:
    """Strip the leading ``https://doi.org/`` from a DOI, if present."""
    if doi and doi.startswith(DOI_URL_PREFIX):
        return doi[len(DOI_URL_PREFIX):]
    return doi


def _dispatch_search(query: str, limit: int, source: str) -> dict[str, Any]:
    """Route to the search backend for ``source``; assumes a non-empty query."""
    if source == "openalex":
        return search_openalex(query, limit)
    if source == "crossref":
        return search_crossref(query, limit)
    return {"error": f"unknown source: {source!r}", "supported": ["openalex", "crossref"]}


def search(query: str, limit: int = 10, source: str = "openalex") -> dict[str, Any]:
    """Search for candidate works. Returns {results: [...], source, total} or {error}."""
    if not query or not query.strip():
        return {"error": "empty query", "results": []}
    return _dispatch_search(query, limit, source.lower())


def _normalise_openalex_work(w: dict[str, Any]) -> dict[str, Any]:
    """Map a raw OpenAlex work dict to the normalised candidate-work shape."""
    doi = _strip_doi_prefix(w.get("doi"))
    authors_raw = w.get("authorships") or []
    authors = [a.get("author", {}).get("display_name") for a in authors_raw if a.get("author", {}).get("display_name")]
    return {
        "doi": doi,
        "title": w.get("title") or w.get("display_name"),
        "authors": authors,
        "year": w.get("publication_year"),
        "venue": ((w.get("primary_location") or {}).get("source") or {}).get("display_name"),
        "openalex_id": w.get("id"),
        "type": w.get("type"),
        "cited_by_count": w.get("cited_by_count"),
        "relevance_score": w.get("relevance_score"),
    }


def _resolve_openalex_work(c: Any, doi: str) -> dict[str, Any]:
    """Resolve a DOI to an OpenAlex work, returning {work_id, cited_by_count} or {error}."""
    work_resp = c.get(
        f"https://api.openalex.org/works/{DOI_URL_PREFIX}{doi}",
        params={"mailto": http.mailto()},
    )
    if work_resp.status_code == 404:
        return {"error": f"OpenAlex has no record of DOI {doi}", "doi": doi, "results": []}
    if work_resp.status_code >= 400:
        return {"error": f"OpenAlex HTTP {work_resp.status_code} resolving DOI {doi}", "doi": doi}
    work = work_resp.json()
    work_id = work.get("id") or ""
    # work_id is a URL like https://openalex.org/W2075950485; the cites:
    # filter wants the bare ID.
    short_id = work_id.rstrip("/").rsplit("/", 1)[-1] if work_id else ""
    if not short_id:
        return {"error": f"OpenAlex returned no work ID for DOI {doi}", "doi": doi}
    return {"short_id": short_id, "cited_by_count": work.get("cited_by_count", 0)}


def search_forward(doi: str, limit: int = 25) -> dict[str, Any]:
    """Return works that CITE the given DOI (forward citations), via OpenAlex.

    Backward citations (works a paper cites) come from cite_resolve's reference
    array. This closes the other direction so snowballing does not depend on a
    hand-search that could fabricate "cited by" entries.
    """
    doi = (doi or "").strip()
    if not doi:
        return {"error": "empty doi", "results": []}
    doi = _strip_doi_prefix(doi) or ""
    with http.client() as c:
        # Resolve the DOI to an OpenAlex work to obtain its work ID.
        resolved = _resolve_openalex_work(c, doi)
        if "short_id" not in resolved:
            return resolved
        cite_resp = c.get(
            "https://api.openalex.org/works",
            params={
                "filter": f"cites:{resolved['short_id']}",
                "per_page": min(max(limit, 1), 100),
                "mailto": http.mailto(),
            },
        )
    return _build_forward_result(cite_resp, doi, resolved["cited_by_count"])


def _build_forward_result(cite_resp: Any, doi: str, cited_by_count: int) -> dict[str, Any]:
    """Shape the forward-citations response from the cites-query response."""
    if cite_resp.status_code >= 400:
        return {"error": f"OpenAlex HTTP {cite_resp.status_code} on cites query", "doi": doi}
    data = cite_resp.json()
    results = [_normalise_openalex_work(w) for w in data.get("results", [])]
    return {
        "source": "openalex-forward",
        "doi": doi,
        "cited_by_count": cited_by_count,
        "total": data.get("meta", {}).get("count", 0),
        "results": results,
    }


def search_openalex(query: str, limit: int) -> dict[str, Any]:
    """Search OpenAlex for ``query``; returns {results, source, total} or {error}."""
    url = "https://api.openalex.org/works"
    params = {
        "search": query,
        "per_page": min(max(limit, 1), 50),
        "mailto": http.mailto(),
    }
    with http.client() as c:
        resp = c.get(url, params=params)
    if resp.status_code >= 400:
        return {"error": f"OpenAlex HTTP {resp.status_code}", "url": str(resp.url)}
    data = resp.json()
    results = [_normalise_openalex_work(w) for w in data.get("results", [])]
    return {
        "source": "openalex",
        "query": query,
        "total": data.get("meta", {}).get("count", 0),
        "results": results,
    }


def _crossref_author_name(a: dict[str, Any]) -> str:
    """Build a display name for a single Crossref author entry."""
    if a.get("family"):
        return (a.get("given", "") + " " + a.get("family", "")).strip()
    return a.get("name", "")


def _normalise_crossref_item(item: dict[str, Any]) -> dict[str, Any]:
    """Map a raw Crossref work item to the normalised candidate-work shape."""
    authors = [_crossref_author_name(a) for a in (item.get("author") or [])]
    title_list = item.get("title") or []
    venue_list = item.get("container-title") or []
    date_parts = (item.get("issued") or {}).get("date-parts") or [[None]]
    year = date_parts[0][0] if date_parts and date_parts[0] else None
    return {
        "doi": item.get("DOI"),
        "title": title_list[0] if title_list else None,
        "authors": [a for a in authors if a],
        "year": year,
        "venue": venue_list[0] if venue_list else None,
        "type": item.get("type"),
        "cited_by_count": item.get("is-referenced-by-count"),
        "relevance_score": item.get("score"),
    }


def search_crossref(query: str, limit: int) -> dict[str, Any]:
    """Search Crossref for ``query``; returns {results, source, total} or {error}."""
    url = "https://api.crossref.org/works"
    params = {
        "query": query,
        "rows": min(max(limit, 1), 50),
        "mailto": http.mailto(),
    }
    with http.client() as c:
        resp = c.get(url, params=params)
    if resp.status_code >= 400:
        return {"error": f"Crossref HTTP {resp.status_code}", "url": str(resp.url)}
    data = resp.json()
    items = data.get("message", {}).get("items", [])
    results = [_normalise_crossref_item(item) for item in items]
    return {
        "source": "crossref",
        "query": query,
        "total": data.get("message", {}).get("total-results", 0),
        "results": results,
    }
