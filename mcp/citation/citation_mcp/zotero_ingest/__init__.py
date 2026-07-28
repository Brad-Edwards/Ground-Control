"""zotero_ingest.py -- package barrel.

Implementation lives in this package, split under issue #1467 for the
500-LOC limit (docs/CODING_STANDARDS.md). Modules are packed from the
declaration dependency graph in topological order, so the package graph is
acyclic by construction. Every top-level name the module exposed before is
re-exported here -- including the underscore-prefixed helpers a star import
would drop -- so existing callers are unaffected.
"""

from .translation_server_url import translation_server_url, zotero_client, _format_creators, _year_from_date, _doi_from_extra, _normalise_zotero_item, search_zotero, _OA_CACHE, _OA_CACHE_TTL, remember_oa_urls, _allowed_oa_urls, _translation_status_error, _translation_items, translate_identifier, _first_matching_item, find_by_doi, _doi_from_translation, _forbidden_tags_error, _resolve_biblio_item, _resolve_item_from_identifier, _find_existing_for_item, _merge_into_existing, _apply_collection_and_tags, _create_new_item  # noqa: F401
from .persist_item import _persist_item, add_to_zotero, _item_doi, _resolve_parent_doi, _check_oa_gate, _download_pdf, _attach_pdf_bytes, _attachment_key_from_result, _newest_pdf_child_key, _gated_parent_doi, _prepare_pdf_attach, attach_pdf  # noqa: F401

__all__ = [
    "translation_server_url",
    "zotero_client",
    "_format_creators",
    "_year_from_date",
    "_normalise_zotero_item",
    "search_zotero",
    "_OA_CACHE",
    "_OA_CACHE_TTL",
    "remember_oa_urls",
    "_allowed_oa_urls",
    "_translation_status_error",
    "_translation_items",
    "translate_identifier",
    "_first_matching_item",
    "find_by_doi",
    "_doi_from_extra",
    "_doi_from_translation",
    "_forbidden_tags_error",
    "_resolve_biblio_item",
    "_resolve_item_from_identifier",
    "_find_existing_for_item",
    "_merge_into_existing",
    "_apply_collection_and_tags",
    "_create_new_item",
    "_persist_item",
    "add_to_zotero",
    "_item_doi",
    "_resolve_parent_doi",
    "_check_oa_gate",
    "_download_pdf",
    "_attach_pdf_bytes",
    "_attachment_key_from_result",
    "_newest_pdf_child_key",
    "_gated_parent_doi",
    "_prepare_pdf_attach",
    "attach_pdf",
]
