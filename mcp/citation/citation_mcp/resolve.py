"""Identifier resolution to canonical CSL-JSON.

Supported prefixes: doi:, arxiv:, pmid:, isbn: (best-effort).
Each source uses the authoritative external service:
- doi: → DOI content negotiation (Crossref / DataCite / mEDRA)
- arxiv: → arXiv API (Atom XML), mapped to CSL-JSON
- pmid: → NCBI E-utilities (esummary)
- isbn: → Crossref query as fallback (no dedicated ISBN registry); declared best-effort
"""

from __future__ import annotations

import re
import time
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from typing import Any

from . import http

PREFIX_RE = re.compile(r"^([a-z]+):(.+)$")
ARXIV_API_COOLDOWN_SECONDS = 600.0
_ARXIV_API_COOLDOWN_UNTIL = 0.0
SUPPORTED_PREFIXES = ["doi", "arxiv", "pmid", "isbn"]


def parse_identifier(identifier: str) -> tuple[str, str]:
    """Split 'doi:10.x/y' into ('doi', '10.x/y'). Returns ('raw', identifier) if no prefix."""
    m = PREFIX_RE.match(identifier.strip())
    if not m:
        return ("raw", identifier.strip())
    return (m.group(1).lower(), m.group(2).strip())


def resolve_identifier(identifier: str) -> dict[str, Any]:
    """Resolve a prefixed identifier to CSL-JSON or return a structured error."""
    prefix, accession = parse_identifier(identifier)
    resolver = _RESOLVERS.get(prefix)
    try:
        if resolver is not None:
            return resolver(accession)
        if prefix == "raw":
            return {
                "error": f"identifier has no recognised prefix: {identifier!r}",
                "supported": SUPPORTED_PREFIXES,
            }
        return {"error": f"unsupported identifier prefix: {prefix!r}", "supported": SUPPORTED_PREFIXES}
    except Exception as e:
        return {"error": f"resolution failed for {identifier!r}: {type(e).__name__}: {e}"}


def resolve_doi(doi: str) -> dict[str, Any]:
    """DOI content negotiation → CSL-JSON."""
    url = f"https://doi.org/{doi}"
    with http.client() as c:
        resp = c.get(url, headers={"Accept": "application/vnd.citationstyles.csl+json"})
    if resp.status_code >= 400:
        msg = f"DOI not found: {doi}" if resp.status_code == 404 else f"DOI lookup HTTP {resp.status_code}: {doi}"
        return {"error": msg, "tried": [url]}
    try:
        data = resp.json()
    except Exception as e:
        return {"error": f"DOI returned non-JSON: {e}", "tried": [url]}
    return {"csl": data, "source": "doi-content-negotiation", "identifier": f"doi:{doi}"}


def _arxiv_text_of(elem: ET.Element | None) -> str | None:
    """Return the stripped text of an XML element, or None if absent."""
    return (elem.text or "").strip() if elem is not None else None


def _fetch_arxiv_api(arxiv_id: str, url: str) -> tuple[Any | None, dict[str, Any] | None]:
    """Query the arXiv API, returning ``(response, None)`` on success or
    ``(None, result)`` when a fallback/error result should be returned directly.

    Updates the module-level API cooldown on transport failures and HTTP 429.
    """
    global _ARXIV_API_COOLDOWN_UNTIL
    if time.monotonic() < _ARXIV_API_COOLDOWN_UNTIL:
        return None, resolve_arxiv_abs_page(arxiv_id, tried=[f"{url} (skipped: arXiv API cooldown)"])
    try:
        with http.client(timeout=10.0) as c:
            resp = c.get(url)
    except Exception as exc:
        _ARXIV_API_COOLDOWN_UNTIL = time.monotonic() + ARXIV_API_COOLDOWN_SECONDS
        return None, resolve_arxiv_abs_page(arxiv_id, tried=[f"{url} ({type(exc).__name__})"])
    if resp.status_code >= 400:
        if resp.status_code == 429:
            _ARXIV_API_COOLDOWN_UNTIL = time.monotonic() + ARXIV_API_COOLDOWN_SECONDS
            return None, resolve_arxiv_abs_page(arxiv_id, tried=[url])
        return None, {"error": f"arXiv HTTP {resp.status_code}: {arxiv_id}", "tried": [url]}
    return resp, None


