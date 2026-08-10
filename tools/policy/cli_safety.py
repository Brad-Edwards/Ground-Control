"""Policy checks: agentic-workflow CLI input validators.

bin/policy is a CLI invoked inside an agentic workflow (Claude / Codex / CI), so
a path or git-ref argument is data an agent could be manipulated into supplying
(pythonsecurity:S8705 argument injection, S8707 path injection). These
validators sanitize such CLI-derived values at the boundary, before they reach a
subprocess or a filesystem call.
"""

from __future__ import annotations
import os
import re
import tempfile
from pathlib import Path

from .core import REPO_ROOT


_GIT_REF_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._/@^~{}-]*$")


def validate_git_ref(ref: str) -> str:
    """Return ``ref`` if it is a safe git ref, else raise ``ValueError``.

    ``--base`` reaches ``git`` as an argv element (no shell), so the residual
    risk is option injection: a value beginning with ``-`` (e.g.
    ``--upload-pack=...``) is parsed by git as an option rather than a ref. The
    leading-alphanumeric charset forecloses that while accepting real refs
    (``origin/dev``, ``HEAD~1``, ``HEAD^``).
    """
    if not isinstance(ref, str) or not _GIT_REF_RE.match(ref):
        raise ValueError(f"unsafe git ref: {ref!r}")
    return ref


def _cli_path_allowed_roots() -> list[str]:
    """Canonical trusted roots a CLI-supplied path may resolve within.

    The repo checkout and the current directory cover local and pre-push
    drivers; the system temp dir plus the CI-provided ``RUNNER_TEMP`` /
    ``GITHUB_WORKSPACE`` / ``GITHUB_EVENT_PATH`` locations cover the GitHub
    Actions driver, whose event payload and scratch files legitimately live
    outside the repo checkout.
    """
    roots = [
        os.path.realpath(REPO_ROOT),
        os.path.realpath(os.getcwd()),
        os.path.realpath(tempfile.gettempdir()),
    ]
    for env in ("RUNNER_TEMP", "GITHUB_WORKSPACE"):
        value = os.getenv(env)
        if value:
            roots.append(os.path.realpath(value))
    event_path = os.getenv("GITHUB_EVENT_PATH")
    if event_path:
        roots.append(os.path.realpath(os.path.dirname(event_path)))
    return roots


def safe_cli_path(path: str) -> Path:
    """Canonicalize a CLI-supplied file path and confine it to trusted roots.

    Resolves ``../`` and symlinks with ``realpath`` first, then requires the
    result to sit within a trusted root (see ``_cli_path_allowed_roots``) so an
    agent-supplied argument cannot read or write arbitrary files
    (pythonsecurity:S8707). The prefix test appends ``os.sep`` to defeat the
    partial-path-traversal bypass (``/base/dirmalicious`` vs ``/base/dir``).
    """
    resolved = os.path.realpath(path)
    for root in _cli_path_allowed_roots():
        if resolved == root or resolved.startswith(root + os.sep):
            return Path(resolved)
    raise ValueError(f"path {path!r} resolves outside the allowed directories")
