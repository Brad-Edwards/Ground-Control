"""Policy contract for the repository's strict SonarCloud merge gate."""

from pathlib import Path

from .core import REPO_ROOT, SONAR_NEW_ISSUE_GATE_PATH, Violation


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
