"""Zotero ingest via translation-server + pyzotero, plus OA-policy-gated PDF attach."""

from __future__ import annotations

import os
import tempfile
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

from . import http
from . import oa as oa_module
from .resolve import parse_identifier


def translation_server_url() -> str:
    """Return the configured translation-server base URL without a trailing slash."""
    return os.environ.get("TRANSLATION_SERVER_URL", "http://localhost:1969").rstrip("/")


def zotero_client() -> Any:
    """Return a pyzotero client. Raises if env vars are missing."""
    library_id = os.environ.get("PERSONAL_ZOTERO_ID")
    api_key = os.environ.get("PERSONAL_ZOTERO_KEY")
    if not library_id or not api_key:
        raise RuntimeError("PERSONAL_ZOTERO_ID / PERSONAL_ZOTERO_KEY not set in environment")
    from pyzotero import zotero

    return zotero.Zotero(library_id, "user", api_key)


def _format_creators(creators: list[dict[str, Any]] | None) -> list[str]:
    """Render Zotero creator dicts into ``Last, First`` (or ``name``) strings."""
    formatted: list[str] = []
    for c in creators or []:
        if c.get("name"):
            formatted.append(c["name"])
        elif c.get("lastName") or c.get("firstName"):
            formatted.append(f"{c.get('lastName','')}, {c.get('firstName','')}".strip(", "))
    return formatted


def _year_from_date(date: str | None) -> int | None:
    """Pull the first 4-digit year out of a free-form Zotero date string."""
    for token in (date or "").replace("-", " ").split():
        if token.isdigit() and len(token) == 4:
            return int(token)
    return None


def _normalise_zotero_item(item: dict[str, Any]) -> dict[str, Any]:
    """Reduce a pyzotero item dict to a stable result shape."""
    data = item.get("data", {})
    doi = data.get("DOI") or _doi_from_extra(data.get("extra"))
    return {
        "zotero_key": item.get("key"),
        "title": data.get("title"),
        "creators": _format_creators(data.get("creators")),
        "year": _year_from_date(data.get("date")),
        "itemType": data.get("itemType"),
        "DOI": doi,
        "url": data.get("url"),
        "tags": sorted({t.get("tag") for t in data.get("tags", []) if t.get("tag")}),
        "collections": list(data.get("collections", [])),
    }


def search_zotero(
    query: str = "",
    limit: int = 10,
    qmode: str = "titleCreatorYear",
    tags: list[str] | None = None,
    item_type: str | None = None,
) -> dict[str, Any]:
    """Search the user's Zotero library.

    qmode: one of "titleCreatorYear" (default), "title", "everything".
    tags: list of tag names to require (AND-filter). Empty/None = no tag filter.
    item_type: optional Zotero itemType filter (e.g., "journalArticle"). Attachments
    are always excluded.
    """
    limit = max(1, min(int(limit), 100))
    valid_modes = {"titleCreatorYear", "title", "everything"}
    if qmode not in valid_modes:
        return {"error": f"qmode must be one of {sorted(valid_modes)}", "got": qmode}

    z = zotero_client()
    kwargs: dict[str, Any] = {"limit": limit, "qmode": qmode}
    if query:
        kwargs["q"] = query
    if tags:
        kwargs["tag"] = list(tags)
    if item_type:
        kwargs["itemType"] = item_type

    try:
        raw = z.items(**kwargs)
    except Exception as e:
        return {"error": f"Zotero search failed: {type(e).__name__}: {e}"}

    results = []
    for item in raw:
        data = item.get("data", {})
        if data.get("itemType") == "attachment":
            continue
        results.append(_normalise_zotero_item(item))

    return {
        "query": query,
        "qmode": qmode,
        "tags_filter": tags or [],
        "item_type_filter": item_type,
        "count": len(results),
        "results": results,
    }


# Cache of recent oa_locate results: {doi: (timestamp, allowed_urls_set)}.
# 15-minute TTL. Used by attach_pdf to enforce the OA-policy gate.
_OA_CACHE: dict[str, tuple[float, set[str]]] = {}
_OA_CACHE_TTL = 15 * 60


def remember_oa_urls(doi: str, locations: list[dict[str, Any]]) -> None:
    """Cache the OA-allowed URLs for a DOI so attach_pdf can enforce the gate."""
    urls: set[str] = set()
    for loc in locations or []:
        for k in ("url", "url_for_pdf", "url_for_landing_page"):
            if loc.get(k):
                urls.add(loc[k])
    _OA_CACHE[doi.strip().lower()] = (time.time(), urls)


