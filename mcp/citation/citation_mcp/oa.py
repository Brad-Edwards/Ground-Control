"""Unpaywall OA-location lookup."""

from __future__ import annotations

from typing import Any

from . import http


def unpaywall_lookup(doi: str) -> dict[str, Any]:
    """Return {is_oa, oa_status, locations: [...], doi} or {error}."""
    doi = doi.strip()
    if doi.startswith("https://doi.org/"):
        doi = doi[len("https://doi.org/"):]
    url = f"https://api.unpaywall.org/v2/{doi}"
    params = {"email": http.mailto()}
    with http.client() as c:
        resp = c.get(url, params=params)
    if resp.status_code == 404:
        return {"error": f"Unpaywall has no record of DOI {doi}", "doi": doi}
    if resp.status_code >= 400:
        return {"error": f"Unpaywall HTTP {resp.status_code} for DOI {doi}", "doi": doi}
    data = resp.json()
    locations: list[dict[str, Any]] = []
    for loc in data.get("oa_locations") or []:
        locations.append(
            {
                "host_type": loc.get("host_type"),
                "version": loc.get("version"),
                "url": loc.get("url"),
                "url_for_pdf": loc.get("url_for_pdf"),
                "url_for_landing_page": loc.get("url_for_landing_page"),
                "license": loc.get("license"),
                "is_best": loc == data.get("best_oa_location"),
            }
        )
    return {
        "doi": doi,
        "is_oa": data.get("is_oa", False),
        "oa_status": data.get("oa_status"),
        "title": data.get("title"),
        "year": data.get("year"),
        "journal": data.get("journal_name"),
        "locations": locations,
    }
