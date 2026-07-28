"""Policy checks: ontology crosswalk.

Extracted from tools/policy/checks.py (issue #1355), which had reached 5,679 lines against
the repo's 500-LOC limit. checks.py remains the entry point and re-exports this module, so
every existing import path and the CLI keep working.
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
    REPO_ROOT,
    REQUIREMENT_FREE_MARKER_RE,
    Violation,
    extract_requirement_uid_tokens,
    extract_requirement_uids_section,
    run_no_deferral_disposition_check,
)


def run_contract_invariant_enforcement_check(root: Path = REPO_ROOT) -> list[Violation]:
    violations: list[Violation] = []
    schemas_dir = root / "contracts" / "schemas"
    if not schemas_dir.exists():
        return [
            Violation(
                code="contract-schema-dir-missing",
                message="contracts/schemas/ is missing; GC-O014 schema invariant coverage cannot be checked.",
            )
        ]

    for schema_path in sorted(schemas_dir.rglob("*.schema.json")):
        rel = schema_path.relative_to(root).as_posix()
        try:
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            violations.append(
                Violation(
                    code="contract-schema-json-invalid",
                    message=f"{rel} is not valid JSON.",
                    details=[str(exc)],
                )
            )
            continue

        invariants = schema.get("x-ground-control-invariants")
        if invariants is None:
            violations.append(
                Violation(
                    code="contract-invariant-inventory-missing",
                    message=f"{rel} must declare x-ground-control-invariants.",
                    details=["Use [{\"id\":\"none\", \"rationale\":\"...\"}] only when the schema has no declared invariant."],
                )
            )
            continue
        if not isinstance(invariants, list) or len(invariants) == 0:
            violations.append(
                Violation(
                    code="contract-invariant-inventory-invalid",
                    message=f"{rel} has an empty or non-list x-ground-control-invariants value.",
                )
            )
            continue

        for entry in invariants:
            if not isinstance(entry, dict) or not entry.get("id"):
                violations.append(
                    Violation(
                        code="contract-invariant-entry-invalid",
                        message=f"{rel} has an invariant entry without an id.",
                    )
                )
                continue
            if entry["id"] == "none":
                if not entry.get("rationale"):
                    violations.append(
                        Violation(
                            code="contract-invariant-none-rationale-missing",
                            message=f"{rel} declares no invariants but omits a rationale.",
                        )
                    )
                continue
            enforced_by = entry.get("enforcedBy")
            if not isinstance(enforced_by, list) or len(enforced_by) == 0:
                violations.append(
                    Violation(
                        code="contract-invariant-enforcement-missing",
                        message=f"{rel} invariant {entry['id']} must name at least one enforcing test or spec file.",
                    )
                )
                continue
            for target in enforced_by:
                if not isinstance(target, str):
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-invalid",
                            message=f"{rel} invariant {entry['id']} has an invalid enforcement path.",
                            details=[str(target)],
                        )
                    )
                    continue

                target_path_text, separator, target_anchor = target.partition("::")
                target_path = Path(target_path_text)
                if not separator or not target_anchor:
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-anchor-missing",
                            message=f"{rel} invariant {entry['id']} must name a specific test/spec anchor.",
                            details=[f"use '<repo-path>::<test-or-rule-id>', got {target}"],
                        )
                    )
                    continue
                if target_path_text.startswith("/") or ".." in target_path.parts:
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-invalid",
                            message=f"{rel} invariant {entry['id']} has an invalid enforcement path.",
                            details=[target],
                        )
                    )
                    continue

                resolved = root / target_path
                if not resolved.exists():
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-missing-file",
                            message=f"{rel} invariant {entry['id']} references a missing enforcement file.",
                            details=[target_path_text],
                        )
                    )
                elif target_anchor not in resolved.read_text(encoding="utf-8"):
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-anchor-missing-file",
                            message=f"{rel} invariant {entry['id']} references an enforcement anchor that is not present.",
                            details=[target],
                        )
                    )

    return violations


def _parse_authz_contract_rows(text: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    current: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line.startswith("- id:"):
            if current:
                rows.append(current)
            current = {"id": line.split(":", 1)[1].strip().strip('"')}
        elif current and ":" in line:
            key, value = line.split(":", 1)
            current[key.strip()] = value.strip().strip('"')
    if current:
        rows.append(current)
    return rows


def _java_matrix_paths_by_access(java_text: str) -> dict[str, set[str]]:
    constants = {
        name: value
        for name, value in re.findall(r'private\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"', java_text)
    }
    access_methods = {
        "hasRole(ROLE_ADMIN)": "ROLE_ADMIN",
        "access(identityAuthorizationManager)": "PERMISSION_IDENTITY_ADMIN",
    }
    paths_by_access = {access: set() for access in access_methods.values()}
    matcher = r"\.requestMatchers\((.*?)\)\s*\.(hasRole\(ROLE_ADMIN\)|access\(identityAuthorizationManager\))"
    for match in re.finditer(matcher, java_text, re.DOTALL):
        block = match.group(1)
        access = access_methods[match.group(2)]
        paths = paths_by_access[access]
        paths.update(value for value in re.findall(r'"(/api/v1/[^"]+)"', block))
        for token in re.findall(r"\b[A-Z][A-Z0-9_]+\b", block):
            if token in constants and constants[token].startswith("/api/v1/"):
                paths.add(constants[token])
    return paths_by_access


def _java_admin_matrix_paths(java_text: str) -> set[str]:
    return _java_matrix_paths_by_access(java_text)["ROLE_ADMIN"]


def run_authz_matrix_sync_check(root: Path = REPO_ROOT) -> list[Violation]:
    matrix_path = root / "contracts" / "authz" / "path-matrix.yaml"
    java_path = root / "backend" / "src" / "main" / "java" / "com" / "keplerops" / "groundcontrol" / "shared" / "security" / "ApiPathMatrix.java"
    if not matrix_path.exists() or not java_path.exists():
        return [
            Violation(
                code="authz-matrix-source-missing",
                message="Authz matrix sync check needs contracts/authz/path-matrix.yaml and ApiPathMatrix.java.",
            )
        ]

    rows = _parse_authz_contract_rows(matrix_path.read_text(encoding="utf-8"))
    contract_paths_by_access = {
        access: {row.get("path", "") for row in rows if row.get("access") == access}
        for access in ("ROLE_ADMIN", "PERMISSION_IDENTITY_ADMIN")
    }
    for paths in contract_paths_by_access.values():
        paths.discard("")
    java_paths_by_access = _java_matrix_paths_by_access(java_path.read_text(encoding="utf-8"))

    violations: list[Violation] = []
    details: list[str] = []
    for access in ("ROLE_ADMIN", "PERMISSION_IDENTITY_ADMIN"):
        contract_paths = contract_paths_by_access[access]
        java_paths = java_paths_by_access[access]
        missing_from_contract = sorted(java_paths - contract_paths)
        missing_from_java = sorted(contract_paths - java_paths)
        if missing_from_contract:
            details.append(
                f"{access} paths in ApiPathMatrix.java but not contracts/authz/path-matrix.yaml: "
                f"{missing_from_contract}"
            )
        if missing_from_java:
            details.append(
                f"{access} paths in contracts/authz/path-matrix.yaml but not ApiPathMatrix.java: "
                f"{missing_from_java}"
            )
    if details:
        violations.append(
            Violation(
                code="authz-matrix-drift",
                message="contracts/authz/path-matrix.yaml drifted from ApiPathMatrix.java.",
                details=details,
            )
        )
    return violations


def run_pr_body_check(event_path: Path) -> list[Violation]:
    """Backwards-compatible wrapper that loads the PR body from a GitHub event payload."""
    event = json.loads(event_path.read_text(encoding="utf-8"))
    pull_request = event.get("pull_request") or {}
    body = pull_request.get("body") or ""
    return check_pr_body(body)


def check_pr_body(body: str) -> list[Violation]:
    """Validate a PR body against the Ground Control template requirements.

    Pure function over the body string so it can be driven from GitHub event
    payloads (CI), a local draft file (pre-push hook), or `gh pr view --json
    body`. The CI path is `run_pr_body_check`; local tooling should call this
    directly.
    """
    violations: list[Violation] = []

    required_headers = [
        "## Requirement UIDs",
        "## ADR Impact",
        "## Ground Control Checks",
        "## Traceability",
    ]
    missing_headers = [header for header in required_headers if header not in body]
    if missing_headers:
        violations.append(
            Violation(
                code="pr-template-sections",
                message="PR body is missing required Ground Control sections.",
                details=[f"missing headers: {', '.join(missing_headers)}"],
            )
        )
        return violations

    if not extract_requirement_uid_tokens(body) and not REQUIREMENT_FREE_MARKER_RE.search(
        extract_requirement_uids_section(body)
    ):
        violations.append(
            Violation(
                code="pr-requirement-uid",
                message="PR body must name at least one requirement UID.",
                details=["expected a UID like GC-O007 in the Requirement UIDs section"],
            )
        )

    if "No ADR required" not in body and "ADR-" not in body:
        violations.append(
            Violation(
                code="pr-adr-impact",
                message="PR body must call out ADR impact or say 'No ADR required'.",
                details=[],
            )
        )

    required_checks = [
        # The policy gate is named semantically, not by command (issue #1429).
        # `workflow.policy_command` in `.ground-control.yaml` decides what
        # actually runs, so the PR body attests that the configured gate
        # passed rather than asserting a Make target. Mirrors
        # `PR_BODY_POLICY_CHECK_LINE` in `mcp/ground-control/lib.js`.
        "- [x] Configured repository policy command passes",
        "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change",
        "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale",
    ]
    missing_checks = [entry for entry in required_checks if entry not in body]
    if missing_checks:
        violations.append(
            Violation(
                code="pr-ground-control-checks",
                message="PR body must record the Ground Control verification checklist.",
                details=missing_checks,
            )
        )

    traceability_markers = ["- IMPLEMENTS:", "- TESTS:"]
    missing_traceability = [marker for marker in traceability_markers if marker not in body]
    if missing_traceability:
        violations.append(
            Violation(
                code="pr-traceability-summary",
                message="PR body must summarize IMPLEMENTS and TESTS traceability.",
                details=missing_traceability,
            )
        )

    # The no-deferral check is composed into the PR-body validator so EVERY
    # PR-body validation route — bin/policy main(), run_pr_body_check (the
    # GitHub-event-payload path / bin/check-pr-body), and a direct
    # check_pr_body(body) call — enforces ADR-029's contract, not just main().
    violations.extend(run_no_deferral_disposition_check(pr_body=body))

    return violations


IMPLEMENT_SKILL_PATH = "skills/implement/SKILL.md"


# After issue #934 the monolithic SKILL.md is a thin orchestrator and the
# per-step prose lives at `skills/implement/steps/step-NN-<id>.md`. The
# test-quality contract check reads from the step file when the
# orchestrator no longer carries the Step 6.6 heading inline.
IMPLEMENT_STEP_TEST_QUALITY_PATH = (
    "skills/implement/steps/step-06.6-test-quality-review.md"
)


# Matches a Markdown step heading at any level (`#` through `####`) whose
# text starts with the given step label (e.g. `Step 13`). Captures the
# heading line so the splitter knows where the section begins. The H1
# branch covers per-step files split out of SKILL.md by issue #934 — each
# step file uses an H1 step heading rather than the H3 used inline in
# the monolithic SKILL.
_STEP_HEADING_RE = re.compile(r"^#{1,4}\s+(Step\s+\d+(?:\.\d+)?)\b.*$", re.MULTILINE)


def extract_step_section(body: str, step_label: str) -> str | None:
    """Return the body of a Markdown `### Step N: ...` section, or None.

    The section runs from its heading up to the next step heading of any
    level (or end of file). Returns None when the step is not present.
    """
    headings = list(_STEP_HEADING_RE.finditer(body))
    target_idx = None
    for i, match in enumerate(headings):
        if match.group(1).strip() == step_label:
            target_idx = i
            break
    if target_idx is None:
        return None
    start = headings[target_idx].end()
    end = headings[target_idx + 1].start() if target_idx + 1 < len(headings) else len(body)
    return body[start:end]
