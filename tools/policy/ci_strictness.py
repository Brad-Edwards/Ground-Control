"""Policy contracts for the repository's merge gates.

Two contracts live here. The Sonar one pins the strict quality-gate ordering.
The required-context one pins the correspondence between the branch-protection
baseline and the jobs that actually produce those checks.
"""

import fnmatch
import json
from pathlib import Path

import yaml

from .core import (
    BRANCH_PROTECTION_BASELINE_PATH,
    CI_STRICTNESS_BRANCHES,
    CI_STRICTNESS_REQUIRED_CONTEXTS,
    REPO_ROOT,
    SONAR_NEW_ISSUE_GATE_PATH,
    Violation,
    require_scanned,
)


WORKFLOWS_DIR = Path(".github/workflows")


# Contexts posted by a hosted app rather than by a job in this repository, so no
# local workflow can produce them. This exemption is deliberately tiny and
# shrink-only: a test asserts every entry is still a required context, so an
# entry cannot outlive the check it exempts and quietly widen the carve-out.
EXTERNALLY_POSTED_CONTEXTS = frozenset(
    {
        "GitGuardian Security Checks",
        "SonarCloud Code Analysis",
    }
)


SONAR_WORKFLOW_PATH = Path(".github/workflows/sonarcloud.yml")
SONAR_QUALITY_GATE_ANCHOR = "SonarSource/sonarqube-quality-gate-action@"
SONAR_TOKEN_BINDING = "SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}"
SONAR_ALWAYS_RUN_ANCHOR = "if: ${{ !cancelled() }}"


def run_sonar_strictness_contract(root: Path = REPO_ROOT) -> list[Violation]:
    """Require Sonar's quality gate and the stricter zero-open-issues gate."""
    violations: list[Violation] = []
    workflow_path = root / SONAR_WORKFLOW_PATH
    issue_gate_path = root / SONAR_NEW_ISSUE_GATE_PATH

    if not workflow_path.exists():
        return [
            Violation(
                code="sonar-strictness-workflow-missing",
                message="The SonarCloud workflow is required for strict merge enforcement.",
                details=[f"expected at {SONAR_WORKFLOW_PATH.as_posix()}"],
            )
        ]
    if not issue_gate_path.exists():
        violations.append(
            Violation(
                code="sonar-strictness-script-missing",
                message="The zero-open-issues SonarCloud gate script is missing.",
                details=[f"expected at {SONAR_NEW_ISSUE_GATE_PATH.as_posix()}"],
            )
        )

    workflow_text = workflow_path.read_text(encoding="utf-8")
    quality_gate_offset = workflow_text.find(SONAR_QUALITY_GATE_ANCHOR)
    issue_gate_offset = workflow_text.find(SONAR_NEW_ISSUE_GATE_PATH.as_posix())
    if quality_gate_offset < 0:
        violations.append(
            Violation(
                code="sonar-strictness-quality-gate",
                message="SonarCloud CI must wait for the hosted quality gate.",
                details=[f"missing {SONAR_QUALITY_GATE_ANCHOR} in {SONAR_WORKFLOW_PATH.as_posix()}"],
            )
        )
    if issue_gate_offset < 0:
        violations.append(
            Violation(
                code="sonar-strictness-zero-issue-gate",
                message="SonarCloud CI must fail when any new-code issue remains open.",
                details=[f"missing {SONAR_NEW_ISSUE_GATE_PATH.as_posix()} invocation"],
            )
        )
    elif quality_gate_offset >= 0 and issue_gate_offset < quality_gate_offset:
        violations.append(
            Violation(
                code="sonar-strictness-gate-order",
                message="The zero-open-issues check must run after the hosted quality gate.",
                details=[f"repair step order in {SONAR_WORKFLOW_PATH.as_posix()}"],
            )
        )
    if workflow_text.count(SONAR_TOKEN_BINDING) < 3:
        violations.append(
            Violation(
                code="sonar-strictness-token-binding",
                message="Every Sonar scan, quality, and zero-issue step must receive SONAR_TOKEN.",
                details=["expected three step-scoped SONAR_TOKEN bindings"],
            )
        )
    if SONAR_ALWAYS_RUN_ANCHOR not in workflow_text:
        violations.append(
            Violation(
                code="sonar-strictness-zero-issue-always-runs",
                message="The zero-open-issues check must run after a hosted gate failure.",
                details=[f"missing {SONAR_ALWAYS_RUN_ANCHOR} on the zero-issue step"],
            )
        )
    return violations


