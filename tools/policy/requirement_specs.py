"""Policy check for specs-as-code requirement files (issue #1500).

Requirements migrated out of the relational store live as `docs/requirements/<UID>/requirement.md`
with a small, versioned YAML frontmatter contract. This check validates that contract
deterministically so the files stay a trustworthy record; it is intentionally dependency-free
(no PyYAML) and only parses the flat frontmatter keys it governs.
"""

from __future__ import annotations

from pathlib import Path

from .core import REPO_ROOT, Violation

REQUIREMENT_SPECS_DIR = REPO_ROOT / "docs" / "requirements"

REQUIRED_KEYS = ("id", "title", "status", "type", "priority")
STATUS_VALUES = {"DRAFT", "ACTIVE", "DEPRECATED", "ARCHIVED"}
TYPE_VALUES = {"FUNCTIONAL", "NON_FUNCTIONAL", "CONSTRAINT", "INTERFACE"}
PRIORITY_VALUES = {"MUST", "SHOULD", "COULD", "WONT"}

FRONTMATTER_CODE = "requirement-spec-frontmatter"


def _parse_frontmatter(text: str) -> dict[str, str] | None:
    """Return the leading ``---`` frontmatter block as a flat dict, or None when it is
    missing or unterminated. Only the first ``key: value`` per line is captured."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return None
    frontmatter: dict[str, str] = {}
    for line in lines[1:]:
        if line.strip() == "---":
            return frontmatter
        key, sep, value = line.partition(":")
        if sep:
            frontmatter[key.strip()] = value.strip()
    return None  # unterminated frontmatter block


def run_requirement_specs_frontmatter_check(specs_dir: Path | None = None) -> list[Violation]:
    directory = specs_dir if specs_dir is not None else REQUIREMENT_SPECS_DIR
    violations: list[Violation] = []
    if not directory.is_dir():
        return violations

    for spec in sorted(directory.glob("*/requirement.md")):
        try:
            rel = spec.relative_to(REPO_ROOT)
        except ValueError:
            rel = spec
        try:
            text = spec.read_text(encoding="utf-8")
        except OSError as exc:
            violations.append(Violation(FRONTMATTER_CODE, f"{rel}: unreadable ({exc})"))
            continue

        frontmatter = _parse_frontmatter(text)
        if frontmatter is None:
            violations.append(Violation(FRONTMATTER_CODE, f"{rel}: missing or unterminated YAML frontmatter"))
            continue

        details: list[str] = []
        for key in REQUIRED_KEYS:
            if not frontmatter.get(key):
                details.append(f"missing required key '{key}'")

        folder = spec.parent.name
        spec_id = frontmatter.get("id")
        if spec_id and spec_id != folder:
            details.append(f"id '{spec_id}' does not match folder '{folder}'")

        status = frontmatter.get("status")
        if status and status not in STATUS_VALUES:
            details.append(f"invalid status '{status}' (expected one of {sorted(STATUS_VALUES)})")

        req_type = frontmatter.get("type")
        if req_type and req_type not in TYPE_VALUES:
            details.append(f"invalid type '{req_type}' (expected one of {sorted(TYPE_VALUES)})")

        priority = frontmatter.get("priority")
        if priority and priority not in PRIORITY_VALUES:
            details.append(f"invalid priority '{priority}' (expected one of {sorted(PRIORITY_VALUES)})")

        wave = frontmatter.get("wave")
        if wave and not wave.lstrip("-").isdigit():
            details.append(f"wave '{wave}' is not an integer")

        if details:
            violations.append(Violation(FRONTMATTER_CODE, str(rel), details))

    return violations
