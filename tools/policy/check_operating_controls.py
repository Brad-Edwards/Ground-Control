#!/usr/bin/env python3
"""Guard the source-gate operating-controls durable record.

Ground Control is the durable home for the `compliance-requirement-traceability`
operating control (penumbra-cell routes to it; master issue penumbra-cell#34,
execution issue #1198). The durable record lives in
`docs/architecture/operating-controls/`. This check is the structural gate that
keeps that record from silently rotting: it fails when the docs go missing or
drift away from the control objective, the `before-source` gate, or the four
named sources of truth.

It is intentionally a standalone check rather than a `run_*` function inside
`tools/policy/checks.py`: the `doc-coverage-gate-sync` ADR rule fires on any edit
to `checks.py` and would force unrelated ADR-054 / DOC_STYLE.md edits. Keeping it
standalone (and mirroring penumbra-cell's `tools/policy/check_operating_controls.py`)
avoids that cross-coupling. Wired into `make policy` and the CI policy job.
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DOC_DIR = REPO_ROOT / "docs/architecture/operating-controls"

CONTROL_ID = "compliance-requirement-traceability"
TIMING_GATE = "before-source"

# Terms that, together, assert the control objective is stated rather than just
# named — "product behavior must stay traceable to requirement, ADR, issue,
# test, and evidence records". Only terms NOT already forced elsewhere belong
# here, so every entry is an independent, testable guard:
#   - "requirement" is a substring of CONTROL_ID (compliance-requirement-…);
#   - "test" and "evidence" are forced by REQUIRED_SOURCES_OF_TRUTH
#     ("test evidence", "assessment evidence index").
# Listing those would be dead guards. The doc still must contain them — via the
# control-id and sources-of-truth checks — so the objective stays complete.
REQUIRED_OBJECTIVE_TERMS = [
    "traceable",
    "ADR",
    "issue",
]

# The four sources of truth the control row names (penumbra-cell source.md). The
# durable home must map each to the concrete Ground Control mechanism, so each
# phrase must be present verbatim (case-insensitive).
REQUIRED_SOURCES_OF_TRUTH = [
    "Ground Control links",
    "PR traceability",
    "test evidence",
    "assessment evidence index",
]


def _read(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None


def validate_operating_controls(doc_dir: Path = DOC_DIR) -> list[str]:
    """Return a list of human-readable failures; empty list means the record is intact."""
    failures: list[str] = []

    readme = _read(doc_dir / "README.md")
    if readme is None:
        failures.append("missing operating-controls doc: README.md")
    elif "(source.md)" not in readme:
        failures.append("README.md must link to source.md")

    source = _read(doc_dir / "source.md")
    if source is None:
        failures.append("missing operating-controls doc: source.md")
        return failures

    lowered = source.lower()

    if CONTROL_ID not in source:
        failures.append(f"source.md must name control {CONTROL_ID}")
    if TIMING_GATE not in lowered:
        failures.append(f"source.md must name the {TIMING_GATE} gate")
    for term in REQUIRED_OBJECTIVE_TERMS:
        if term.lower() not in lowered:
            failures.append(f"source.md must state the control objective term: {term}")
    for phrase in REQUIRED_SOURCES_OF_TRUTH:
        if phrase.lower() not in lowered:
            failures.append(f"source.md must name source of truth: {phrase}")

    return failures


def main() -> int:
    failures = validate_operating_controls()
    if failures:
        for failure in failures:
            print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    print("operating controls policy: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
