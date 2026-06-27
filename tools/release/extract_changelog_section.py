#!/usr/bin/env python3
"""Extract a single version's notes from the collated CHANGELOG.md.

The CI ``release`` job (``.github/workflows/ci.yml``) runs this on a ``vX.Y.Z``
tag push to derive the GitHub Release body from the changelog section that
``towncrier build`` already collated for that version (ADR-063 §4 step 7,
issue #1224). It deliberately does NOT re-run towncrier or invent a second
release-notes schema — ``CHANGELOG.md`` is the single source of truth.

Heading grammar matches ``towncrier.toml``'s
``title_format = "## [{version}] - {project_date}"``. The version is matched
exactly inside the brackets, so ``1.2`` never matches ``## [1.20.0]``. A
missing section is a hard error (non-zero exit) so CI fails loudly rather than
publishing an empty or hand-copied release.

The caller passes a bare semver (``1.4.0``); stripping the leading ``v`` from
the git tag ``v1.4.0`` is the workflow's job (``${GITHUB_REF_NAME#v}``).
"""

from __future__ import annotations

import argparse
import re
import sys

# A release heading: "## [<version>] - <date>". The capture is only used to
# detect *any* release heading (the section terminator); the requested version
# is matched separately and exactly.
_RELEASE_HEADING = re.compile(r"^## \[[^\]]+\]")


def _heading_for(version: str) -> re.Pattern[str]:
    """Match the exact bracketed version heading, e.g. ``## [1.2.0]``."""
    return re.compile(r"^## \[" + re.escape(version) + r"\]")


def extract_section(changelog_text: str, version: str) -> str:
    """Return the notes for ``version`` with surrounding blank lines stripped.

    Raises ``KeyError`` when no ``## [<version>]`` heading exists or the section
    is empty.
    """
    want = _heading_for(version)
    lines = changelog_text.splitlines()

    start = None
    for i, line in enumerate(lines):
        if want.match(line):
            start = i + 1
            break

    if start is None:
        raise KeyError(version)

    end = len(lines)
    for j in range(start, len(lines)):
        if _RELEASE_HEADING.match(lines[j]):
            end = j
            break

    section = "\n".join(lines[start:end]).strip("\n")
    if not section.strip():
        raise KeyError(version)
    return section


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--version",
        required=True,
        help="Bare semver to extract (e.g. 1.4.0); no leading 'v'.",
    )
    parser.add_argument(
        "--changelog",
        default="CHANGELOG.md",
        help="Path to the collated changelog (default: CHANGELOG.md).",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Write the section here (default: stdout).",
    )
    args = parser.parse_args(argv)

    try:
        with open(args.changelog, encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        print(f"error: cannot read changelog {args.changelog!r}: {exc}", file=sys.stderr)
        return 2

    try:
        section = extract_section(text, args.version)
    except KeyError:
        print(
            f"error: no changelog section for version {args.version!r} in "
            f"{args.changelog!r}. The collated CHANGELOG.md must contain a "
            f"'## [{args.version}] - <date>' heading before the tag is pushed.",
            file=sys.stderr,
        )
        return 1

    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(section + "\n")
    else:
        print(section)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