def _matches_any(branch: str, patterns: list) -> bool:
    """Whether `branch` matches any entry, which may be a glob."""
    return any(fnmatch.fnmatch(branch, str(pattern)) for pattern in patterns)


def _trigger_covers(branch: str, pull_request: object) -> bool:
    """Whether a `pull_request` trigger runs for pull requests into `branch`.

    An absent `branches` / `branches-ignore` filter matches every branch. Both
    filters accept glob patterns, which is why this matches rather than compares.
    """
    if not isinstance(pull_request, dict):
        # `pull_request:` with no body, or a list-form `on:`; no filter, so it runs.
        return True
    ignore = pull_request.get("branches-ignore")
    if isinstance(ignore, list) and _matches_any(branch, ignore):
        return False
    allowed = pull_request.get("branches")
    return not isinstance(allowed, list) or _matches_any(branch, allowed)


def _load_workflow(path: Path) -> dict | None:
    """Parse one workflow, or None when it is unreadable or not a mapping.

    Returning None rather than raising keeps one malformed file from masking the
    rest of the scan; the scan-floor guard is what turns "resolved nothing" into
    a failure.
    """
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (yaml.YAMLError, OSError):
        return None
    return document if isinstance(document, dict) else None


def _pull_request_trigger(document: dict) -> tuple[bool, object]:
    """Whether the workflow runs on `pull_request`, and that trigger's config."""
    # PyYAML resolves an unquoted `on:` key to the boolean True (the YAML 1.1
    # "y/yes/on" rule), so both spellings have to be accepted here.
    triggers = document.get("on", document.get(True))
    if isinstance(triggers, dict):
        return "pull_request" in triggers, triggers.get("pull_request")
    if isinstance(triggers, list):
        return "pull_request" in triggers, None
    return triggers == "pull_request", None


def _reported_check_name(job_id: object, definition: object) -> str | None:
    """The status context this job reports, or None when it resolves to no single name.

    GitHub reports a job's `name:` when it sets one and its `jobs.<id>` key
    otherwise, and branch protection waits on the reported name. A name carrying
    a `${{ }}` expression expands per matrix leg, so it is neither the id nor the
    raw template; such a job contributes no resolvable context, and whatever it
    was meant to satisfy fails as unproduced rather than passing on a guess.
    """
    name = definition.get("name") if isinstance(definition, dict) else None
    if not (isinstance(name, str) and name.strip()):
        return str(job_id)
    return None if "${{" in name else name.strip()


def _check_names_by_branch(root: Path) -> tuple[dict[str, set[str]], int]:
    """Reported check names per protected branch, and the workflow scan count.

    This is per-branch rather than one pooled set because a `pull_request`
    trigger filtered to one branch never runs for the other, so pooling would
    accept a repository where `main` requires a check only `dev` can produce and
    every `main` pull request waits forever. Only pull-request-triggered
    workflows count: a job that runs solely on push never reports a context on
    the pull request it is meant to gate.
    """
    by_branch: dict[str, set[str]] = {branch: set() for branch in CI_STRICTNESS_BRANCHES}
    scanned = 0
    workflows_dir = root / WORKFLOWS_DIR
    if not workflows_dir.is_dir():
        return by_branch, scanned
    paths = sorted(workflows_dir.glob("*.yml")) + sorted(workflows_dir.glob("*.yaml"))
    for path in paths:
        document = _load_workflow(path)
        if document is None:
            continue
        scanned += 1
        runs_on_pull_request, pull_request = _pull_request_trigger(document)
        jobs = document.get("jobs")
        if not runs_on_pull_request or not isinstance(jobs, dict):
            continue
        names = {
            name
            for job_id, definition in jobs.items()
            if (name := _reported_check_name(job_id, definition)) is not None
        }
        for branch in CI_STRICTNESS_BRANCHES:
            if _trigger_covers(branch, pull_request):
                by_branch[branch].update(names)
    return by_branch, scanned


