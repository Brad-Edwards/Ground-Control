"""Policy checks: version mirror consistency and documentation coverage.

Extracted from tools/policy/checks.py (issue #1355), which had reached 5,679 lines against
the repo's 500-LOC limit. checks.py remains the entry point and re-exports this module, so
every existing import path and the CLI keep working.

The first cut named each file for the section that began where the previous chunk ended, so
every name described a neighbour's contents. The modules are named for what they hold.
"""

from __future__ import annotations
import argparse
import fnmatch
import hashlib
import json
import os
import posixpath
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Iterable
from .core import (
    REPO_ROOT,
    Violation,
)


RELEASE_PLEASE_CONFIG = "release-please-config.json"


RELEASE_PLEASE_MANIFEST = ".release-please-manifest.json"


# The single Ground Control root component's key in both the config and the manifest.
RELEASE_PLEASE_ROOT_PACKAGE = "."


# release-please "generic" updater annotation (string-form extra-files), e.g.
#   version = "1.0.1" // x-release-please-version   (backend/build.gradle.kts)
_GENERIC_VERSION_ANNOTATION = "x-release-please-version"


_QUOTED_VERSION_RE = re.compile(r"""["'](\d+\.\d+\.\d+[0-9A-Za-z.\-+]*)["']""")


# Characters that terminate a plain (dot-navigated) JSONPath key segment.
_JSONPATH_DELIMS = ".["


class _ShortCircuit(Exception):
    """Internal signal carrying the single Violation a check should emit and stop.

    It lets the failure gates of a check raise instead of threading an error value
    back through every caller, which keeps each function within the return-count
    and complexity limits without changing the check's observable behaviour.
    """

    def __init__(self, violation: Violation) -> None:
        super().__init__(violation.message)
        self.violation = violation


def _scan_plain_key(text: str, start: int) -> int:
    """Return the index after a plain key beginning at ``start`` in ``text``.

    Scanning stops at the next JSONPath delimiter (``.`` or ``[``) or the end of
    the string.
    """
    j, n = start, len(text)
    while j < n and text[j] not in _JSONPATH_DELIMS:
        j += 1
    return j


def _parse_bracket_key(text: str, start: int) -> tuple[str, int] | None:
    """Parse a ``[...]`` segment at ``start``; return ``(key, next_index)`` or ``None``.

    A quoted inner value has its matching surrounding quotes stripped. Returns
    ``None`` when the closing bracket is absent.
    """
    close = text.find("]", start)
    if close == -1:
        return None
    inner = text[start + 1 : close].strip()
    if len(inner) >= 2 and inner[0] in "\"'" and inner[-1] == inner[0]:
        inner = inner[1:-1]
    return inner, close + 1


def _jsonpath_keys(jsonpath: str) -> list[str] | None:
    """Tokenize a minimal JSONPath into an ordered key list.

    Supports the forms release-please emits for JSON extra-files: ``$.version``,
    ``$.a.b``, and bracketed keys including the empty root-package key
    ``$.packages[''].version`` / ``$.packages[""].version``. Returns ``None`` for a
    path this resolver cannot parse.
    """
    s = jsonpath.strip()
    if s.startswith("$"):
        s = s[1:]
    keys: list[str] = []
    i, n = 0, len(s)
    while i < n:
        if s[i] == "[":
            parsed = _parse_bracket_key(s, i)
            if parsed is None:
                return None
            key, i = parsed
        else:
            start = i + 1 if s[i] == "." else i
            end = _scan_plain_key(s, start)
            if end == start:
                return None
            key, i = s[start:end], end
        keys.append(key)
    return keys or None


def _extract_json_version(data: object, jsonpath: str) -> str | None:
    """Return the string value at ``jsonpath`` inside parsed JSON ``data``.

    Returns ``None`` when the path cannot be parsed, the keys do not resolve to a
    value, or the resolved value is not a string.
    """
    keys = _jsonpath_keys(jsonpath)
    if keys is None:
        return None
    cur: object = data
    for key in keys:
        if isinstance(cur, dict) and key in cur:
            cur = cur[key]
        else:
            return None
    return cur if isinstance(cur, str) else None


