"""Scope-language and completion-boundary checks for the /implement workflow."""

import re
from pathlib import Path

from .core import Violation


def check_scope_and_completion_contract(root: Path) -> list[Violation]:
    """Reject bypass language and require completion-obligation enforcement."""
    violations: list[Violation] = []
    paths = {
        "skill": root / "skills/implement/SKILL.md",
        "principles": root / "skills/implement/_development-principles.md",
        "step1": root / "skills/implement/steps/step-01-issue-branch-resolution.md",
    }
    completion = (root / "skills/implement/steps/step-17-completion.md").read_text(
        encoding="utf-8"
    )
    cursor = (root / ".cursor/skills/implement/SKILL.md").read_text(encoding="utf-8")
    cursor_flat = " ".join(cursor.split()).lower()
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
