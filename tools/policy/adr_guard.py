"""Policy checks: ADR guard and controller contracts.

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
    CONTROLLER_PATH_RE,
    REPO_ROOT,
    Violation,
    normalize_path,
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
            ["git", "merge-base", base, ref],
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


JAVA_MAIN_SOURCE_PREFIX = "backend/src/main/java/"


JAVA_TEST_SOURCE_PREFIX = "backend/src/test/java/"


WEBMVCTEST_ANNOTATION_RE = re.compile(r"@WebMvcTest\s*\(([^)]*)\)", re.DOTALL)


# Dotted Java identifier (`a.b.C`). Matched WITHOUT a trailing `.class` literal:
# a `(?:\.[\w$]+)*\.class` form overlaps the quantified segment with the final
# `.class` and backtracks super-linearly (Sonar S8786). The `.class` suffix is
# stripped in code instead, which keeps the match linear.
JAVA_DOTTED_NAME_RE = re.compile(r"[\w$]+(?:\.[\w$]+)*")


_CLASS_LITERAL_SUFFIX = ".class"


# Non-static single-type imports only: `import static ...;` has a space after
# `import` that `[\w.]+` cannot span, so it never matches here.
JAVA_IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)\s*;", re.MULTILINE)


def controller_fully_qualified_name(controller_path: str) -> str | None:
    """Fully-qualified class name for a controller from its repo-relative path."""
    normalized = normalize_path(controller_path)
    if not normalized.startswith(JAVA_MAIN_SOURCE_PREFIX) or not normalized.endswith(".java"):
        return None
    relative = normalized[len(JAVA_MAIN_SOURCE_PREFIX) : -len(".java")]
    return relative.replace("/", ".")


def test_covers_controller(content: str, controller_fqcn: str) -> bool:
    """True when a test's @WebMvcTest annotation resolves to ``controller_fqcn``.

    Resolution mirrors Java name binding: a fully-qualified literal matches
    directly; a simple name binds through the file's single-type import for that
    name; absent such an import the simple name binds in the file's own package.
    The import check is what disambiguates same-simple-name controllers in
    different packages (issue #1167) — matching on the bare filename stem, or on
    the annotation's simple name alone, cannot.
    """
    referenced: set[str] = set()
    for args in WEBMVCTEST_ANNOTATION_RE.findall(content):
        for token in JAVA_DOTTED_NAME_RE.findall(args):
            if token.endswith(_CLASS_LITERAL_SUFFIX):
                referenced.add(token[: -len(_CLASS_LITERAL_SUFFIX)])
    if not referenced:
        return False
    if controller_fqcn in referenced:
        return True
    simple_name = controller_fqcn.rsplit(".", 1)[-1]
    if simple_name not in referenced:
        return False
    imports = {imported.rsplit(".", 1)[-1]: imported for imported in JAVA_IMPORT_RE.findall(content)}
    bound = imports.get(simple_name)
    if bound is not None:
        return bound == controller_fqcn
    # No single-type import of the simple name: it binds in the test's own
    # package (or via a wildcard import that cannot be resolved statically).
    # The conflicting-import collision this check exists to prevent has already
    # been excluded above, so accept the simple-name match.
    return True


def run_controller_contracts(changed_files: list[str], root: Path = REPO_ROOT) -> list[Violation]:
    controllers = [path for path in changed_files if CONTROLLER_PATH_RE.match(path)]
    if not controllers:
        return []

    violations: list[Violation] = []
    missing: list[str] = []
    if "docs/API.md" not in changed_files:
        missing.append("docs/API.md")
    if "mcp/ground-control/lib.js" not in changed_files:
        missing.append("mcp/ground-control/lib.js")
    # MCP server adapter companion: most tools register inline in index.js, but a few were
    # factored into their own modules — gc_risk_governance, gc_risk_scenario, and gc_workflow_run
    # (their Zod shapes, descriptions, and handlers live in gc-risk-governance.js,
    # gc-risk-scenario.js, and gc-workflow-run.js; index.js only registers the imports). Any of
    # those files satisfies the MCP-adapter requirement for its controller; index.js stays
    # mandatory for any tool still registered inline.
    adapter_files = (
        "mcp/ground-control/index.js",
        "mcp/ground-control/gc-risk-governance.js",
        "mcp/ground-control/gc-risk-scenario.js",
        "mcp/ground-control/gc-workflow-run.js",
    )
    if not any(adapter in changed_files for adapter in adapter_files):
        missing.append("one of: " + ", ".join(adapter_files))
    if missing:
        violations.append(
            Violation(
                code="controller-parity",
                message="Controller changes require API docs and MCP parity updates.",
                details=[
                    f"controllers changed: {', '.join(controllers)}",
                    f"missing companion updates: {', '.join(missing)}",
                ],
            )
        )

    # Resolve each controller's @WebMvcTest companion by reverse-lookup on the
    # controller's fully-qualified class, not its filename stem. The stem
    # collides whenever two packages declare a same-named controller (issue
    # #1167: api/audit/AuditController vs api/audits/AuditController).
    repo_test_files = get_repo_relative_files(root, "backend/src/test/java/**/*.java")
    changed_test_files = [
        path
        for path in changed_files
        if path.startswith(JAVA_TEST_SOURCE_PREFIX) and path.endswith(".java")
    ]

    def covers(rel_path: str, fqcn: str) -> bool:
        try:
            content = (root / rel_path).read_text(encoding="utf-8")
        except OSError:
            return False
        return test_covers_controller(content, fqcn)

    for controller in controllers:
        # A controller deleted in this diff has no request mapping left to slice-test,
        # and its @WebMvcTest companion is deleted along with it. Demanding a companion
        # for a file that no longer exists would make route removal unshippable.
        if not (root / controller).exists():
            continue
        fqcn = controller_fully_qualified_name(controller)
        if fqcn is None:
            continue
        simple_name = fqcn.rsplit(".", 1)[-1]

        if any(covers(path, fqcn) for path in changed_test_files):
            # The controller's @WebMvcTest companion was updated in this diff.
            continue

        existing = [path for path in repo_test_files if covers(path, fqcn)]
        if existing:
            violations.append(
                Violation(
                    code="controller-webmvctest-update",
                    message="Controller changes require a matching @WebMvcTest update.",
                    details=[
                        f"changed {controller} but did not update its @WebMvcTest "
                        f"companion; expected one of: {', '.join(existing)}"
                    ],
                )
            )
            continue

        # No @WebMvcTest slice resolves to this controller. Distinguish "a
        # same-named test exists but is not a slice" (annotation) from "no test
        # exists at all" (missing) so the message points at the real gap.
        stem_tests = get_repo_relative_files(
            root, f"backend/src/test/java/**/{simple_name}Test.java"
        )
        non_slice_tests = [
            path
            for path in stem_tests
            if "@WebMvcTest(" not in (root / path).read_text(encoding="utf-8")
        ]
        if non_slice_tests:
            violations.append(
                Violation(
                    code="controller-webmvctest-annotation",
                    message="Controller test exists but is not a @WebMvcTest.",
                    details=[
                        f"{', '.join(non_slice_tests)} must use @WebMvcTest for {controller}"
                    ],
                )
            )
            continue

        violations.append(
            Violation(
                code="controller-webmvctest-missing",
                message="Controller is missing a matching @WebMvcTest class.",
                details=[f"no @WebMvcTest({simple_name}.class) slice found for {controller}"],
            )
        )

    return violations