def _allowed_oa_urls(doi: str) -> set[str] | None:
    """Return the cached OA-allowed URLs for a DOI, or None if absent/expired."""
    entry = _OA_CACHE.get(doi.strip().lower())
    if not entry:
        return None
    ts, urls = entry
    if time.time() - ts > _OA_CACHE_TTL:
        _OA_CACHE.pop(doi.strip().lower(), None)
        return None
    return urls


def _translation_status_error(resp: httpx.Response, identifier: str) -> dict[str, Any] | None:
    """Map a translation-server response status to an error dict, or None if OK."""
    if resp.status_code == 501:
        return {"error": "translation-server could not handle identifier", "identifier": identifier}
    if resp.status_code == 300:
        return {"error": "translation-server returned multiple items requiring selection (not handled in v1)"}
    if resp.status_code >= 400:
        return {"error": f"translation-server HTTP {resp.status_code}", "body": resp.text[:300]}
    return None


def _translation_items(resp: httpx.Response) -> dict[str, Any]:
    """Parse a successful translation-server response into {items} or {error}."""
    try:
        items = resp.json()
    except Exception as e:
        return {"error": f"translation-server returned non-JSON: {e}"}
    if not isinstance(items, list) or not items:
        return {"error": "translation-server returned no items"}
    return {"items": items}


def translate_identifier(identifier: str) -> dict[str, Any]:
    """POST identifier to translation-server. Returns {items: [...]} or {error}.

    Identifiers acceptable to /search: DOI, ISBN, PMID, arXiv ID (with prefix or bare).
    For URL inputs use /web.
    """
    prefix, accession = parse_identifier(identifier)
    body = accession if prefix == "raw" else f"{prefix}:{accession}"
    base = translation_server_url()

    try:
        with http.client(timeout=30.0) as c:
            resp = c.post(
                f"{base}/search",
                content=body.encode("utf-8"),
                headers={"Content-Type": "text/plain"},
            )
    except (httpx.ConnectError, httpx.ReadError, httpx.ConnectTimeout) as e:
        return {
            "error": "translation-server unreachable",
            "url": base,
            "hint": "Start translation-server with: docker run -d --rm -p 1969:1969 zotero/translation-server",
            "detail": str(e),
        }

    status_error = _translation_status_error(resp, identifier)
    if status_error is not None:
        return status_error
    return _translation_items(resp)


def _first_matching_item(
    z: Any,
    query_kwargs: dict[str, Any],
    predicate: Callable[[dict[str, Any]], bool],
) -> dict[str, Any] | None:
    """Run one Zotero ``items`` query and return the first item satisfying ``predicate``.

    Swallows query errors (returning None) so callers can try fallback queries.
    """
    try:
        for item in z.items(**query_kwargs):
            if predicate(item):
                return item
    except Exception:
        return None
    return None


def find_by_doi(doi: str, title_hint: str | None = None) -> dict[str, Any] | None:
    """Search the user's Zotero library for an existing item with matching DOI.

    Zotero's full-text search does not reliably index DOI strings (slashes and
    dots tokenize as separators), so `q=<doi>` often misses even when the
    DOI is set on an existing item. Workaround: search by a title hint when
    available, then verify DOI match in the candidates. Falls back to the
    DOI's suffix and finally to the raw DOI string.
    """
    doi = (doi or "").strip().lower()
    if not doi:
        return None
    z = zotero_client()

    def _check(item: dict[str, Any]) -> bool:
        """Return True if ``item`` is a non-attachment whose DOI matches ``doi``."""
        data = item.get("data", {})
        if data.get("itemType") == "attachment":
            return False
        return (data.get("DOI") or "").lower() == doi or doi in (data.get("extra") or "").lower()

    # Try, in order: title-based search (verify DOI in candidates), the DOI
    # suffix after the "/" (often matches URL fields with https://doi.org/<suffix>),
    # then the raw DOI as a last resort.
    suffix = doi.split("/", 1)[1] if "/" in doi else doi
    attempts: list[dict[str, Any]] = []
    if title_hint:
        attempts.append({"q": title_hint, "qmode": "titleCreatorYear", "limit": 25})
    attempts.append({"q": suffix, "qmode": "everything", "limit": 25})
    attempts.append({"q": doi, "qmode": "everything", "limit": 25})

    for kwargs in attempts:
        match = _first_matching_item(z, kwargs, _check)
        if match is not None:
            return match
    return None


def _doi_from_extra(extra: str | None) -> str | None:
    """Return the DOI declared on a ``doi:`` line of a Zotero/translator extra field."""
    for line in (extra or "").splitlines():
        if line.strip().lower().startswith("doi:"):
            return line.split(":", 1)[1].strip()
    return None