def _load_baseline(path: Path) -> tuple[dict, Violation | None]:
    """The parsed branch-protection baseline, or the violation that blocks reading it."""
    if not path.exists():
        return {}, Violation(
            code="ci-required-context-baseline-missing",
            message="The branch-protection baseline is required to verify the merge gate.",
            details=[f"expected at {BRANCH_PROTECTION_BASELINE_PATH.as_posix()}"],
        )
    try:
        return json.loads(path.read_text(encoding="utf-8")), None
    except json.JSONDecodeError as error:
        return {}, Violation(
            code="ci-required-context-baseline-unreadable",
            message="The branch-protection baseline is not valid JSON.",
            details=[f"{BRANCH_PROTECTION_BASELINE_PATH.as_posix()}: {error}"],
        )


def _context_drift(branch: str, declared: set[str], expected: set[str]) -> Violation | None:
    """The drift between one branch's declared contexts and the contract, if any."""
    missing = sorted(expected - declared)
    extra = sorted(declared - expected)
    if not missing and not extra:
        return None
    return Violation(
        code="ci-required-context-baseline-drift",
        message="The branch-protection baseline must match the declared required-context set.",
        details=(
            [f"{branch}: missing '{name}'" for name in missing]
            + [f"{branch}: unexpected '{name}'" for name in extra]
        ),
    )


def _baseline_violations(branches: dict, expected: set[str]) -> list[Violation]:
    """Per-branch baseline shape: the branch is declared, strict, and matches the contract."""
    violations: list[Violation] = []
    for branch in CI_STRICTNESS_BRANCHES:
        config = branches.get(branch)
        if not isinstance(config, dict):
            violations.append(
                Violation(
                    code="ci-required-context-branch-missing",
                    message="Every protected branch must declare its required status checks.",
                    details=[
                        f"{BRANCH_PROTECTION_BASELINE_PATH.as_posix()}: no entry for '{branch}'"
                    ],
                )
            )
            continue
        checks = config.get("required_status_checks")
        checks = checks if isinstance(checks, dict) else {}
        if checks.get("strict") is not True:
            violations.append(
                Violation(
                    code="ci-required-context-not-strict",
                    message="Required status checks must stay strict on every protected branch.",
                    details=[f"{branch}: expected strict=true"],
                )
            )
        drift = _context_drift(branch, set(checks.get("contexts") or []), expected)
        if drift:
            violations.append(drift)
    return violations


def _unproduced_violation(
    by_branch: dict[str, set[str]], expected: set[str]
) -> Violation | None:
    """The contexts no pull-request job produces, named per protected branch."""
    details: list[str] = []
    for branch in CI_STRICTNESS_BRANCHES:
        produced = by_branch.get(branch, set())
        details += [
            f"{branch}: required context '{name}' has no pull-request job in "
            f"{WORKFLOWS_DIR.as_posix()}/ that runs for this branch"
            for name in sorted(expected - EXTERNALLY_POSTED_CONTEXTS - produced)
        ]
    if not details:
        return None
    return Violation(
        code="ci-required-context-unproduced",
        message=(
            "Every required status check must be produced by a pull-request job "
            "on every protected branch."
        ),
        details=details,
    )


def run_ci_required_context_contract(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert every required status check is real, and that the baseline matches.

    A required context with no job behind it never reports, so the pull request
    waits on a check that can never arrive and merges become impossible. The
    inverse, a job that quietly stops being required, is the gate-weakening
    direction. Both are drift between two files nothing else compares, so the
    check is two-sided over `CI_STRICTNESS_REQUIRED_CONTEXTS` (GC-P030, ADR-091).
    """
    baseline, blocked = _load_baseline(root / BRANCH_PROTECTION_BASELINE_PATH)
    if blocked:
        return [blocked]

    branches = baseline.get("branches")
    expected = set(CI_STRICTNESS_REQUIRED_CONTEXTS)
    violations = _baseline_violations(branches if isinstance(branches, dict) else {}, expected)

    by_branch, scanned = _check_names_by_branch(root)
    guard = require_scanned("pull-request workflow inventory", scanned)
    if guard:
        return violations + guard

    unproduced = _unproduced_violation(by_branch, expected)
    return violations + ([unproduced] if unproduced else [])
