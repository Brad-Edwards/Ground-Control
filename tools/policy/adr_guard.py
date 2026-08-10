"""Policy checks: ADR guard.

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
from .core import (
    ADR_POLICY_PATH,
    REPO_ROOT,
    Violation,
    normalize_path,
)
from .cli_safety import (
    validate_git_ref,
)


def run_git(args: list[str], root: Path = REPO_ROOT) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def merge_base_or(base: str, ref: str = "HEAD", root: Path = REPO_ROOT) -> str:
    """Resolve the point from which ``ref`` diverged from ``base``.

    ``git diff <base> --`` is a two-dot comparison: it reports every path that
    differs between the tip of ``base`` and the working tree, so any commit
    ``base`` gained AFTER the branch forked is wrongly attributed to the branch.
    On a busy repo where ``dev`` advances while a PR is open, that mis-attributes
    ``dev``'s own later changes to the PR — spuriously tripping the diff-scoped
    gates (documentation coverage, changelog fragments) on files the branch
    never touched.

    Diffing from ``merge-base(base, ref)`` instead yields only what the branch
    itself changed, matching what GitHub shows as the PR diff. Comparing that
    merge base against the working tree (rather than ``base...ref``) preserves
    the caller's intent of catching still-uncommitted changes in the local /
    pre-push path.

    Falls back to ``base`` when no common ancestor exists (unrelated histories)
    or ``git merge-base`` fails, so the check degrades to the prior behavior
    instead of raising.
    """
    try:
        result = subprocess.run(
            ["git", "merge-base", validate_git_ref(base), validate_git_ref(ref)],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        return base
    return result.stdout.strip() or base


def read_changed_files(
    *,
    files: Iterable[str] | None = None,
    base: str | None = None,
    staged: bool = False,
    env_var: str | None = None,
    root: Path = REPO_ROOT,
) -> list[str]:
    if files:
        return sorted({normalize_path(path) for path in files if path})
    if env_var:
        raw = os.getenv(env_var, "")
        return sorted({normalize_path(path) for path in raw.splitlines() if path.strip()})
    if base:
        diff_base = merge_base_or(base, root=root)
        output = run_git(
            ["diff", "--name-only", "--diff-filter=ACDMRTUXB", diff_base, "--"], root=root
        )
        return sorted({normalize_path(path) for path in output.splitlines() if path.strip()})
    if staged:
        output = run_git(["diff", "--cached", "--name-only", "--diff-filter=ACDMRTUXB", "--"], root=root)
        return sorted({normalize_path(path) for path in output.splitlines() if path.strip()})

    tracked = run_git(["diff", "--name-only", "--diff-filter=ACDMRTUXB", "HEAD", "--"], root=root)
    untracked = run_git(["ls-files", "--others", "--exclude-standard"], root=root)
    combined = tracked.splitlines() + untracked.splitlines()
    return sorted({normalize_path(path) for path in combined if path.strip()})


def matches_any(path: str, patterns: Iterable[str]) -> bool:
    return any(fnmatch.fnmatch(path, pattern) for pattern in patterns)


def filter_matches(paths: Iterable[str], patterns: Iterable[str]) -> list[str]:
    return sorted({path for path in paths if matches_any(path, patterns)})


def load_json(path: Path, *, reject_duplicate_keys: bool = False) -> dict:
    object_pairs_hook = None
    if reject_duplicate_keys:
        def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
            result: dict[str, Any] = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError(f"duplicate JSON key: {key}")
                result[key] = value
            return result

        object_pairs_hook = reject_duplicates
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=object_pairs_hook)


def get_repo_relative_files(root: Path, glob_pattern: str) -> list[str]:
    return sorted(
        normalize_path(str(path.relative_to(root)))
        for path in root.glob(glob_pattern)
        if path.is_file()
    )


def changed_lines_for(path: str, base: str | None, root: Path = REPO_ROOT) -> str | None:
    """Added and removed lines for one path, or None when the diff cannot be read.

    Returning None rather than an empty string is what keeps a content-scoped trigger
    fail-closed: "nothing matched" and "I could not look" must not resolve the same
    way, or an unreadable diff would silently disable a gate.
    """
    try:
        diff_base = merge_base_or(base, root=root) if base else "HEAD"
        output = run_git(["diff", "--unified=0", diff_base, "--", path], root=root)
    except (subprocess.CalledProcessError, OSError):
        return None
    return "\n".join(
        line
        for line in output.splitlines()
        if (line.startswith("+") or line.startswith("-")) and not line.startswith(("+++", "---"))
    )


def _trigger_is_in_scope(
    path: str, rule: dict, base: str | None, root: Path
) -> bool:
    """Does this changed path actually touch the surface the rule guards?

    A rule may scope a trigger with `triggerContent`, mapping a path glob to a regex.
    Mega-modules like `lib.js` and `checks.py` hold many unrelated surfaces, so a
    bare path trigger on them fires on nearly every diff; a gate that fires constantly
    stops carrying information and trains contributors to satisfy it reflexively.

    Scoping narrows *when the trigger fires*, never what it then requires, and only
    for paths a rule explicitly scopes. An unreadable diff falls back to firing, so
    the gate can only ever become quieter when it can prove the surface is untouched.
    """
    conditions = rule.get("triggerContent") or {}
    pattern = next(
        (regex for glob, regex in conditions.items() if fnmatch.fnmatch(path, glob)),
        None,
    )
    if pattern is None:
        return True
    changed = changed_lines_for(path, base, root=root)
    if changed is None:
        return True
    return re.search(pattern, changed) is not None


def run_adr_guard(
    changed_files: list[str], root: Path = REPO_ROOT, base: str | None = None
) -> list[Violation]:
    policy = load_json(ADR_POLICY_PATH)
    violations: list[Violation] = []

    for policy_entry in policy["policies"]:
        for rule in policy_entry.get("rules", []):
            triggers = [
                path
                for path in filter_matches(changed_files, rule.get("whenAny", []))
                if _trigger_is_in_scope(path, rule, base, root)
            ]
            if not triggers:
                continue

            missing_all = [
                required
                for required in rule.get("requireAll", [])
                if required not in changed_files
            ]
            missing_any = []
            require_any = rule.get("requireAny", [])
            if require_any and not any(required in changed_files for required in require_any):
                missing_any.append(f"one of: {', '.join(require_any)}")

            if missing_all or missing_any:
                details = [f"triggered by: {', '.join(triggers)}"]
                if missing_all:
                    details.append(f"missing required file updates: {', '.join(missing_all)}")
                details.extend(missing_any)
                violations.append(
                    Violation(
                        code=rule["id"],
                        message=rule["message"],
                        details=details,
                    )
                )

    return violations