def _extract_generic_version(text: str) -> str | None:
    """Return the version from the first ``x-release-please-version`` annotated line.

    Returns ``None`` when no annotated line carries a quoted semantic version.
    """
    for line in text.splitlines():
        if _GENERIC_VERSION_ANNOTATION in line:
            match = _QUOTED_VERSION_RE.search(line)
            if match:
                return match.group(1)
    return None


def _drift(message: str, details: list[str] | None = None) -> Violation:
    """Build a ``version-mirror-drift`` Violation with optional detail lines."""
    return Violation(code="version-mirror-drift", message=message, details=details or [])


def _require_release_please_pair(config_path: Path, manifest_path: Path) -> None:
    """Raise ``_ShortCircuit`` when only one of the config/manifest pair is present."""
    if config_path.exists() and manifest_path.exists():
        return
    missing = RELEASE_PLEASE_CONFIG if not config_path.exists() else RELEASE_PLEASE_MANIFEST
    raise _ShortCircuit(
        Violation(
            code="version-mirror-config-missing",
            message=(
                "Release Please version management is partially configured: "
                f"{missing} is missing (config and manifest must ship together)."
            ),
            details=[],
        )
    )


def _parse_release_please_files(config_path: Path, manifest_path: Path) -> tuple[Any, Any]:
    """Return ``(manifest, config)`` parsed from JSON, raising on read/parse failure."""
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise _ShortCircuit(
            Violation(
                code="version-mirror-config-invalid",
                message="release-please config/manifest could not be parsed.",
                details=[str(exc)],
            )
        ) from exc
    return manifest, config


def _require_manifest_version(manifest: Any) -> str:
    """Return the root-package version string, raising when it is absent or non-string."""
    manifest_version = manifest.get(RELEASE_PLEASE_ROOT_PACKAGE)
    if not isinstance(manifest_version, str):
        raise _ShortCircuit(
            Violation(
                code="version-mirror-config-invalid",
                message=(
                    f"{RELEASE_PLEASE_MANIFEST} has no string version for the root "
                    f'package "{RELEASE_PLEASE_ROOT_PACKAGE}".'
                ),
                details=[],
            )
        )
    return manifest_version


def _load_version_mirror_context(root: Path) -> tuple[str | None, list[Any]]:
    """Load the manifest version and the declared mirror inventory.

    Returns ``(None, [])`` when Release Please is not adopted (nothing to enforce).
    Otherwise returns ``(manifest_version, extra_files)``. Raises ``_ShortCircuit``
    carrying a configuration Violation when the config/manifest pair is partial,
    unparseable, or missing a root-package version.
    """
    config_path = root / RELEASE_PLEASE_CONFIG
    manifest_path = root / RELEASE_PLEASE_MANIFEST

    # No Release Please adoption in this repo -> nothing to enforce.
    if not config_path.exists() and not manifest_path.exists():
        return None, []

    _require_release_please_pair(config_path, manifest_path)
    manifest, config = _parse_release_please_files(config_path, manifest_path)
    manifest_version = _require_manifest_version(manifest)
    package = (config.get("packages") or {}).get(RELEASE_PLEASE_ROOT_PACKAGE) or {}
    extra_files = package.get("extra-files") or []
    return manifest_version, extra_files


def _parse_extra_file_entry(entry: Any) -> tuple[str, str | None, str] | None:
    """Return ``(path, jsonpath, kind)`` for an extra-files entry, or ``None`` to skip.

    ``None`` covers a malformed entry (neither string nor object) and an entry
    with no ``path``.
    """
    if isinstance(entry, str):
        path, jsonpath, kind = entry, None, "generic"
    elif isinstance(entry, dict):
        path = entry.get("path")
        jsonpath = entry.get("jsonpath")
        kind = entry.get("type", "generic")
    else:
        return None
    if not path:
        return None
    return path, jsonpath, kind


