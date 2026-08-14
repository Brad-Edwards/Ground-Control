"""Policy checks: implement execution contract.

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
    REPO_ROOT,
    Violation,
)
from .cli_safety import (
    safe_cli_path,
)
MCP_LIB_PATH = "mcp/ground-control/lib.js"


MCP_LIB_DIR = "mcp/ground-control/lib"


def read_mcp_library(root: Path = REPO_ROOT) -> str | None:
    """The MCP shared library's full source, barrel plus every extracted module.

    lib.js was a single 20,634-line file until issue #1355 split it under the repo's
    500-LOC limit; it is now a barrel of star re-exports. A check that reads only that
    file sees no implementation at all and silently passes, so every content check reads
    the whole surface instead of one path.
    """
    barrel = root / MCP_LIB_PATH
    if not barrel.exists():
        return None
    parts = [barrel.read_text(encoding="utf-8")]
    lib_dir = root / MCP_LIB_DIR
    if lib_dir.is_dir():
        parts.extend(
            path.read_text(encoding="utf-8") for path in sorted(lib_dir.glob("*.js"))
        )
    return "\n".join(parts)


def read_mcp_registrations(root: Path = REPO_ROOT) -> str:
    """The MCP tool-registration surface: index.js plus every module under tools/.

    index.js was the single registration file until it was decomposed; the registrations now
    live in mcp/ground-control/tools/*.js. A check that reads only index.js finds none of them,
    which is the same shape as the lib.js barrel above: the subject moved, the scan matched
    nothing, and the check reported on a surface it could no longer see.
    """
    parts = []
    index = root / "mcp/ground-control/index.js"
    if index.exists():
        parts.append(index.read_text(encoding="utf-8"))
    tools_dir = root / "mcp/ground-control/tools"
    if tools_dir.is_dir():
        parts.extend(path.read_text(encoding="utf-8") for path in sorted(tools_dir.rglob("*.js")))
    return "\n".join(parts)


def run_implement_execution_contract(root: Path = REPO_ROOT) -> list[Violation]:
    """Enforce /implement's pre-routing principles and persistence boundary."""
    violations: list[Violation] = []
    paths = {
        "skill": root / "skills/implement/SKILL.md",
        "principles": root / "skills/implement/_development-principles.md",
        "step1": root / "skills/implement/steps/step-01-issue-branch-resolution.md",
        "step4": root / "skills/implement/steps/step-04-planning.md",
        "step4_4": root / "skills/implement/steps/step-04.4-tdd.md",
        "step8_5": root / "skills/implement/steps/step-08.5-sync-base.md",
        "step9": root / "skills/implement/steps/step-09-pr-body.md",
        "completion": root / "skills/implement/steps/step-17-completion.md",
        "cursor": root / ".cursor/skills/implement/SKILL.md",
        "mcp_lib": root / "mcp/ground-control/lib.js",
        "mcp_index": root / "mcp/ground-control/index.js",
    }
    missing = [str(path.relative_to(root)) for path in paths.values() if not path.is_file()]
    if missing:
        return [
            Violation(
                code="implement-execution-contract-missing",
                message="/implement execution-contract surfaces are missing.",
                details=missing,
            )
        ]

    skill = paths["skill"].read_text(encoding="utf-8")
    principles = paths["principles"].read_text(encoding="utf-8")
    step1 = paths["step1"].read_text(encoding="utf-8")
    step4 = paths["step4"].read_text(encoding="utf-8")
    step4_4 = paths["step4_4"].read_text(encoding="utf-8")
    step8_5 = paths["step8_5"].read_text(encoding="utf-8")
    step9 = paths["step9"].read_text(encoding="utf-8")
    completion = paths["completion"].read_text(encoding="utf-8")
    cursor = paths["cursor"].read_text(encoding="utf-8")
    # Barrel plus every extracted module: lib.js alone holds no implementation since #1355.
    mcp_lib = read_mcp_library(root) or ""
    # Registrations moved out of index.js when it was decomposed; read the whole surface.
    mcp_index = read_mcp_registrations(root)
    principles_flat = " ".join(principles.split())
    cursor_flat = " ".join(cursor.split()).lower()

    principles_ref = skill.find("_development-principles.md")
    route_ref = skill.find("gc_resolve_workflow_route")
    if principles_ref < 0 or route_ref < 0 or principles_ref > route_ref:
        violations.append(
            Violation(
                code="implement-principles-order",
                message="The canonical development principles must load before route resolution.",
                details=["place _development-principles.md before gc_resolve_workflow_route in SKILL.md"],
            )
        )
    required_skill_tokens = (
        "gc.implement.execution-contract/v1",
        "development_principles_verbatim",
        "execution_contract_mutation_attempt",
        "invocation_root",
        '"checkout_mode": "same_checkout"',
        "Ground Control does not manufacture subagents",
    )
    missing_tokens = [token for token in required_skill_tokens if token not in skill]
    if missing_tokens:
        violations.append(
            Violation(
                code="implement-contract-propagation",
                message="Primary-session execution-contract propagation is incomplete.",
                details=[f"missing token: {token}" for token in missing_tokens],
            )
        )

    pause_tokens = (
        "explicit workflow gate",
        "unresolved ambiguity",
        "significant architecture or security decision",
        "unexpectedly material scope",
        "destructive or externally consequential",
        "hard external dependency",
        "enforced cycle cap",
        "Work size, difficulty,",
    )
    missing_pauses = [token for token in pause_tokens if token not in principles_flat]
    if missing_pauses:
        violations.append(
            Violation(
                code="implement-pause-contract",
                message="The development principles do not carry the closed pause-class contract.",
                details=[f"missing token: {token}" for token in missing_pauses],
            )
        )

    verification_tokens = (
        "Make local verification proportionate to risk",
        "Batch related edits",
        "narrowest tests",
        "shared or cross-cutting",
        "security-sensitive",
        "repository-wide completion and policy suites once",
        "mandatory pre-commit, completion, review, CI, Sonar, or final",
    )
    missing_verification = [
        token for token in verification_tokens if token not in principles_flat
    ]
    if missing_verification:
        violations.append(
            Violation(
                code="implement-proportionate-verification-contract",
                message="The development principles do not enforce risk-proportionate verification.",
                details=[f"missing token: {token}" for token in missing_verification],
            )
        )

    review_rules = (
        root / "skills/implement/steps/_review-loop-rules.md"
    ).read_text(encoding="utf-8")
    review_rules_flat = " ".join(review_rules.split())
    step5 = (root / "skills/implement/steps/step-05-quality-assurance.md").read_text(
        encoding="utf-8"
    )
    step6 = (root / "skills/implement/steps/step-06-completion-gate.md").read_text(
        encoding="utf-8"
    )
    step6_flat = " ".join(step6.split())
    step7 = (root / "skills/implement/steps/step-07-stage-precommit.md").read_text(
        encoding="utf-8"
    )
    verification_surface_tokens = (
        (
            review_rules_flat,
            "Do not run `cfg.workflow.completion_command` or "
            "`cfg.workflow.policy_command` after every small fix",
        ),
        (review_rules_flat, "once before leaving the review band on the final post-fix tree"),
        (step5, "Do not run `pre-commit` here"),
        (step6_flat, "Run `cfg.workflow.policy_command`"),
        (step7, "single mandatory pre-publish"),
        (step7, "cfg.workflow.precommit_command"),
    )
    missing_surfaces = [
        token for surface, token in verification_surface_tokens if token not in surface
    ]
    if missing_surfaces:
        violations.append(
            Violation(
                code="implement-verification-boundary-drift",
                message="/implement verification surfaces disagree on batching or mandatory boundaries.",
                details=[f"missing token: {token}" for token in missing_surfaces],
            )
        )

    four_path_tokens = (
        (step1, "`implementation_intent`"),
        (step1, "`feature`"),
        (step1, "`bug-fix`"),
        (step1, "`mixed`"),
        (step4, "`tdd_path`"),
        (step4, "per-clause classification is authoritative"),
        (step4_4, "Path A — New requirement or feature"),
        (step4_4, "Path B — Bug fix on shipped code"),
        (step4_4, "Path C — Reviewer-finding fix"),
        (step4_4, "Path D — Prose-only or static contract narrowing"),
        (step4_4, "unmodified buggy tree"),
        (step4_4, "cannot use the documentation-only carve-out"),
        (step4_4, "runtime-consumed configuration"),
    )
    missing_four_path = [
        token for surface, token in four_path_tokens if token not in surface
    ]
    if missing_four_path:
        violations.append(
            Violation(
                code="implement-four-path-tdd-contract",
                message="/implement's four-path TDD contract is incomplete.",
                details=[f"missing semantic anchor: {token}" for token in missing_four_path],
            )
        )

    fix_evidence_tokens = (
        "Fix locks itself",
        "executable code or a runtime-consumed data contract",
        "fails when the named defect is reintroduced",
        "test file path and test-case or describe-block name",
        "prose-only, no executable surface to lock",
        "auto-posted decision record is written before",
    )
    missing_fix_evidence = [
        token for token in fix_evidence_tokens if token not in review_rules_flat
    ]
    if missing_fix_evidence:
        violations.append(
            Violation(
                code="implement-review-fix-evidence-contract",
                message="/implement's review-fix regression-evidence contract is incomplete.",
                details=[f"missing semantic anchor: {token}" for token in missing_fix_evidence],
            )
        )

    if "gc_prepare_implement_branch" not in step1 or "checkout_mode" not in step1:
        violations.append(
            Violation(
                code="implement-same-checkout-boundary",
                message="Step 1 must use the same-checkout MCP branch operation.",
                details=["require gc_prepare_implement_branch with checkout_mode=same_checkout"],
            )
        )
    if "gc_mark_implement_issue_picked_up" not in step1:
        violations.append(
            Violation(
                code="implement-pickup-side-effect-boundary",
                message="Step 1 must route pickup label/comment mutations through MCP.",
                details=["require gc_mark_implement_issue_picked_up in Step 1"],
            )
        )

    sync_tokens = (
        "gc_synchronize_implement_branch",
        "record_id",
        "pre_sync_sha",
        "fetched_base_sha",
        "already_current",
        "merged_clean",
        "merged_conflicts_resolved",
        "refs/remotes/origin/",
        "cfg.workflow.policy_command",
    )
    missing_sync = [token for token in sync_tokens if token not in step8_5]
    pr_tokens = (
        "gc_render_pr_body",
        "gc_create_synchronized_implement_pr",
        "synchronization_record_id",
        "back to Step",
    )
    missing_pr = [token for token in pr_tokens if token not in step9]
    mcp_tokens = (
        "gc.implement.remote-base-sync/v1",
        "+refs/heads/",
        "refs/remotes/origin/",
        "runSynchronizeImplementBranch",
        "runCreateSynchronizedImplementPr",
        "implement_pr_sync_stale",
    )
    missing_mcp = [token for token in mcp_tokens if token not in mcp_lib]
    registration_tokens = (
        "gc_synchronize_implement_branch",
        "gc_create_synchronized_implement_pr",
    )
    missing_registration = [
        token for token in registration_tokens if token not in mcp_index
    ]
    if missing_sync or missing_pr or missing_mcp or missing_registration:
        violations.append(
            Violation(
                code="implement-pre-pr-sync-contract",
                message="/implement pre-PR synchronization enforcement is incomplete.",
                details=[
                    *[f"Step 8.5 missing: {token}" for token in missing_sync],
                    *[f"Step 9 missing: {token}" for token in missing_pr],
                    *[f"MCP library missing: {token}" for token in missing_mcp],
                    *[f"MCP registration missing: {token}" for token in missing_registration],
                ],
            )
        )
    if re.search(r"\bgh\s+pr\s+create\b", step9):
        violations.append(
            Violation(
                code="implement-direct-pr-create",
                message="Step 9 must route PR creation through the synchronized MCP boundary.",
                details=["remove direct gh pr create from Step 9"],
            )
        )
    step_order = [
        skill.find("steps/step-08-commit-push.md"),
        skill.find("steps/step-08.5-sync-base.md"),
        skill.find("steps/step-09-pr-body.md"),
    ]
    if min(step_order) < 0 or step_order != sorted(step_order):
        violations.append(
            Violation(
                code="implement-pre-pr-sync-order",
                message="The canonical step list must order Step 8, Step 8.5, then Step 9.",
                details=[f"positions: {step_order}"],
            )
        )
    implement_sources = [
        paths["skill"],
        paths["principles"],
        *sorted((root / "skills/implement/steps").glob("*.md")),
    ]
    forbidden = []
    for path in implement_sources:
        text = path.read_text(encoding="utf-8")
        direct_branch = re.search(
            r"\bgit\s+worktree\s+add\b|\bgh\s+issue\s+develop\b",
            text,
        )
        direct_pickup = (
            path == paths["step1"]
            and re.search(r"\bgh\s+(?:api|label|issue)\b[^\n]*\bin-progress\b", text)
        )
        if direct_branch or direct_pickup:
            forbidden.append(str(path.relative_to(root)))
    if forbidden:
        violations.append(
            Violation(
                code="implement-direct-worktree-or-branch-command",
                message="/implement workflow surfaces must use MCP branch and pickup boundaries.",
                details=[f"direct branch/worktree/pickup command in {path}" for path in forbidden],
            )
        )

    contradictory = []
    for path in implement_sources:
        text = path.read_text(encoding="utf-8").lower()
        if "outside the diff's scope" in text or "no scope creep" in text:
            contradictory.append(str(path.relative_to(root)))
    if contradictory:
        violations.append(
            Violation(
                code="implement-contradictory-scope-language",
                message="Workflow text still permits scope-based non-action.",
                details=contradictory,
            )
        )

    if "completion_open_execution_obligations" not in completion:
        violations.append(
            Violation(
                code="implement-obligation-completion-gate",
                message="Step 17 must document completion refusal while obligations are open.",
                details=["missing completion_open_execution_obligations"],
            )
        )
    if "_development-principles.md" not in cursor or "before route resolution" not in cursor_flat:
        violations.append(
            Violation(
                code="implement-driver-principles",
                message="The Cursor driver wrapper must load the canonical principles before routing.",
                details=["update .cursor/skills/implement/SKILL.md"],
            )
        )
    return violations


def _resolve_pr_body(args: argparse.Namespace) -> str | None:
    """Resolve the PR body string from CLI args / environment, in priority order.

    1. ``--pr-body-file`` — local pre-push hook driver.
    2. ``--event-path`` or ``GITHUB_EVENT_PATH`` — CI driver.
    3. ``--pr-number`` — fetched via ``gh pr view <n> --json body``.

    Returns ``None`` when no source is configured (the check is skipped).
    """
    if args.pr_body_file:
        return safe_cli_path(args.pr_body_file).read_text(encoding="utf-8")
    event_path = args.event_path or os.getenv("GITHUB_EVENT_PATH")
    if event_path:
        event = json.loads(safe_cli_path(event_path).read_text(encoding="utf-8"))
        pull_request = event.get("pull_request") or {}
        return pull_request.get("body") or ""
    if args.pr_number is not None:
        # str(int(...)) forces the CLI value to an integer literal so it cannot
        # smuggle an option or metacharacter into the gh argv (S8705).
        result = subprocess.run(
            ["gh", "pr", "view", str(int(args.pr_number)), "--json", "body", "--jq", ".body"],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout
    return None


# Automation PRs that carry no single requirement/traceability of their own, so the
# per-PR body contract does not apply to them (GC-P027): the dev -> main promotion
# (aggregate of already-merged feature PRs), the Release Please release PR (head
# release-please--* targeting main), and the main -> dev back-merge (head
# sync/main-to-dev targeting dev).
RELEASE_PR_BASE = "main"


RELEASE_PR_HEAD = "dev"


# Release Please opens its release PR from a branch prefixed release-please--.
RELEASE_PLEASE_PR_HEAD_PREFIX = "release-please--"


# sync-main-to-dev.yml opens the back-merge PR from this dedicated automation branch.
SYNC_PR_BASE = "dev"


SYNC_PR_HEAD = "sync/main-to-dev"
