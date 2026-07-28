"""Policy checks: migration policy.

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
from .adr_guard import (
    run_git,
)
from .core import (
    COMPOSE_ENV_INHERIT_FORM_RE,
    COMPOSE_ENV_KEY_RE,
    MIGRATION_PATH_RE,
    REPO_ROOT,
    Violation,
    normalize_path,
)


def git_diff_for_paths(paths: Iterable[str], root: Path = REPO_ROOT) -> str:
    path_list = [normalize_path(path) for path in paths]
    if not path_list:
        return ""
    return run_git(["diff", "--unified=0", "HEAD", "--", *path_list], root=root)


def _resolve_baseline_ref(base: str | None, root: Path = REPO_ROOT) -> str | None:
    """Resolve the released-baseline ref to diff migration content against.

    Prefers the explicit ``--base`` ref, then ``origin/main`` (the released
    line), then ``main``. Returns ``None`` when none resolve (e.g. a shallow
    clone without the baseline fetched) so the immutability check skips
    gracefully rather than failing the run.
    """
    for ref in (base, "origin/main", "main"):
        if not ref:
            continue
        try:
            run_git(["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], root=root)
            return ref
        except subprocess.CalledProcessError:
            continue
    return None


def _migration_content_at_ref(ref: str, path: str, root: Path = REPO_ROOT) -> str | None:
    """Return ``path`` content at ``ref``, or ``None`` if it does not exist there."""
    try:
        return run_git(["show", f"{ref}:{path}"], root=root)
    except subprocess.CalledProcessError:
        return None


def run_migration_policy(
    changed_files: list[str], root: Path = REPO_ROOT, base: str | None = None
) -> list[Violation]:
    migrations = [path for path in changed_files if MIGRATION_PATH_RE.match(path)]
    violations: list[Violation] = []

    if migrations:
        # Flyway immutability: a migration already present on the released
        # baseline (origin/main) must never have its content changed — Flyway
        # validates checksums on every startup, so editing an applied migration
        # crashes every database that already ran it (the V043/V045 incident,
        # which a fresh-DB smoke test cannot catch). New migrations are exempt
        # (absent from the baseline); the only correct way to change applied
        # data/schema is a new forward migration.
        baseline = _resolve_baseline_ref(base, root)
        if baseline:
            for path in migrations:
                released = _migration_content_at_ref(baseline, path, root)
                if released is None:
                    continue  # new migration — not on the baseline, allowed.
                target = root / path
                current = target.read_text(encoding="utf-8") if target.exists() else None
                if current != released:
                    change = "removed" if current is None else "modified"
                    violations.append(
                        Violation(
                            code="migration-immutability",
                            message=(
                                "An applied Flyway migration was changed. Migrations on the "
                                "released baseline are immutable — add a new forward migration "
                                "instead of editing one."
                            ),
                            details=[
                                f"{change} migration: {path}",
                                f"baseline ref: {baseline}",
                                "Flyway validates checksums on startup; changing an applied "
                                "migration breaks every database that already ran it.",
                            ],
                        )
                    )

        required = [
            "backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java",
            "backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementsE2EIntegrationTest.java",
        ]
        missing = [path for path in required if path not in changed_files]
        if missing:
            violations.append(
                Violation(
                    code="migration-smoke-sync",
                    message="Migration changes require the hardcoded integration version lists to be updated.",
                    details=[
                        f"migrations changed: {', '.join(migrations)}",
                        f"missing companion updates: {', '.join(missing)}",
                    ],
                )
            )

    java_files = [path for path in changed_files if path.endswith(".java")]
    diff = git_diff_for_paths(java_files, root=root)
    audited_files: set[str] = set()
    current_file = ""
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current_file = normalize_path(line.removeprefix("+++ b/"))
        elif line.startswith("+") and not line.startswith("+++") and "@Audited" in line and current_file:
            audited_files.add(current_file)

    if audited_files and not migrations:
        violations.append(
            Violation(
                code="audited-entity-migration",
                message="Adding @Audited requires matching Flyway migration updates.",
                details=[
                    f"@Audited added in: {', '.join(sorted(audited_files))}",
                    "expected at least one db/migration/V*.sql change in the same diff",
                ],
            )
        )

    return violations


def _extract_compose_backend_env_entries(text: str) -> dict[str, str]:
    """Extract environment entries declared on the `backend` service.

    Returns a mapping of key → form, where form is one of:
      ``"inherit"`` — list shorthand ``- KEY`` (no value, host-inheritance only).
      ``"list-value"`` — list form with explicit value ``- KEY=...``.
      ``"map"``    — map form ``KEY: ...``.

    Compose allows both list-form (``- KEY=VALUE`` / ``- KEY``) and map-form
    (``KEY: VALUE``) under ``environment:``; honor either. A handwritten
    indentation walker is intentional here — adding a PyYAML dependency for
    one check would make ``make policy`` fail with ``ModuleNotFoundError`` on
    a clean Python installation, since the rest of ``tools/policy/`` is
    stdlib-only.
    """
    found: dict[str, str] = {}
    in_backend = False
    backend_indent = -1
    in_environment = False
    env_indent = -1
    for raw_line in text.splitlines():
        stripped = raw_line.lstrip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw_line) - len(stripped)

        # Track sibling-out-of-block exits before recognizing new starts.
        if in_environment and indent <= env_indent:
            in_environment = False
        if in_backend and indent <= backend_indent and not stripped.startswith("backend:"):
            in_backend = False

        if stripped.startswith("backend:"):
            in_backend = True
            backend_indent = indent
            in_environment = False
            continue
        if in_backend and stripped.startswith("environment:") and not in_environment:
            in_environment = True
            env_indent = indent
            continue
        if in_environment:
            inherit_match = COMPOSE_ENV_INHERIT_FORM_RE.match(raw_line)
            if inherit_match:
                found.setdefault(inherit_match.group(1), "inherit")
                continue
            match = COMPOSE_ENV_KEY_RE.match(raw_line)
            if match:
                key = match.group(1) or match.group(2)
                form = "list-value" if match.group(1) is not None else "map"
                found.setdefault(key, form)
    return found


RELEASE_PLEASE_CONFIG = "release-please-config.json"


RELEASE_PLEASE_MANIFEST = ".release-please-manifest.json"


# The single Ground Control root component's key in both the config and the manifest.
RELEASE_PLEASE_ROOT_PACKAGE = "."


# release-please "generic" updater annotation (string-form extra-files), e.g.
#   version = "1.0.1" // x-release-please-version   (backend/build.gradle.kts)
_GENERIC_VERSION_ANNOTATION = "x-release-please-version"


_QUOTED_VERSION_RE = re.compile(r"""["'](\d+\.\d+\.\d+[0-9A-Za-z.\-+]*)["']""")


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
        c = s[i]
        if c == ".":
            i += 1
            j = i
            while j < n and s[j] not in ".[":
                j += 1
            if j == i:
                return None
            keys.append(s[i:j])
            i = j
        elif c == "[":
            close = s.find("]", i)
            if close == -1:
                return None
            inner = s[i + 1 : close].strip()
            if len(inner) >= 2 and inner[0] in "\"'" and inner[-1] == inner[0]:
                keys.append(inner[1:-1])
            else:
                keys.append(inner)
            i = close + 1
        else:
            j = i
            while j < n and s[j] not in ".[":
                j += 1
            keys.append(s[i:j])
            i = j
    return keys or None


def _extract_json_version(data: object, jsonpath: str) -> str | None:
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
    for line in text.splitlines():
        if _GENERIC_VERSION_ANNOTATION in line:
            match = _QUOTED_VERSION_RE.search(line)
            if match:
                return match.group(1)
    return None
