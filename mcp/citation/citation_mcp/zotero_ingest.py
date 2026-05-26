"""Zotero ingest via translation-server + pyzotero, plus OA-policy-gated PDF attach."""

from __future__ import annotations

import os
import tempfile
import time
from pathlib import Path
from typing import Any

import httpx

from . import http
from . import oa as oa_module
from .resolve import parse_identifier


def translation_server_url() -> str:
    return os.environ.get("TRANSLATION_SERVER_URL", "http://localhost:1969").rstrip("/")


def zotero_client():
    """Return a pyzotero client. Raises if env vars are missing."""
    library_id = os.environ.get("PERSONAL_ZOTERO_ID")
    api_key = os.environ.get("PERSONAL_ZOTERO_KEY")
    if not library_id or not api_key:
        raise RuntimeError("PERSONAL_ZOTERO_ID / PERSONAL_ZOTERO_KEY not set in environment")
    from pyzotero import zotero

    return zotero.Zotero(library_id, "user", api_key)


def _normalise_zotero_item(item: dict[str, Any]) -> dict[str, Any]:
    """Reduce a pyzotero item dict to a stable result shape."""
    data = item.get("data", {})
    creators: list[str] = []
    for c in data.get("creators") or []:
        if c.get("name"):
            creators.append(c["name"])
        elif c.get("lastName") or c.get("firstName"):
            creators.append(f"{c.get('lastName','')}, {c.get('firstName','')}".strip(", "))
    year = None
    date = data.get("date") or ""
    for token in date.replace("-", " ").split():
        if token.isdigit() and len(token) == 4:
            year = int(token)
            break
    doi = data.get("DOI")
    if not doi and (data.get("extra") or ""):
        for line in data["extra"].splitlines():
            if line.strip().lower().startswith("doi:"):
                doi = line.split(":", 1)[1].strip()
                break
    return {
        "zotero_key": item.get("key"),
        "title": data.get("title"),
        "creators": creators,
        "year": year,
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
    urls: set[str] = set()
    for loc in locations or []:
        for k in ("url", "url_for_pdf", "url_for_landing_page"):
            if loc.get(k):
                urls.add(loc[k])
    _OA_CACHE[doi.strip().lower()] = (time.time(), urls)


def _allowed_oa_urls(doi: str) -> set[str] | None:
    entry = _OA_CACHE.get(doi.strip().lower())
    if not entry:
        return None
    ts, urls = entry
    if time.time() - ts > _OA_CACHE_TTL:
        _OA_CACHE.pop(doi.strip().lower(), None)
        return None
    return urls


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

    if resp.status_code == 501:
        return {"error": "translation-server could not handle identifier", "identifier": identifier}
    if resp.status_code == 300:
        return {"error": "translation-server returned multiple items requiring selection (not handled in v1)"}
    if resp.status_code >= 400:
        return {"error": f"translation-server HTTP {resp.status_code}", "body": resp.text[:300]}
    try:
        items = resp.json()
    except Exception as e:
        return {"error": f"translation-server returned non-JSON: {e}"}
    if not isinstance(items, list) or not items:
        return {"error": "translation-server returned no items"}
    return {"items": items}


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

    def _check(item):
        data = item.get("data", {})
        if data.get("itemType") == "attachment":
            return False
        if (data.get("DOI") or "").lower() == doi:
            return True
        if doi in (data.get("extra") or "").lower():
            return True
        return False

    # Primary: title-based search, verify DOI in candidates.
    if title_hint:
        try:
            for item in z.items(q=title_hint, qmode="titleCreatorYear", limit=25):
                if _check(item):
                    return item
        except Exception:
            pass

    # Fallback: DOI suffix (after the "/"). Often matches URL fields containing
    # https://doi.org/<suffix>.
    suffix = doi.split("/", 1)[1] if "/" in doi else doi
    try:
        for item in z.items(q=suffix, qmode="everything", limit=25):
            if _check(item):
                return item
    except Exception:
        pass

    # Last resort: raw DOI.
    try:
        for item in z.items(q=doi, qmode="everything", limit=25):
            if _check(item):
                return item
    except Exception:
        pass

    return None


def _doi_from_translation(item: dict[str, Any]) -> str | None:
    doi = item.get("DOI") or item.get("doi")
    if doi:
        return doi.strip()
    # Some translators put DOI in extra
    extra = item.get("extra") or ""
    for line in extra.splitlines():
        if line.strip().lower().startswith("doi:"):
            return line.split(":", 1)[1].strip()
    return None


def add_to_zotero(
    identifier: str,
    collection_key: str | None = None,
    tags: list[str] | None = None,
) -> dict[str, Any]:
    """Ingest via translation-server + pyzotero. Idempotent on DOI."""
    if tags:
        forbidden = {t for t in tags if t.lower() in ("read", "unread")}
        if forbidden:
            return {"error": f"forbidden tags: {sorted(forbidden)} (read-status tags not allowed)"}

    translation = translate_identifier(identifier)
    if "error" in translation:
        return translation
    items = translation["items"]
    # translation-server can return auxiliary entries — standalone notes or
    # attachments — alongside the bibliographic record (the arXiv translator,
    # for one, emits the paper's "comment" as a separate note). Those are not
    # citations; keep only the real bibliographic item(s).
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
    item = dict(biblio[0])

    # Dedup
    doi = _doi_from_translation(item)
    existing = None
    if doi:
        try:
            existing = find_by_doi(doi, title_hint=item.get("title"))
        except Exception as e:
            return {"error": f"Zotero search failed: {type(e).__name__}: {e}"}
    if existing:
        # Merge any requested tags / collection into the existing item.
        # Without this, callers who pass tags expecting them to be applied
        # discover that dedup silently dropped them.
        existing_data = existing["data"]
        merge_diff = {"tags_added": [], "collection_added": False}
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
                z = zotero_client()
                z.update_item(existing)
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

    # Apply collection + tags
    if collection_key:
        item["collections"] = list({*item.get("collections", []), collection_key})
    if tags:
        existing_tags = {t.get("tag") for t in item.get("tags", []) if isinstance(t, dict)}
        for t in tags:
            if t not in existing_tags:
                item.setdefault("tags", []).append({"tag": t})

    # pyzotero create
    try:
        z = zotero_client()
        resp = z.create_items([item])
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


def _item_doi(zotero_key: str) -> str | None:
    z = zotero_client()
    item = z.item(zotero_key)
    if not item:
        return None
    data = item.get("data", {})
    doi = data.get("DOI")
    if doi:
        return doi.strip()
    extra = data.get("extra") or ""
    for line in extra.splitlines():
        if line.strip().lower().startswith("doi:"):
            return line.split(":", 1)[1].strip()
    return None


def attach_pdf(
    zotero_key: str,
    pdf_url: str,
    source_note: str | None = None,
) -> dict[str, Any]:
    """OA-policy-gated PDF attach.

    Refuses to download pdf_url unless that URL appears in a recent oa_locate
    cache entry for the parent item's DOI. Cache TTL 15 minutes.
    """
    try:
        parent_doi = _item_doi(zotero_key)
    except Exception as e:
        return {"error": f"could not look up parent item: {type(e).__name__}: {e}"}
    if not parent_doi:
        return {"error": f"parent Zotero item {zotero_key} has no DOI; OA policy cannot be applied"}

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

    # Download
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

    fd, tmp_path = tempfile.mkstemp(suffix=".pdf", prefix="citation-mcp-")
    try:
        os.write(fd, content)
    finally:
        os.close(fd)

    try:
        z = zotero_client()
        result = z.attachment_simple([tmp_path], zotero_key)
    except Exception as e:
        return {"error": f"Zotero attach failed: {type(e).__name__}: {e}"}
    finally:
        try:
            Path(tmp_path).unlink()
        except FileNotFoundError:
            pass

    # pyzotero's attachment_simple returns various shapes; the response's
    # `success`/`successful` map is sometimes empty on success and the actual
    # attachment key only appears via the parent-children listing. We treat a
    # non-empty `failure`/`failed` map as a definite failure and otherwise
    # confirm by re-fetching the parent's children and finding the most recent
    # PDF attachment.
    success_map = result.get("success") or result.get("successful") or {}
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

    attachment_key: str | None = None
    if success_map:
        first = next(iter(success_map.values()))
        if isinstance(first, str):
            attachment_key = first
        elif isinstance(first, dict):
            attachment_key = first.get("key") or first.get("data", {}).get("key")

    # If pyzotero didn't surface the key directly, scan the parent's children
    # for a recently-added PDF attachment.
    if not attachment_key:
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
                attachment_key = pdf_children[0]["key"]
        except Exception:
            pass

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
