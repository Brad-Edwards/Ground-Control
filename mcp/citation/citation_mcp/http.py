"""Shared HTTP client with polite-pool mailto for Crossref / OpenAlex / Unpaywall."""

from __future__ import annotations

import os

import httpx

USER_AGENT = "Citation-MCP/0.1 (+https://github.com/KeplerOps/Ground-Control; mailto:{mailto})"
DEFAULT_TIMEOUT = httpx.Timeout(30.0, connect=10.0)


def mailto() -> str:
    """Return the contact email for the polite-pool mailto parameter."""
    return os.environ.get("CITATION_MCP_MAILTO", "j.bradley.edwards@gmail.com")


def client(timeout: httpx.Timeout | float | None = None) -> httpx.Client:
    """Return a configured httpx.Client. Caller closes it."""
    return httpx.Client(
        headers={"User-Agent": USER_AGENT.format(mailto=mailto())},
        timeout=timeout or DEFAULT_TIMEOUT,
        follow_redirects=True,
    )
