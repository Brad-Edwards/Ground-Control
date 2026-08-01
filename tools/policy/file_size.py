"""File-size limit gate (issue #1467).

`docs/CODING_STANDARDS.md` caps source files at 500 lines (Sonar `python:S104`
and its per-language equivalents), but nothing enforced it, so 60 files drifted
past the limit and the largest were the ones edited most often. Size is not a
cosmetic concern here: decomposing a 20,634-line module under #1355 turned up
policy checks that read a single file to find their subject and silently passed
once that file became a barrel, and a drift scan blind to anything but inline
literals. Both were possible because one file held more surfaces than a reviewer
could hold in mind.

The gate is deliberately two-sided. An oversized file that is not listed fails,
and a listed file that is no longer oversized *also* fails. Without the second
rule the grandfather list would be a place to park debt forever; with it, the
list can only shrink, and it reaches empty exactly when the work is finished.
"""

from __future__ import annotations

import json
from pathlib import Path

from .adr_guard import run_git
from .core import REPO_ROOT, Violation, normalize_path

MAX_LINES = 500

GRANDFATHER_PATH = Path("tools/policy/file_size_grandfather.json")

# Languages the limit applies to. Data, docs and lockfiles are excluded: a long
# migration or fixture is not the reviewability problem this gate exists for.
SOURCE_SUFFIXES = (".java", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".py", ".kts")

# Machine-generated trees are rewritten wholesale by their generator, so a size
# limit on them would only ever be satisfied by editing the generator's output.
EXCLUDED_PREFIXES = ("contracts/gen/",)


def _is_source(path: str) -> bool:
    if not path.endswith(SOURCE_SUFFIXES):
        return False
    return not path.startswith(EXCLUDED_PREFIXES)


def count_lines(path: Path) -> int:
    with path.open("rb") as handle:
        return sum(1 for _ in handle)


def load_grandfather(root: Path = REPO_ROOT) -> tuple[dict[str, str], list[Violation]]:
    """Return {path: reason} plus any violations from the list itself."""
    target = root / GRANDFATHER_PATH
    if not target.exists():
        return {}, []
    try:
        payload = json.loads(target.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return {}, [
            Violation(
                "file-size-grandfather-unreadable",
                f"{GRANDFATHER_PATH} is not valid JSON: {exc}",
            )
        ]
    entries = payload.get("entries")
    if not isinstance(entries, dict):
        return {}, [
            Violation(
                "file-size-grandfather-unreadable",
                f"{GRANDFATHER_PATH} must hold an object under 'entries'",
            )
        ]
    resolved: dict[str, str] = {}
    problems: list[Violation] = []
    for raw_path, reason in entries.items():
        if not isinstance(reason, str) or not reason.strip():
            problems.append(
                Violation(
                    "file-size-grandfather-unreadable",
                    f"{GRANDFATHER_PATH}: entry '{raw_path}' needs a non-empty reason",
                )
            )
            continue
        resolved[normalize_path(raw_path)] = reason
    return resolved, problems


def run_file_size_limit_check(root: Path = REPO_ROOT) -> list[Violation]:
    """Fail on any un-grandfathered source file over the limit, and on stale entries."""
    grandfather, violations = load_grandfather(root)

    # Tracked files only. Globbing the working tree would sweep in build output
    # and node_modules, whose sizes are not this repo's to answer for.
    tracked = [
        normalize_path(line)
        for line in run_git(["ls-files"], root).splitlines()
        if line.strip()
    ]
    tracked = [p for p in tracked if _is_source(p)]
    sizes: dict[str, int] = {}
    for rel in tracked:
        target = root / rel
        if target.is_file():
            sizes[rel] = count_lines(target)

    oversized = sorted(p for p, n in sizes.items() if n > MAX_LINES)

    unlisted = [p for p in oversized if p not in grandfather]
    if unlisted:
        violations.append(
            Violation(
                "file-size-limit",
                f"{len(unlisted)} source file(s) exceed the {MAX_LINES}-line limit "
                "(docs/CODING_STANDARDS.md). Split along real seams, or add a "
                f"justified entry to {GRANDFATHER_PATH} if the work is already in flight.",
                [f"{p} ({sizes[p]} lines)" for p in unlisted],
            )
        )

    stale = []
    for listed in sorted(grandfather):
        if listed not in sizes:
            stale.append(f"{listed} (no longer present)")
        elif sizes[listed] <= MAX_LINES:
            stale.append(f"{listed} ({sizes[listed]} lines — now within the limit)")
    if stale:
        violations.append(
            Violation(
                "file-size-grandfather-stale",
                f"{GRANDFATHER_PATH} lists file(s) that no longer need an exemption. "
                "Remove them: the list is only allowed to shrink.",
                stale,
            )
        )

    return violations
