"""Split from zotero_ingest.py under issue #1467 for the 500-LOC limit
(docs/CODING_STANDARDS.md). Definitions are unchanged.
"""

import os
import tempfile
from pathlib import Path
from typing import Any
from . import http
from . import oa as oa_module
from .translation_server_url import _allowed_oa_urls, _apply_collection_and_tags, _create_new_item, _doi_from_extra, _doi_from_translation, _find_existing_for_item, _forbidden_tags_error, _merge_into_existing, _resolve_item_from_identifier, remember_oa_urls, zotero_client


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