def _parse_arxiv_entry(
    resp_text: str, arxiv_id: str, url: str, ns: dict[str, str]
) -> tuple[ET.Element | None, dict[str, Any] | None]:
    """Parse arXiv Atom XML and locate the entry element.

    Returns ``(entry, None)`` on success or ``(None, error)`` when parsing fails
    or no entry is present.
    """
    try:
        root = ET.fromstring(resp_text)
    except ET.ParseError as e:
        return None, {"error": f"arXiv returned malformed XML: {e}", "tried": [url]}
    entry = root.find("atom:entry", ns)
    if entry is None:
        return None, {"error": f"arXiv entry not found for {arxiv_id}", "tried": [url]}
    return entry, None


def _arxiv_entry_to_csl(entry: ET.Element, arxiv_id: str, ns: dict[str, str]) -> dict[str, Any]:
    """Map a parsed arXiv Atom ``<entry>`` element to CSL-JSON."""
    primary_doi = _arxiv_text_of(entry.find("arxiv:doi", ns))

    authors: list[dict[str, str]] = []
    for a in entry.findall("atom:author", ns):
        name = _arxiv_text_of(a.find("atom:name", ns)) or ""
        parts = name.rsplit(" ", 1)
        if len(parts) == 2:
            authors.append({"given": parts[0], "family": parts[1]})
        else:
            authors.append({"literal": name})

    csl: dict[str, Any] = {
        "id": f"arxiv:{arxiv_id}",
        "type": "article",
        "title": _arxiv_text_of(entry.find("atom:title", ns)),
        "author": authors,
        "abstract": _arxiv_text_of(entry.find("atom:summary", ns)),
        "publisher": "arXiv",
        "URL": f"https://arxiv.org/abs/{arxiv_id}",
    }
    if primary_doi:
        csl["DOI"] = primary_doi
    published = _arxiv_text_of(entry.find("atom:published", ns))
    if published:
        year = published[:4]
        if year.isdigit():
            csl["issued"] = {"date-parts": [[int(year)]]}
            csl["date-published-arxiv"] = published
    return csl


def resolve_arxiv(arxiv_id: str) -> dict[str, Any]:
    """arXiv API → CSL-JSON. arXiv returns Atom XML."""
    arxiv_id = arxiv_id.strip()
    url = f"https://export.arxiv.org/api/query?id_list={arxiv_id}"
    resp, fallback = _fetch_arxiv_api(arxiv_id, url)
    if fallback is not None:
        return fallback
    ns = {"atom": "http://www.w3.org/2005/Atom", "arxiv": "http://arxiv.org/schemas/atom"}
    entry, error = _parse_arxiv_entry(resp.text, arxiv_id, url, ns)
    if error is not None:
        return error
    csl = _arxiv_entry_to_csl(entry, arxiv_id, ns)
    return {"csl": csl, "source": "arxiv", "identifier": f"arxiv:{arxiv_id}"}


class _ArxivMetaParser(HTMLParser):
    """Collect ``<meta>`` name/property → content pairs from an arXiv abstract page."""

    def __init__(self) -> None:
        super().__init__()
        self.metadata: dict[str, list[str]] = {}

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() != "meta":
            return
        attr_map = {key.lower(): value for key, value in attrs if value is not None}
        name = attr_map.get("name") or attr_map.get("property")
        content = attr_map.get("content")
        if not name or content is None:
            return
        self.metadata.setdefault(name, []).append(content.strip())


def resolve_arxiv_abs_page(arxiv_id: str, tried: list[str] | None = None) -> dict[str, Any]:
    """arXiv abstract-page metadata fallback for API 429 responses."""
    url = f"https://arxiv.org/abs/{arxiv_id}"
    tried = [*(tried or []), url]
    with http.client() as c:
        resp = c.get(url)
    if resp.status_code >= 400:
        return {"error": f"arXiv abstract page HTTP {resp.status_code}: {arxiv_id}", "tried": tried}

    parser = _ArxivMetaParser()
    parser.feed(resp.text)
    meta = parser.metadata

    title = _first(meta, "citation_title", "og:title")
    if not title:
        return {"error": f"arXiv abstract page missing citation metadata: {arxiv_id}", "tried": tried}

    authors = [_name_to_csl(author) for author in meta.get("citation_author", [])]
    published = _first(meta, "citation_date", "citation_online_date")
    doi = _first(meta, "citation_doi")

    csl: dict[str, Any] = {
        "id": f"arxiv:{arxiv_id}",
        "type": "article",
        "title": title,
        "author": authors,
        "abstract": _first(meta, "citation_abstract", "og:description"),
        "publisher": "arXiv",
        "URL": url,
    }
    if doi:
        csl["DOI"] = doi
    if published:
        year = published[:4]
        if year.isdigit():
            csl["issued"] = {"date-parts": [[int(year)]]}
            csl["date-published-arxiv"] = published
    return {"csl": csl, "source": "arxiv-abs-page", "identifier": f"arxiv:{arxiv_id}", "tried": tried}


