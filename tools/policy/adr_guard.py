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
from collections.abc import Iterable
from pathlib import Path
from typing import Any
from .core import (
    ADR_POLICY_PATH,
    REPO_ROOT,
    Violation,
    normalize_path,
)
from .cli_safety import (
    validate_git_ref,
)

# `git diff`/`git ls-files` filter selecting added, copied, deleted, modified,
# renamed, type-changed, unmerged, unknown, and broken-pairing paths.
GIT_DIFF_FILTER = "--diff-filter=ACDMRTUXB"


def run_git(args: list[str], root: Path = REPO_ROOT) -> str:
    """Run ``git`` with ``args`` in ``root`` and return its captured stdout."""
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


def _changed_file_lines(
    *,
    base: str | None,
    staged: bool,
    env_var: str | None,
    root: Path,
) -> list[str]:
    """Return the raw, newline-split path list for the selected diff scope."""
    if env_var:
        return os.getenv(env_var, "").splitlines()
    if base:
        diff_base = merge_base_or(base, root=root)
        lines = run_git(
            ["diff", "--name-only", GIT_DIFF_FILTER, diff_base, "--"], root=root
        ).splitlines()
    elif staged:
        lines = run_git(
            ["diff", "--cached", "--name-only", GIT_DIFF_FILTER, "--"], root=root
        ).splitlines()
    else:
        tracked = run_git(
            ["diff", "--name-only", GIT_DIFF_FILTER, "HEAD", "--"], root=root
        ).splitlines()
        untracked = run_git(["ls-files", "--others", "--exclude-standard"], root=root).splitlines()
        lines = tracked + untracked
    return lines


def read_changed_files(
    *,
    files: Iterable[str] | None = None,
    base: str | None = None,
    staged: bool = False,
    env_var: str | None = None,
    root: Path = REPO_ROOT,
) -> list[str]:
    """Resolve the sorted, de-duplicated set of changed repo-relative paths.

    Selection is ordered: an explicit ``files`` list wins, otherwise the paths
    come from an ``env_var`` listing, a diff against ``base``, the staged index,
    or finally the working-tree diff plus untracked files.
    """
    if files:
        return sorted({normalize_path(path) for path in files if path})
    raw_lines = _changed_file_lines(base=base, staged=staged, env_var=env_var, root=root)
    return sorted({normalize_path(path) for path in raw_lines if path.strip()})


def matches_any(path: str, patterns: Iterable[str]) -> bool:
    """Return True when ``path`` matches any of the fnmatch ``patterns``."""
    return any(fnmatch.fnmatch(path, pattern) for pattern in patterns)


def filter_matches(paths: Iterable[str], patterns: Iterable[str]) -> list[str]:
    """Return the sorted, de-duplicated ``paths`` matching any of ``patterns``."""
    return sorted({path for path in paths if matches_any(path, patterns)})


def load_json(path: Path, *, reject_duplicate_keys: bool = False) -> dict[str, Any]:
    """Parse the JSON object at ``path``, optionally rejecting duplicate keys."""
    object_pairs_hook = None
    if reject_duplicate_keys:
        def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
            """Build a dict from ``pairs``, raising ValueError on any repeated key."""
            result: dict[str, Any] = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError(f"duplicate JSON key: {key}")
                result[key] = value
            return result

        object_pairs_hook = reject_duplicates
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=object_pairs_hook)


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
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))
    )


def _trigger_is_in_scope(
    path: str, rule: dict[str, Any], base: str | None, root: Path
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


def _rule_triggers(
    rule: dict[str, Any], changed_files: list[str], base: str | None, root: Path
) -> list[str]:
    """Return the changed paths that trigger ``rule`` and fall within its scope."""
    return [
        path
        for path in filter_matches(changed_files, rule.get("whenAny", []))
        if _trigger_is_in_scope(path, rule, base, root)
    ]


def _missing_requirements(
    rule: dict[str, Any], changed_files: list[str]
) -> tuple[list[str], list[str]]:
    """Return the (missing requireAll, missing requireAny) entries for ``rule``."""
    missing_all = [
        required
        for required in rule.get("requireAll", [])
        if required not in changed_files
    ]
    missing_any: list[str] = []
    require_any = rule.get("requireAny", [])
    if require_any and not any(required in changed_files for required in require_any):
        missing_any.append(f"one of: {', '.join(require_any)}")
    return missing_all, missing_any


def _evaluate_rule(
    rule: dict[str, Any], changed_files: list[str], base: str | None, root: Path
) -> Violation | None:
    """Return a Violation when ``rule`` fires but its required updates are absent."""
    triggers = _rule_triggers(rule, changed_files, base, root)
    if not triggers:
        return None

    missing_all, missing_any = _missing_requirements(rule, changed_files)
    if not (missing_all or missing_any):
        return None

    details = [f"triggered by: {', '.join(triggers)}"]
    if missing_all:
        details.append(f"missing required file updates: {', '.join(missing_all)}")
    details.extend(missing_any)
    return Violation(
        code=rule["id"],
        message=rule["message"],
        details=details,
    )


def run_adr_guard(
    changed_files: list[str], root: Path = REPO_ROOT, base: str | None = None
) -> list[Violation]:
    """Evaluate the ADR/documentation coupling policy against ``changed_files``."""
    policy = load_json(ADR_POLICY_PATH)
    violations: list[Violation] = []
    for policy_entry in policy["policies"]:
        for rule in policy_entry.get("rules", []):
            violation = _evaluate_rule(rule, changed_files, base, root)
            if violation is not None:
                violations.append(violation)
    return violations