def _doi_from_translation(item: dict[str, Any]) -> str | None:
    """Extract a DOI from a translation-server item, checking the extra field."""
    doi = item.get("DOI") or item.get("doi")
    if doi:
        return doi.strip()
    # Some translators put DOI in extra
    return _doi_from_extra(item.get("extra"))


def _forbidden_tags_error(tags: list[str] | None) -> dict[str, Any] | None:
    """Return an error dict if read-status tags were requested, else None."""
    if not tags:
        return None
    forbidden = {t for t in tags if t.lower() in ("read", "unread")}
    if forbidden:
        return {"error": f"forbidden tags: {sorted(forbidden)} (read-status tags not allowed)"}
    return None


def _resolve_biblio_item(translation: dict[str, Any]) -> dict[str, Any]:
    """Pick the single bibliographic item from a translation, or return {error}.

    translation-server can return auxiliary entries — standalone notes or
    attachments — alongside the bibliographic record (the arXiv translator,
    for one, emits the paper's "comment" as a separate note). Those are not
    citations; keep only the real bibliographic item(s).
    """
    items = translation["items"]
    biblio = [it for it in items if it.get("itemType") not in ("note", "attachment", "annotation")]
    if not biblio:
        return {
            "error": "translation-server returned no bibliographic item",
            "item_types": [it.get("itemType") for it in items],
        }
    if len(biblio) > 1:
        return {
            "error": f"translation-server returned {len(biblio)} distinct bibliographic "
            f"items; cannot disambiguate in v1",
            "candidates": [
                {"itemType": it.get("itemType"), "title": it.get("title")} for it in biblio
            ],
        }
    return {"item": dict(biblio[0])}


def _resolve_item_from_identifier(identifier: str) -> dict[str, Any]:
    """Translate an identifier to a single bibliographic item, or return {error}."""
    translation = translate_identifier(identifier)
    if "error" in translation:
        return translation
    return _resolve_biblio_item(translation)


def _find_existing_for_item(item: dict[str, Any], doi: str | None) -> dict[str, Any]:
    """Look up an existing deduped item by DOI; return {existing: item-or-None} or {error}."""
    if not doi:
        return {"existing": None}
    try:
        return {"existing": find_by_doi(doi, title_hint=item.get("title"))}
    except Exception as e:
        return {"error": f"Zotero search failed: {type(e).__name__}: {e}"}


def _merge_into_existing(
    existing: dict[str, Any],
    doi: str | None,
    collection_key: str | None,
    tags: list[str] | None,
) -> dict[str, Any]:
    """Merge requested tags / collection into a deduped existing item.

    Without this, callers who pass tags expecting them to be applied
    discover that dedup silently dropped them.
    """
    existing_data = existing["data"]
    merge_diff: dict[str, Any] = {"tags_added": [], "collection_added": False}
    if tags:
        existing_tag_set = {t.get("tag") for t in existing_data.get("tags", []) if t.get("tag")}
        new_tags = [t for t in tags if t not in existing_tag_set]
        if new_tags:
            existing_data["tags"] = list(existing_data.get("tags", [])) + [{"tag": t} for t in new_tags]
            merge_diff["tags_added"] = new_tags
    if collection_key:
        cols = list(existing_data.get("collections") or [])
        if collection_key not in cols:
            cols.append(collection_key)
            existing_data["collections"] = cols
            merge_diff["collection_added"] = True
    if merge_diff["tags_added"] or merge_diff["collection_added"]:
        try:
            zotero_client().update_item(existing)
        except Exception as e:
            return {
                "error": f"Zotero update_item failed during dedup-merge: {type(e).__name__}: {e}",
                "zotero_key": existing["key"],
            }
    return {
        "zotero_key": existing["key"],
        "was_new": False,
        "doi": doi,
        "title": existing_data.get("title"),
        "itemType": existing_data.get("itemType"),
        "merge_diff": merge_diff,
    }


def _apply_collection_and_tags(
    item: dict[str, Any],
    collection_key: str | None,
    tags: list[str] | None,
) -> None:
    """Apply the requested collection and tags onto a not-yet-created item."""
    if collection_key:
        item["collections"] = list(dict.fromkeys([*item.get("collections", []), collection_key]))
    if tags:
        existing_tags = {t.get("tag") for t in item.get("tags", []) if isinstance(t, dict)}
        for t in tags:
            if t not in existing_tags:
                item.setdefault("tags", []).append({"tag": t})


