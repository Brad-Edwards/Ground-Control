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
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable
from .migration_policy import (
    RELEASE_PLEASE_CONFIG,
    RELEASE_PLEASE_MANIFEST,
    RELEASE_PLEASE_ROOT_PACKAGE,
    _extract_generic_version,
    _extract_json_version,
)
from .core import (
    REPO_ROOT,
    Violation,
)


def run_version_mirror_consistency_check(root: Path = REPO_ROOT) -> list[Violation]:
    """Fail when a product-version mirror drifts from the Release Please manifest.

    The set of mirrors is read from the release-please config's ``extra-files`` for
    the root package (the declarative inventory), so this check never carries its own
    hard-coded mirror list.
    """
    config_path = root / RELEASE_PLEASE_CONFIG
    manifest_path = root / RELEASE_PLEASE_MANIFEST

    # No Release Please adoption in this repo -> nothing to enforce.
    if not config_path.exists() and not manifest_path.exists():
        return []

    if not config_path.exists() or not manifest_path.exists():
        missing = RELEASE_PLEASE_CONFIG if not config_path.exists() else RELEASE_PLEASE_MANIFEST
        return [
            Violation(
                code="version-mirror-config-missing",
                message=(
                    "Release Please version management is partially configured: "
                    f"{missing} is missing (config and manifest must ship together)."
                ),
                details=[],
            )
        ]

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return [
            Violation(
                code="version-mirror-config-invalid",
                message="release-please config/manifest could not be parsed.",
                details=[str(exc)],
            )
        ]

    manifest_version = manifest.get(RELEASE_PLEASE_ROOT_PACKAGE)
    if not isinstance(manifest_version, str):
        return [
            Violation(
                code="version-mirror-config-invalid",
                message=(
                    f"{RELEASE_PLEASE_MANIFEST} has no string version for the root "
                    f'package "{RELEASE_PLEASE_ROOT_PACKAGE}".'
                ),
                details=[],
            )
        ]

    package = (config.get("packages") or {}).get(RELEASE_PLEASE_ROOT_PACKAGE) or {}
    extra_files = package.get("extra-files") or []

    violations: list[Violation] = []
    for entry in extra_files:
        if isinstance(entry, str):
            path, jsonpath, kind = entry, None, "generic"
        elif isinstance(entry, dict):
            path = entry.get("path")
            jsonpath = entry.get("jsonpath")
            kind = entry.get("type", "generic")
        else:
            continue
        if not path:
            continue

        target = root / path
        if not target.exists():
            violations.append(
                Violation(
                    code="version-mirror-drift",
                    message=f"Release Please version mirror is missing: {path}",
                    details=[],
                )
            )
            continue
        try:
            text = target.read_text(encoding="utf-8")
        except OSError as exc:
            violations.append(
                Violation(
                    code="version-mirror-drift",
                    message=f"cannot read version mirror {path}",
                    details=[str(exc)],
                )
            )
            continue

        if kind == "json":
            resolved_path = jsonpath or "$.version"
            try:
                data = json.loads(text)
            except json.JSONDecodeError as exc:
                violations.append(
                    Violation(
                        code="version-mirror-drift",
                        message=f"{path} is not valid JSON",
                        details=[str(exc)],
                    )
                )
                continue
            found = _extract_json_version(data, resolved_path)
            label = f"{path} ({resolved_path})"
        else:
            found = _extract_generic_version(text)
            label = f"{path} (x-release-please-version)"

        if found is None:
            violations.append(
                Violation(
                    code="version-mirror-drift",
                    message=f"could not read a product version from mirror {label}",
                    details=[f"expected manifest version: {manifest_version}"],
                )
            )
        elif found != manifest_version:
            violations.append(
                Violation(
                    code="version-mirror-drift",
                    message=(
                        f"Product-version mirror {label} is {found}, but the Release "
                        f"Please manifest ({RELEASE_PLEASE_MANIFEST}) says {manifest_version}. "
                        "Mirrors are updated by the release PR; do not hand-edit them out of sync."
                    ),
                    details=[],
                )
            )

    return violations


_DOCUMENTATION_COVERAGE_FIXTURE = REPO_ROOT / "tools" / "documentation_coverage_fixture.mjs"


_DOCUMENTATION_SECTION_RE = re.compile(r"^##\s+Documentation\b", re.MULTILINE)


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
    import shutil

    if shutil.which("node") is None:
        return []

    if not _DOCUMENTATION_COVERAGE_FIXTURE.exists():
        return [
            Violation(
                code="doc-coverage-fixture-error",
                message=(
                    "documentation_coverage_fixture.mjs not found — "
                    "documentation coverage check cannot run."
                ),
                details=[f"expected at {_DOCUMENTATION_COVERAGE_FIXTURE}"],
            )
        ]

    fixture_input = {
        "repo_path": str(root),
        "changed_paths": list(changed_files),
    }
    try:
        proc = subprocess.run(
            ["node", str(_DOCUMENTATION_COVERAGE_FIXTURE)],
            input=json.dumps(fixture_input),
            capture_output=True,
            text=True,
            cwd=str(root),
            timeout=30,
        )
    except Exception as exc:  # noqa: BLE001
        return [
            Violation(
                code="doc-coverage-fixture-error",
                message=f"documentation_coverage_fixture.mjs failed to execute: {exc}",
                details=[],
            )
        ]

    if proc.returncode != 0:
        return [
            Violation(
                code="doc-coverage-fixture-error",
                message="documentation_coverage_fixture.mjs exited with non-zero status.",
                details=[f"stderr: {proc.stderr.strip()[:500]}"] if proc.stderr.strip() else [],
            )
        ]

    try:
        result = json.loads(proc.stdout)
    except Exception:  # noqa: BLE001
        return [
            Violation(
                code="doc-coverage-fixture-error",
                message="documentation_coverage_fixture.mjs produced invalid JSON output.",
                details=[],
            )
        ]

    if not result.get("outcome_required"):
        return []

    # outcome_required is true — check the PR body for a ## Documentation section.
    if pr_body is None:
        # No PR body available; skip gracefully (mirrors changelog check style).
        return []

    if not _DOCUMENTATION_SECTION_RE.search(pr_body):
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
        return [
            Violation(
                code="doc-coverage-outcome-missing",
                message=(
                    "Diff touches a documented surface but the PR body has no "
                    "## Documentation section. Add a documentation_outcome field "
                    "when calling gc_render_pr_body (ADR-054)."
                ),
                details=details,
            )
        ]

    return []
