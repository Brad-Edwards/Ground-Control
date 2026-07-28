"""Policy checks: measurement catalogue.

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
from .deploy_artifacts import (
    MCP_LIB_DIR,
    MCP_LIB_PATH,
    read_mcp_library,
)
from .ontology_crosswalk import (
    EMITTER_SOURCE_PATHS,
    IMPLEMENT_MODULE_DIR,
    STATION_CATALOGUE_PATH,
    _PHASE_MARKER_LITERAL_RE,
    _STATION_BY_ACTION_ENTRY_RE,
    _STATION_CALL_RE,
    _STATION_ID_FIELD_RE,
    _STATION_TABLE_RE,
)


def _load_station_catalogue(root: Path) -> tuple[dict | None, list[Violation]]:
    path = root / STATION_CATALOGUE_PATH
    if not path.exists():
        return None, [
            Violation(
                code="measurement-catalogue-missing",
                message=f"{STATION_CATALOGUE_PATH} is missing; ADR-090 station identity has no authority.",
            )
        ]
    try:
        return json.loads(path.read_text(encoding="utf-8")), []
    except json.JSONDecodeError as exc:
        return None, [
            Violation(
                code="measurement-catalogue-json-invalid",
                message=f"{STATION_CATALOGUE_PATH} is not valid JSON.",
                details=[str(exc)],
            )
        ]


def _catalogue_index(catalogue: dict) -> tuple[dict[str, str], dict[tuple[str, str], list[str]]]:
    """Map every declared id and every (alias kind, value) pair to its owning entry."""
    entries: dict[str, str] = {}
    aliases: dict[tuple[str, str], list[str]] = {}

    def absorb(entry: dict, id_field: str, kind: str) -> None:
        entry_id = entry.get(id_field)
        if not isinstance(entry_id, str):
            return
        entries[entry_id] = kind
        for alias_kind, values in (entry.get("aliases") or {}).items():
            for value in values if isinstance(values, list) else []:
                aliases.setdefault((alias_kind, value), []).append(entry_id)

    for station in catalogue.get("stations") or []:
        absorb(station, "station_id", "station")
    for marker in catalogue.get("lifecycle_markers") or []:
        absorb(marker, "marker_id", "lifecycle_marker")
    return entries, aliases


def _resolves(value: str, kind: str, entries: dict[str, str], aliases: dict[tuple[str, str], list[str]]) -> bool:
    return value in entries or (kind, value) in aliases


def run_measurement_catalogue_check(root: Path = REPO_ROOT) -> list[Violation]:
    """ADR-090 / GC-O014: the station catalogue is the authority for station identity.

    A published catalogue that nothing checks is the same divergence ADR-090 exists to
    close, so this asserts the catalogue is internally coherent AND that every id the
    running system actually emits resolves against it. Drift is read from the emitter
    sources themselves, never from a second copy of the vocabulary kept in step.
    """
    catalogue, violations = _load_station_catalogue(root)
    if catalogue is None:
        return violations

    entries, aliases = _catalogue_index(catalogue)

    station_ids = [s.get("station_id") for s in catalogue.get("stations") or []]
    marker_ids = [m.get("marker_id") for m in catalogue.get("lifecycle_markers") or []]

    duplicates = sorted({i for i in station_ids + marker_ids if (station_ids + marker_ids).count(i) > 1})
    if duplicates:
        violations.append(
            Violation(
                code="measurement-catalogue-duplicate-id",
                message="Station and lifecycle-marker ids must be unique across both sets.",
                details=duplicates,
            )
        )

    overlap = sorted(set(station_ids) & set(marker_ids))
    if overlap:
        violations.append(
            Violation(
                code="measurement-catalogue-station-marker-overlap",
                message="An id is declared as both a station and a lifecycle marker; a recorded transition is not a gate.",
                details=overlap,
            )
        )

    for (alias_kind, value), owners in sorted(aliases.items()):
        if len(set(owners)) > 1:
            violations.append(
                Violation(
                    code="measurement-catalogue-ambiguous-alias",
                    message=f"Alias {alias_kind}='{value}' resolves to more than one entry.",
                    details=sorted(set(owners)),
                )
            )

    declared_kinds = set((catalogue.get("alias_kinds") or {}).keys())
    used_kinds = {kind for kind, _ in aliases}
    undeclared = sorted(used_kinds - declared_kinds)
    if undeclared:
        violations.append(
            Violation(
                code="measurement-catalogue-undeclared-alias-kind",
                message="An alias uses a kind that alias_kinds does not declare.",
                details=undeclared,
            )
        )

    violations.extend(_check_emitter_station_drift(root, entries, aliases))
    violations.extend(_check_phase_marker_drift(root, entries, aliases))
    violations.extend(_check_routing_stage_drift(root, catalogue, entries, aliases))
    return violations


def _check_emitter_station_drift(
    root: Path, entries: dict[str, str], aliases: dict[tuple[str, str], list[str]]
) -> list[Violation]:
    """Every station id any MCP emitter writes must be a declared entry."""
    emitted: set[str] = set()
    scanned = False
    sources = [(p, (root / p)) for p in EMITTER_SOURCE_PATHS]
    impl_dir = root / IMPLEMENT_MODULE_DIR
    if impl_dir.is_dir():
        sources.extend((str(f.relative_to(root)), f) for f in sorted(impl_dir.glob("*.js")))
    lib_dir = root / MCP_LIB_DIR
    if lib_dir.is_dir():
        # The review stations are emitted from the extracted library modules, not from the
        # barrel, so the scan follows the implementation rather than one path.
        sources.extend((str(f.relative_to(root)), f) for f in sorted(lib_dir.glob("*.js")))
    for _relative_path, source_path in sources:
        if not source_path.exists():
            continue
        scanned = True
        source = source_path.read_text(encoding="utf-8")
        emitted.update(_STATION_CALL_RE.findall(source))
        emitted.update(_STATION_ID_FIELD_RE.findall(source))
        for block in _STATION_TABLE_RE.findall(source):
            emitted.update(_STATION_BY_ACTION_ENTRY_RE.findall(block))
    if not scanned:
        return []

    unknown = sorted(s for s in emitted if s not in entries)
    if unknown:
        return [
            Violation(
                code="measurement-catalogue-emitter-drift",
                message=(
                    "An MCP emitter writes a station id the catalogue does not declare; "
                    "add it to the catalogue or resolve it to an existing entry. "
                    f"Sources scanned: {', '.join(EMITTER_SOURCE_PATHS)}."
                ),
                details=unknown,
            )
        ]
    return []


def _check_phase_marker_drift(
    root: Path, entries: dict[str, str], aliases: dict[tuple[str, str], list[str]]
) -> list[Violation]:
    """Every issue-thread `gc:phase` marker value must resolve to a declared entry.

    The issue thread is the durable workflow record (ADR-029), so a marker value is a
    published identity as much as an emitted station id. Without this the catalogue
    would be authoritative for two of the three surfaces it claims.
    """
    source = read_mcp_library(root)
    if source is None:
        return []

    written = {
        value
        for match in _PHASE_MARKER_LITERAL_RE.findall(source)
        for value in match
        if value
    }
    unknown = sorted(
        value
        for value in written
        if value not in entries and not _resolves(value, "issue_thread_marker", entries, aliases)
    )
    if unknown:
        return [
            Violation(
                code="measurement-catalogue-phase-marker-drift",
                message=(
                    f"{MCP_LIB_PATH} writes a gc:phase marker value the catalogue does not declare; "
                    "add it as a lifecycle marker or as an issue_thread_marker alias."
                ),
                details=unknown,
            )
        ]
    return []


def _check_routing_stage_drift(
    root: Path, catalogue: dict, entries: dict[str, str], aliases: dict[tuple[str, str], list[str]]
) -> list[Violation]:
    """Every ADR-036 routing stage resolves to an entry or is declared a non-station."""
    config_path = root / ".ground-control.yaml"
    if not config_path.exists():
        return []

    # Stage ids are the keys directly under `routing.stages`. Track the block's own indent
    # rather than assuming a fixed depth: hardcoding one silently scans nothing the moment
    # the file is reindented, and a check that scans nothing passes vacuously.
    stages: list[str] = []
    stages_indent: int | None = None
    for line in config_path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if stages_indent is None:
            if re.match(r"^\s*stages:\s*$", line):
                stages_indent = indent
            continue
        if indent <= stages_indent:
            break
        entry = re.match(r"^\s+([a-z0-9_]+):\s*$", line)
        if entry and indent == stages_indent + 2:
            stages.append(entry.group(1))

    excused = {e.get("adr036_stage") for e in catalogue.get("non_station_stages") or []}
    unresolved = sorted(
        stage for stage in set(stages) if stage not in excused and not _resolves(stage, "adr036_stage", entries, aliases)
    )
    if unresolved:
        return [
            Violation(
                code="measurement-catalogue-routing-stage-drift",
                message=(
                    "An ADR-036 routing stage resolves to no station, no lifecycle marker, and no "
                    "declared non-station stage."
                ),
                details=unresolved,
            )
        ]
    return []
