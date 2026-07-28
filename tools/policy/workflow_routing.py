"""Policy checks: workflow routing and traceability reconciliation gate.

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
from .decision_records import (
    IMPLEMENT_STEP_17_PATH,
    IMPLEMENT_STEP_20_PATH,
)
from .core import (
    GROUND_CONTROL_YAML_PATH,
    REPO_ROOT,
    Violation,
)
from .authz_matrix import (
    IMPLEMENT_SKILL_PATH,
)


def run_traceability_reconciliation_gate_contract(
    *,
    root: Path = REPO_ROOT,
) -> list[Violation]:
    """Assert the traceability + closeout gate prose surfaces are wired.

    The gate has two MCP-tool surfaces and three prose anchors:

      step-17-completion.md   must mention `gc_assert_completion` and
                              `traceability_reconciled` and `plain_english_outcome`
      step-20-close-issue-on-merge.md must exist AND mention
                              `gc_close_issue_after_merge`
      SKILL.md                must mention `Phase E` and
                              `gc_close_issue_after_merge`

    Emits one violation per missing anchor with a stable code so CI surfaces
    the specific gap. A repo whose policy-tests file isn't yet up to date
    (e.g., the test fixture path needs a workflow run) flags here rather
    than going silent.
    """
    violations: list[Violation] = []

    requirements = (
        (
            IMPLEMENT_STEP_17_PATH,
            ("gc_assert_completion", "traceability_reconciled", "plain_english_outcome"),
            "traceability-gate-step17-missing",
            "Step 17 must use gc_assert_completion with traceability_reconciled and plain_english_outcome (issue #1103).",
        ),
        (
            IMPLEMENT_STEP_20_PATH,
            ("gc_close_issue_after_merge",),
            "traceability-gate-step20-missing",
            "Step 20 (Phase E post-merge close) must exist and mention gc_close_issue_after_merge (issue #1058).",
        ),
        (
            IMPLEMENT_SKILL_PATH,
            ("Phase E", "gc_close_issue_after_merge"),
            "traceability-gate-skill-missing",
            "SKILL.md must document Phase E and the gc_close_issue_after_merge close path (issue #1058).",
        ),
    )

    for rel_path, tokens, code, message in requirements:
        path = root / rel_path
        if not path.exists():
            violations.append(
                Violation(
                    code=code,
                    message=f"{rel_path} is missing — required by the issue #1058 traceability gate contract.",
                    details=[f"expected at {rel_path}", *(f"must mention: {t}" for t in tokens)],
                )
            )
            continue
        text = path.read_text(encoding="utf-8")
        missing_tokens = [t for t in tokens if t not in text]
        if missing_tokens:
            violations.append(
                Violation(
                    code=code,
                    message=message,
                    details=[f"in {rel_path}", *(f"missing: '{t}'" for t in missing_tokens)],
                )
            )

    return violations


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run repo policy checks.")
    parser.add_argument("--base", help="Git base ref to diff against.")
    parser.add_argument(
        "--files",
        nargs="*",
        default=None,
        help="Explicit repo-relative files to evaluate.",
    )
    parser.add_argument(
        "paths",
        nargs="*",
        help="Positional repo-relative files to evaluate. Used by pre-commit.",
    )
    parser.add_argument("--files-env", help="Read newline-delimited files from an env var.")
    parser.add_argument(
        "--json",
        dest="json_out",
        help=(
            "Write the violations and this run's duration to a JSON file, for the ADR-090 "
            "measurement projection. Emitted at the gate's own boundary so the measurement layer "
            "reads a structured artifact instead of re-running the gate or parsing its console "
            "output. Never changes the exit code."
        ),
    )
    parser.add_argument("--staged", action="store_true", help="Read staged files from git.")
    parser.add_argument(
        "--skip-pr-body",
        action="store_true",
        help="Do not evaluate the GitHub pull request body.",
    )
    parser.add_argument(
        "--event-path",
        help="Path to a GitHub event payload. Defaults to GITHUB_EVENT_PATH when present.",
    )
    parser.add_argument(
        "--pr-body-file",
        help=(
            "Path to a plain-text PR body draft. Use this in pre-push hooks to "
            "validate the PR body before push. Mutually exclusive with --event-path / "
            "--pr-number; --pr-body-file wins when supplied."
        ),
    )
    parser.add_argument(
        "--pr-number",
        type=int,
        help=(
            "GitHub PR number. When set (and neither --pr-body-file nor "
            "--event-path is supplied), the body is fetched via "
            "`gh pr view <n> --json body`."
        ),
    )
    parser.add_argument(
        "--pr-comments-json",
        help=(
            "Path to sanitized PR issue comments as a JSON array or JSONL "
            "objects with body and author fields. CI uses this so PR-head "
            "policy code does not receive a GitHub token."
        ),
    )
    return parser.parse_args(argv)


def write_violations_json(path: str, violations: list[Violation], duration_ms: int) -> None:
    """Write this run's violations as structured data (issue #1355, ADR-090).

    Fail-open: measurement must never change whether the policy gate passes, so a write failure
    is swallowed. The gate's verdict is its exit code, not this file.
    """
    try:
        Path(path).write_text(
            json.dumps(
                {
                    "station_id": "policy",
                    "duration_ms": duration_ms,
                    "violations": [
                        {"code": v.code, "details": list(v.details)} for v in violations
                    ],
                },
                indent=1,
            ),
            encoding="utf-8",
        )
    except OSError:
        pass


def render_and_exit(violations: list[Violation]) -> int:
    if not violations:
        print("Policy checks passed.")
        return 0

    print("Policy checks failed:")
    for violation in violations:
        print(violation.render())
    return 1


def run_workflow_routing_contract(root: Path = REPO_ROOT) -> list[Violation]:
    """Keep /implement routing advisory and free of executor controls."""
    violations: list[Violation] = []
    config_path = root / GROUND_CONTROL_YAML_PATH
    if not config_path.exists():
        violations.append(
            Violation(
                code="workflow-routing-config-missing",
                message=".ground-control.yaml is missing — workflow routing cannot be verified.",
                details=[f"expected at {GROUND_CONTROL_YAML_PATH.as_posix()}"],
            )
        )
        return violations

    config = config_path.read_text(encoding="utf-8")
    retired = [
        token
        for token in ("default_fallback:", "agent:", "fallback:")
        if re.search(rf"(?m)^\s+{re.escape(token)}", config)
    ]
    if retired:
        violations.append(
            Violation(
                code="workflow-routing-execution-control-retired",
                message="Routing metadata must not select an executor or fallback path.",
                details=[f"retired field present: {token}" for token in retired],
            )
        )
    if not re.search(r"(?m)^\s{4}base_sync:\s*$", config):
        violations.append(
            Violation(
                code="workflow-routing-base-sync-stage-missing",
                message="The advisory routing table must declare the Step 8.5 base_sync stage.",
                details=["expected routing.stages.base_sync in .ground-control.yaml"],
            )
        )
    return violations