def _first(metadata: dict[str, list[str]], *keys: str) -> str | None:
    """Return the first non-empty value among ``keys`` in ``metadata``, else None."""
    for key in keys:
        values = metadata.get(key)
        if values:
            return values[0]
    return None


def _name_to_csl(name: str) -> dict[str, str]:
    """Split an author ``name`` into CSL given/family parts (or a literal fallback)."""
    if "," in name:
        family, given = [part.strip() for part in name.split(",", 1)]
        return {"given": given, "family": family}
    parts = name.rsplit(" ", 1)
    if len(parts) == 2:
        return {"given": parts[0], "family": parts[1]}
    return {"literal": name}


def _pmid_doi(rec: dict[str, Any]) -> str | None:
    """Return the DOI from an NCBI record's ``articleids``, or None."""
    for aid in rec.get("articleids", []):
        if aid.get("idtype") == "doi":
            return aid.get("value")
    return None


def resolve_pmid(pmid: str) -> dict[str, Any]:
    """NCBI E-utilities esummary → CSL-JSON (subset)."""
    pmid = pmid.strip()
    url = f"https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi?db=pubmed&id={pmid}&retmode=json"
    with http.client() as c:
        resp = c.get(url)
    if resp.status_code >= 400:
        return {"error": f"NCBI HTTP {resp.status_code}: pmid {pmid}", "tried": [url]}
    data = resp.json()
    result = data.get("result", {})
    if pmid not in result:
        return {"error": f"PMID not found: {pmid}", "tried": [url]}
    rec = result[pmid]
    authors = [{"literal": a.get("name", "")} for a in rec.get("authors", []) if a.get("name")]
    pub_year = (rec.get("pubdate") or "")[:4]
    issued: dict[str, Any] | None = None
    if pub_year.isdigit():
        issued = {"date-parts": [[int(pub_year)]]}
    doi = _pmid_doi(rec)
    csl: dict[str, Any] = {
        "id": f"pmid:{pmid}",
        "type": "article-journal",
        "title": rec.get("title"),
        "container-title": rec.get("fulljournalname") or rec.get("source"),
        "volume": rec.get("volume"),
        "issue": rec.get("issue"),
        "page": rec.get("pages"),
        "author": authors,
        "PMID": pmid,
    }
    if doi:
        csl["DOI"] = doi
    if issued:
        csl["issued"] = issued
    return {"csl": csl, "source": "ncbi-eutils", "identifier": f"pmid:{pmid}"}


def resolve_isbn(isbn: str) -> dict[str, Any]:
    """Best-effort ISBN resolution via OpenLibrary. Declared best-effort."""
    isbn_clean = re.sub(r"[^0-9Xx]", "", isbn).upper()
    url = f"https://openlibrary.org/api/books?bibkeys=ISBN:{isbn_clean}&format=json&jscmd=data"
    with http.client() as c:
        resp = c.get(url)
    if resp.status_code >= 400:
        return {"error": f"OpenLibrary HTTP {resp.status_code}: isbn {isbn}", "tried": [url]}
    data = resp.json()
    key = f"ISBN:{isbn_clean}"
    if key not in data:
        return {"error": f"ISBN not found in OpenLibrary: {isbn}", "tried": [url]}
    rec = data[key]
    authors = [{"literal": a.get("name", "")} for a in rec.get("authors", []) if a.get("name")]
    pub_year = None
    pd = rec.get("publish_date")
    if pd:
        m = re.search(r"(\d{4})", pd)
        if m:
            pub_year = int(m.group(1))
    publishers = rec.get("publishers") or []
    publisher = publishers[0]["name"] if publishers and isinstance(publishers[0], dict) else None
    csl: dict[str, Any] = {
        "id": f"isbn:{isbn_clean}",
        "type": "book",
        "title": rec.get("title"),
        "author": authors,
        "publisher": publisher,
        "ISBN": isbn_clean,
        "URL": rec.get("url"),
    }
    if pub_year:
        csl["issued"] = {"date-parts": [[pub_year]]}
    return {"csl": csl, "source": "openlibrary", "identifier": f"isbn:{isbn_clean}"}


_RESOLVERS = {
    "doi": resolve_doi,
    "arxiv": resolve_arxiv,
    "pmid": resolve_pmid,
    "pubmed": resolve_pmid,
    "isbn": resolve_isbn,
}