def _create_new_item(item: dict[str, Any], doi: str | None) -> dict[str, Any]:
    """Create a new Zotero item via pyzotero and normalise the response."""
    try:
        resp = zotero_client().create_items([item])
    except Exception as e:
        return {"error": f"Zotero create failed: {type(e).__name__}: {e}"}

    success = resp.get("success") or resp.get("successful") or {}
    failed = resp.get("failed") or {}
    if failed:
        return {"error": "Zotero create reported failures", "failed": failed}
    if not success:
        return {"error": "Zotero create returned empty success map", "response": resp}
    created = next(iter(success.values()))
    key = created if isinstance(created, str) else (
        created.get("key") or created.get("data", {}).get("key")
    )
    return {
        "zotero_key": key,
        "was_new": True,
        "doi": doi,
        "title": item.get("title"),
        "itemType": item.get("itemType"),
    }


def _persist_item(
    item: dict[str, Any],
    existing: dict[str, Any] | None,
    doi: str | None,
    collection_key: str | None,
    tags: list[str] | None,
) -> dict[str, Any]:
    """Merge into a deduped item when one exists, otherwise create a new one."""
    if existing:
        return _merge_into_existing(existing, doi, collection_key, tags)
    _apply_collection_and_tags(item, collection_key, tags)
    return _create_new_item(item, doi)


def add_to_zotero(
    identifier: str,
    collection_key: str | None = None,
    tags: list[str] | None = None,
) -> dict[str, Any]:
    """Ingest via translation-server + pyzotero. Idempotent on DOI."""
    tag_error = _forbidden_tags_error(tags)
    resolved = tag_error or _resolve_item_from_identifier(identifier)
    if "error" in resolved:
        return resolved
    item = resolved["item"]

    # Dedup
    doi = _doi_from_translation(item)
    lookup = _find_existing_for_item(item, doi)
    if "error" in lookup:
        return lookup
    return _persist_item(item, lookup["existing"], doi, collection_key, tags)


def _item_doi(zotero_key: str) -> str | None:
    """Return the DOI of a stored Zotero item, falling back to its extra field."""
    z = zotero_client()
    item = z.item(zotero_key)
    if not item:
        return None
    data = item.get("data", {})
    doi = data.get("DOI")
    if doi:
        return doi.strip()
    return _doi_from_extra(data.get("extra"))


def _resolve_parent_doi(zotero_key: str) -> dict[str, Any]:
    """Resolve the parent item's DOI for an attach, or return {error}."""
    try:
        parent_doi = _item_doi(zotero_key)
    except Exception as e:
        return {"error": f"could not look up parent item: {type(e).__name__}: {e}"}
    if not parent_doi:
        return {"error": f"parent Zotero item {zotero_key} has no DOI; OA policy cannot be applied"}
    return {"doi": parent_doi}


def _check_oa_gate(parent_doi: str, pdf_url: str) -> dict[str, Any] | None:
    """Enforce the OA whitelist for pdf_url. Return an error dict, or None if allowed."""
    allowed = _allowed_oa_urls(parent_doi)
    if allowed is None:
        # Try a live oa_locate now.
        oa = oa_module.unpaywall_lookup(parent_doi)
        if "error" in oa:
            return {"error": "could not establish OA whitelist for parent item", "oa_lookup_error": oa}
        remember_oa_urls(parent_doi, oa.get("locations", []))
        allowed = _allowed_oa_urls(parent_doi) or set()
    if pdf_url not in allowed:
        return {
            "error": "pdf_url not in OA whitelist for this DOI",
            "doi": parent_doi,
            "hint": "Call oa_locate(doi) first; only URLs Unpaywall identifies as OA may be attached.",
            "allowed_urls_count": len(allowed),
        }
    return None


def _download_pdf(pdf_url: str) -> dict[str, Any]:
    """Download and validate a PDF from pdf_url. Return {content} or {error}."""
    try:
        with http.client(timeout=60.0) as c:
            resp = c.get(pdf_url)
    except Exception as e:
        return {"error": f"download failed: {type(e).__name__}: {e}", "url": pdf_url}
    if resp.status_code >= 400:
        return {"error": f"download HTTP {resp.status_code}", "url": pdf_url}
    content = resp.content
    if not content.startswith(b"%PDF"):
        return {
            "error": "downloaded content is not a PDF",
            "url": pdf_url,
            "content_type": resp.headers.get("content-type"),
            "first_bytes": content[:16].hex(),
        }
    return {"content": content}