def _read_mirror_text(target: Path, path: str) -> str:
    """Return a mirror file's text, raising ``_ShortCircuit`` when missing/unreadable."""
    if not target.exists():
        raise _ShortCircuit(_drift(f"Release Please version mirror is missing: {path}"))
    try:
        return target.read_text(encoding="utf-8")
    except OSError as exc:
        raise _ShortCircuit(_drift(f"cannot read version mirror {path}", [str(exc)])) from exc


def _extract_mirror_version(
    text: str, kind: str, jsonpath: str | None, path: str
) -> tuple[str | None, str]:
    """Return ``(found_version, label)`` for a mirror, raising on invalid JSON."""
    if kind == "json":
        resolved_path = jsonpath or "$.version"
        try:
            data = json.loads(text)
        except json.JSONDecodeError as exc:
            raise _ShortCircuit(_drift(f"{path} is not valid JSON", [str(exc)])) from exc
        return _extract_json_version(data, resolved_path), f"{path} ({resolved_path})"
    return _extract_generic_version(text), f"{path} (x-release-please-version)"


def _version_drift_violations(
    found: str | None, label: str, manifest_version: str
) -> list[Violation]:
    """Return drift Violations comparing a mirror's version to the manifest version."""
    if found is None:
        return [
            _drift(
                f"could not read a product version from mirror {label}",
                [f"expected manifest version: {manifest_version}"],
            )
        ]
    if found != manifest_version:
        return [
            _drift(
                f"Product-version mirror {label} is {found}, but the Release "
                f"Please manifest ({RELEASE_PLEASE_MANIFEST}) says {manifest_version}. "
                "Mirrors are updated by the release PR; do not hand-edit them out of sync."
            )
        ]
    return []


def _version_mirror_entry_violations(
    entry: Any, root: Path, manifest_version: str
) -> list[Violation]:
    """Return the drift Violations (zero or one) for a single extra-files entry."""
    parsed = _parse_extra_file_entry(entry)
    if parsed is None:
        return []
    try:
        path, jsonpath, kind = parsed
        text = _read_mirror_text(root / path, path)
        found, label = _extract_mirror_version(text, kind, jsonpath, path)
        return _version_drift_violations(found, label, manifest_version)
    except _ShortCircuit as exc:
        return [exc.violation]


def run_version_mirror_consistency_check(root: Path = REPO_ROOT) -> list[Violation]:
    """Fail when a product-version mirror drifts from the Release Please manifest.

    The set of mirrors is read from the release-please config's ``extra-files`` for
    the root package (the declarative inventory), so this check never carries its own
    hard-coded mirror list.
    """
    try:
        manifest_version, extra_files = _load_version_mirror_context(root)
    except _ShortCircuit as exc:
        return [exc.violation]

    if manifest_version is None:
        return []

    violations: list[Violation] = []
    for entry in extra_files:
        violations.extend(_version_mirror_entry_violations(entry, root, manifest_version))
    return violations


_DOCUMENTATION_COVERAGE_FIXTURE = REPO_ROOT / "tools" / "documentation_coverage_fixture.mjs"


_DOCUMENTATION_SECTION_RE = re.compile(r"^##\s+Documentation\b", re.MULTILINE)


def _fixture_error(message: str, details: list[str] | None = None) -> Violation:
    """Build a ``doc-coverage-fixture-error`` Violation (a drift signal, not a pass)."""
    return Violation(code="doc-coverage-fixture-error", message=message, details=details or [])


def _require_coverage_fixture() -> None:
    """Raise ``_ShortCircuit`` when the Node classifier fixture is absent."""
    if not _DOCUMENTATION_COVERAGE_FIXTURE.exists():
        raise _ShortCircuit(
            _fixture_error(
                "documentation_coverage_fixture.mjs not found — "
                "documentation coverage check cannot run.",
                [f"expected at {_DOCUMENTATION_COVERAGE_FIXTURE}"],
            )
        )