def _attach_pdf_bytes(zotero_key: str, content: bytes) -> dict[str, Any]:
    """Write content to a temp file and attach it to the parent.

    Return ``{result, client}`` on a completed call (so the same pyzotero client
    can be reused for follow-up reads), or ``{error}`` if the client or attach fails.
    """
    fd, tmp_path = tempfile.mkstemp(suffix=".pdf", prefix="citation-mcp-")
    try:
        os.write(fd, content)
    finally:
        os.close(fd)

    try:
        z = zotero_client()
        return {"result": z.attachment_simple([tmp_path], zotero_key), "client": z}
    except Exception as e:
        return {"error": f"Zotero attach failed: {type(e).__name__}: {e}"}
    finally:
        try:
            Path(tmp_path).unlink()
        except FileNotFoundError:
            pass


def _attachment_key_from_result(result: dict[str, Any]) -> str | None:
    """Extract a created attachment key from pyzotero's success/successful map."""
    success_map = result.get("success") or result.get("successful") or {}
    if not success_map:
        return None
    first = next(iter(success_map.values()))
    if isinstance(first, str):
        return first
    if isinstance(first, dict):
        return first.get("key") or first.get("data", {}).get("key")
    return None


def _newest_pdf_child_key(z: Any, zotero_key: str) -> str | None:
    """Scan the parent's children for the most recently added PDF attachment key."""
    try:
        children = z.children(zotero_key)
        pdf_children = [
            c for c in children
            if c.get("data", {}).get("itemType") == "attachment"
            and c.get("data", {}).get("contentType") == "application/pdf"
        ]
        if pdf_children:
            pdf_children.sort(
                key=lambda c: c.get("data", {}).get("dateAdded", ""),
                reverse=True,
            )
            return pdf_children[0]["key"]
    except Exception:
        return None
    return None


def _gated_parent_doi(zotero_key: str, pdf_url: str) -> dict[str, Any]:
    """Resolve the parent DOI and enforce the OA gate for pdf_url.

    Return ``{doi}`` when the URL is allowed, otherwise ``{error}``.
    """
    doi_result = _resolve_parent_doi(zotero_key)
    if "error" in doi_result:
        return doi_result
    gate_error = _check_oa_gate(doi_result["doi"], pdf_url)
    return gate_error if gate_error is not None else doi_result


def _prepare_pdf_attach(zotero_key: str, pdf_url: str) -> dict[str, Any]:
    """Run the DOI/OA-gate/download/attach pipeline.

    Return ``{error}`` at the first failing step, otherwise
    ``{parent_doi, content, result, client}`` describing the completed attach call.
    """
    gated = _gated_parent_doi(zotero_key, pdf_url)
    if "error" in gated:
        return gated

    download = _download_pdf(pdf_url)
    if "error" in download:
        return download

    attach = _attach_pdf_bytes(zotero_key, download["content"])
    if "error" in attach:
        return attach
    return {
        "parent_doi": gated["doi"],
        "content": download["content"],
        "result": attach["result"],
        "client": attach["client"],
    }


def attach_pdf(
    zotero_key: str,
    pdf_url: str,
    source_note: str | None = None,
) -> dict[str, Any]:
    """OA-policy-gated PDF attach.

    Refuses to download pdf_url unless that URL appears in a recent oa_locate
    cache entry for the parent item's DOI. Cache TTL 15 minutes.
    """
    prepared = _prepare_pdf_attach(zotero_key, pdf_url)
    if "error" in prepared:
        return prepared
    parent_doi = prepared["parent_doi"]
    content = prepared["content"]
    result = prepared["result"]
    z = prepared["client"]

    # pyzotero's attachment_simple returns various shapes; the response's
    # `success`/`successful` map is sometimes empty on success and the actual
    # attachment key only appears via the parent-children listing. We treat a
    # non-empty `failure`/`failed` map as a definite failure and otherwise
    # confirm by re-fetching the parent's children and finding the most recent
    # PDF attachment.
    failure_map = result.get("failure") or result.get("failed") or {}
    if failure_map:
        return {
            "success": False,
            "parent_zotero_key": zotero_key,
            "doi": parent_doi,
            "source_url": pdf_url,
            "error": "Zotero attach reported failures",
            "failure": failure_map,
        }

    # If pyzotero didn't surface the key directly, scan the parent's children
    # for a recently-added PDF attachment.
    attachment_key = _attachment_key_from_result(result) or _newest_pdf_child_key(z, zotero_key)

    return {
        "success": bool(attachment_key),
        "attachment_key": attachment_key,
        "parent_zotero_key": zotero_key,
        "doi": parent_doi,
        "source_url": pdf_url,
        "source_note": source_note,
        "bytes": len(content),
        "raw_response": result,
    }