def _invoke_coverage_fixture(fixture_input: dict[str, Any], root: Path) -> Any:
    """Run the Node classifier fixture and return its parsed JSON result.

    Raises ``_ShortCircuit`` carrying a ``doc-coverage-fixture-error`` Violation when
    the fixture cannot execute, exits non-zero, or emits invalid JSON.
    """
    try:
        proc = subprocess.run(
            ["node", str(_DOCUMENTATION_COVERAGE_FIXTURE)],
            input=json.dumps(fixture_input),
            capture_output=True,
            text=True,
            cwd=str(root),
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise _ShortCircuit(
            _fixture_error(f"documentation_coverage_fixture.mjs failed to execute: {exc}")
        ) from exc

    if proc.returncode != 0:
        details = [f"stderr: {proc.stderr.strip()[:500]}"] if proc.stderr.strip() else []
        raise _ShortCircuit(
            _fixture_error(
                "documentation_coverage_fixture.mjs exited with non-zero status.", details
            )
        )

    try:
        return json.loads(proc.stdout)
    except (json.JSONDecodeError, TypeError) as exc:
        raise _ShortCircuit(
            _fixture_error("documentation_coverage_fixture.mjs produced invalid JSON output.")
        ) from exc


def _load_documentation_coverage_result(
    changed_files: list[str], root: Path
) -> tuple[Any, list[Violation]]:
    """Run the classifier fixture and return ``(result, violations)``.

    ``result`` is ``None`` when the check should not compare against a PR body: an
    empty ``violations`` list means skip gracefully (``node`` unavailable), a
    non-empty list carries a fixture error. A non-``None`` ``result`` is the parsed
    classifier output.
    """
    import shutil

    if shutil.which("node") is None:
        return None, []

    try:
        _require_coverage_fixture()
        fixture_input = {
            "repo_path": str(root),
            "changed_paths": list(changed_files),
        }
        return _invoke_coverage_fixture(fixture_input, root), []
    except _ShortCircuit as exc:
        return None, [exc.violation]


def _documentation_outcome_details(result: Any) -> list[str]:
    """Build the detail lines (classified surfaces, suggested targets) for the outcome."""
    surface_classes = sorted({
        c["surface_class"]
        for c in result.get("classifications", [])
        if c.get("surface_class") not in ("doc", "unclassified")
    })
    suggested = result.get("suggested_doc_targets", [])
    details = []
    if surface_classes:
        details.append(f"classified surfaces: {', '.join(surface_classes)}")
    if suggested:
        details.append(f"suggested doc targets: {', '.join(suggested)}")
    return details


def _documentation_outcome_violations(result: Any, pr_body: str | None) -> list[Violation]:
    """Return the outcome-missing Violation when a documented surface lacks the section.

    An empty list means no outcome is required, the PR body is unavailable (skip
    gracefully), or the PR body already carries a ``## Documentation`` section.
    """
    if not result.get("outcome_required") or pr_body is None:
        return []
    if _DOCUMENTATION_SECTION_RE.search(pr_body):
        return []
    return [
        Violation(
            code="doc-coverage-outcome-missing",
            message=(
                "Diff touches a documented surface but the PR body has no "
                "## Documentation section. Add a documentation_outcome field "
                "when calling gc_render_pr_body (ADR-054)."
            ),
            details=_documentation_outcome_details(result),
        )
    ]


def run_documentation_coverage_check(
    changed_files: list[str],
    root: Path = REPO_ROOT,
    pr_body: str | None = None,
) -> list[Violation]:
    """Classify the diff and verify the PR body carries a documentation outcome.

    Violation codes:
    - ``doc-coverage-outcome-missing``: a classified surface requires a
      documentation outcome but the PR body has no ``## Documentation`` section.
    - ``doc-coverage-fixture-error``: the Node classifier fixture failed
      (treat as a drift signal, not a pass).

    The check skips gracefully when ``node`` is unavailable or the PR body
    cannot be resolved (matches the changelog-fragment check style).
    """
    result, violations = _load_documentation_coverage_result(changed_files, root)
    if result is None:
        return violations
    return _documentation_outcome_violations(result, pr_body)
