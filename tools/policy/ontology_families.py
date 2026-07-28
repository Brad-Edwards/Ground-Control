"""Policy checks: ontology families.

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
from .adr_guard import (
    load_json,
)
from .core import (
    CONTRACT_REQUIRED_PATHS,
    FRONTEND_CONTRACT_SHIM_PATH,
    GENERATED_CONTRACT_EXPORT,
    REPO_ROOT,
    Violation,
)
from .enum_contract import (
    ONTOLOGY_CROSSWALK_PATH,
    ONTOLOGY_CROSSWALK_SCHEMA_VERSION,
    ONTOLOGY_CROSSWALK_TIME_FAMILY,
    ONTOLOGY_EFFECT_VOCABULARY,
    ONTOLOGY_EXTERNAL_SNAPSHOT_ROOT,
    _load_ontology_family_ids,
    _safe_ontology_external_path,
)
from .env_templates import (
    ONTOLOGY_CONTRACT_PATHS,
    ONTOLOGY_SCHEMA_VERSIONS,
    _ontology_violation,
)


def _validate_crosswalk_pin(
    root: Path, crosswalk: dict[str, Any]
) -> tuple[set[str], list[Violation]]:
    """Validate the external pin and return (aces_family_ids, violations)."""
    pin = crosswalk.get("external_pin")
    if not isinstance(pin, dict):
        return set(), [
            _ontology_violation("ontology-crosswalk-pin-invalid", "Crosswalk must declare an external_pin object.")
        ]
    violations: list[Violation] = []
    distribution = pin.get("distribution")
    release_version = pin.get("release_version")
    sha256 = pin.get("sha256")
    snapshot_rel = pin.get("reference_snapshot")
    for field in ("authority", "distribution", "release_version", "artifact_path", "catalog_schema_version"):
        if not isinstance(pin.get(field), str) or not pin[field].strip():
            violations.append(
                _ontology_violation("ontology-crosswalk-pin-invalid", f"external_pin.{field} must be a non-empty string.")
            )
    if not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", sha256):
        violations.append(
            _ontology_violation("ontology-crosswalk-pin-invalid", "external_pin.sha256 must be a 64-character lowercase hex digest.")
        )
    if not _safe_ontology_external_path(root, snapshot_rel):
        violations.append(
            _ontology_violation(
                "ontology-crosswalk-pin-invalid",
                f"external_pin.reference_snapshot must be a repo-relative path under {ONTOLOGY_EXTERNAL_SNAPSHOT_ROOT.as_posix()}/.",
                f"got {snapshot_rel!r}",
            )
        )
        return set(), violations
    # The snapshot must live under external/<distribution>/<release_version>/ so a
    # release bump adds a new snapshot instead of mutating a pinned one.
    if isinstance(distribution, str) and isinstance(release_version, str):
        expected_prefix = f"{ONTOLOGY_EXTERNAL_SNAPSHOT_ROOT.as_posix()}/{distribution}/{release_version}/"
        if not snapshot_rel.startswith(expected_prefix):
            violations.append(
                _ontology_violation(
                    "ontology-crosswalk-pin-invalid",
                    "external_pin.reference_snapshot must live under external/<distribution>/<release_version>/.",
                    f"expected prefix {expected_prefix}, got {snapshot_rel}",
                )
            )
    snapshot_path = root / Path(snapshot_rel)
    if not snapshot_path.is_file():
        violations.append(
            _ontology_violation("ontology-crosswalk-snapshot-missing", f"Reference snapshot is missing: {snapshot_rel}.")
        )
        return set(), violations
    snapshot_bytes = snapshot_path.read_bytes()
    if isinstance(sha256, str):
        actual = hashlib.sha256(snapshot_bytes).hexdigest()
        if actual != sha256:
            violations.append(
                _ontology_violation(
                    "ontology-crosswalk-hash-drift",
                    "Reference snapshot bytes do not match external_pin.sha256; re-review the crosswalk against the new release.",
                    f"pinned {sha256}, actual {actual}",
                )
            )
    # ADR-084 §2/§4: the reference catalog rejects duplicate-key JSON, so a pinned
    # snapshot cannot carry an ambiguous repeated key while passing hash validation.
    try:
        snapshot = load_json(snapshot_path, reject_duplicate_keys=True)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        violations.append(
            _ontology_violation("ontology-crosswalk-snapshot-invalid-json", f"Reference snapshot is not readable JSON: {snapshot_rel}.", str(exc))
        )
        return set(), violations
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("families"), dict):
        violations.append(
            _ontology_violation("ontology-crosswalk-snapshot-invalid-json", f"Reference snapshot must declare a families object: {snapshot_rel}.")
        )
        return set(), violations
    declared_catalog_version = pin.get("catalog_schema_version")
    snapshot_version = snapshot.get("schema_version")
    if isinstance(declared_catalog_version, str) and snapshot_version != declared_catalog_version:
        violations.append(
            _ontology_violation(
                "ontology-crosswalk-pin-invalid",
                "external_pin.catalog_schema_version does not match the snapshot schema_version.",
                f"pinned {declared_catalog_version!r}, snapshot {snapshot_version!r}",
            )
        )
    aces_family_ids = {fid for fid in snapshot["families"] if isinstance(fid, str) and fid}
    return aces_family_ids, violations


def run_ontology_crosswalk_check(root: Path = REPO_ROOT) -> list[Violation]:
    """Validate the ACES concept-family crosswalk: pin/hash integrity, both-catalog
    referential integrity, and the closed effect vocabulary (ADR-084 §4)."""

    crosswalk_path = root / ONTOLOGY_CROSSWALK_PATH
    if not crosswalk_path.is_file():
        return [
            _ontology_violation(
                "ontology-crosswalk-missing",
                f"Required crosswalk artifact is missing: {ONTOLOGY_CROSSWALK_PATH.as_posix()}.",
            )
        ]
    try:
        crosswalk = load_json(crosswalk_path, reject_duplicate_keys=True)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        return [
            _ontology_violation(
                "ontology-crosswalk-invalid-json",
                f"Crosswalk artifact is not readable JSON: {ONTOLOGY_CROSSWALK_PATH.as_posix()}.",
                str(exc),
            )
        ]
    if not isinstance(crosswalk, dict):
        return [_ontology_violation("ontology-crosswalk-shape-invalid", "Crosswalk artifact must contain a JSON object.")]

    violations: list[Violation] = []
    if crosswalk.get("schema_version") != ONTOLOGY_CROSSWALK_SCHEMA_VERSION:
        violations.append(
            _ontology_violation(
                "ontology-crosswalk-version-invalid",
                "Crosswalk artifact has an unsupported schema version.",
                f"expected {ONTOLOGY_CROSSWALK_SCHEMA_VERSION}, got {crosswalk.get('schema_version')!r}",
            )
        )

    gc_catalog_rel = crosswalk.get("gc_catalog")
    declared_gc_version = crosswalk.get("gc_catalog_schema_version")
    canonical_gc_catalog = ONTOLOGY_CONTRACT_PATHS["families"].as_posix()
    canonical_gc_version = ONTOLOGY_SCHEMA_VERSIONS["families"]
    gc_family_ids: set[str] = set()
    if not isinstance(gc_catalog_rel, str) or not gc_catalog_rel:
        violations.append(_ontology_violation("ontology-crosswalk-shape-invalid", "Crosswalk must declare gc_catalog."))
    elif gc_catalog_rel != canonical_gc_catalog:
        # Bind to the canonical GC catalog so a crosswalk cannot point validation
        # at an alternate file with invented family IDs (ADR-084 §2).
        violations.append(
            _ontology_violation(
                "ontology-crosswalk-gc-catalog-invalid",
                "Crosswalk gc_catalog must be the canonical Ground Control family catalog.",
                f"expected {canonical_gc_catalog}, got {gc_catalog_rel}",
            )
        )
    else:
        gc_family_ids, loaded_gc_version, gc_error = _load_ontology_family_ids(root / Path(gc_catalog_rel))
        if gc_error is not None or not gc_family_ids:
            violations.append(
                _ontology_violation(
                    "ontology-crosswalk-gc-catalog-unreadable",
                    f"Crosswalk gc_catalog is missing or unreadable: {gc_catalog_rel}.",
                    *([gc_error] if isinstance(gc_error, str) else []),
                )
            )
        # The declared version and the loaded catalog's version must both equal the
        # canonical version, so neither the pin nor the catalog can drift unseen.
        if declared_gc_version != canonical_gc_version or loaded_gc_version != canonical_gc_version:
            violations.append(
                _ontology_violation(
                    "ontology-crosswalk-gc-catalog-invalid",
                    "Crosswalk gc_catalog_schema_version and the loaded catalog must both be the canonical GC catalog version.",
                    f"canonical {canonical_gc_version!r}, declared {declared_gc_version!r}, loaded {loaded_gc_version!r}",
                )
            )

    aces_family_ids, pin_violations = _validate_crosswalk_pin(root, crosswalk)
    violations.extend(pin_violations)

    alignments = crosswalk.get("alignments")
    if not isinstance(alignments, list) or not alignments:
        violations.append(_ontology_violation("ontology-crosswalk-shape-invalid", "Crosswalk must declare a non-empty alignments array."))
        alignments = []

    seen_pairs: set[tuple[str, str]] = set()
    for row in alignments:
        if not isinstance(row, dict):
            violations.append(_ontology_violation("ontology-crosswalk-alignment-invalid", "Every alignment must be an object."))
            continue
        gc_family = row.get("gc_family")
        aces_family = row.get("aces_family")
        effect = row.get("effect")
        rationale = row.get("rationale")
        divergences = row.get("divergences")
        if isinstance(gc_family, str) and isinstance(aces_family, str):
            pair = (gc_family, aces_family)
            if pair in seen_pairs:
                violations.append(
                    _ontology_violation("ontology-crosswalk-alignment-duplicate", f"Alignment ({gc_family} -> {aces_family}) is declared more than once.")
                )
            seen_pairs.add(pair)
        if not isinstance(gc_family, str) or gc_family not in gc_family_ids:
            violations.append(
                _ontology_violation("ontology-crosswalk-gc-family-missing", f"Alignment references unknown Ground Control family {gc_family!r}.")
            )
        if not isinstance(aces_family, str) or aces_family not in aces_family_ids:
            violations.append(
                _ontology_violation("ontology-crosswalk-aces-family-missing", f"Alignment references unknown ACES family {aces_family!r}.")
            )
        elif aces_family == ONTOLOGY_CROSSWALK_TIME_FAMILY:
            # ADR-084 §5: time has no GC family and is deliberately omitted from v1;
            # aligning it requires a GC time family and an ADR amendment first.
            violations.append(
                _ontology_violation(
                    "ontology-crosswalk-time-alignment-forbidden",
                    f"Alignment to ACES `{ONTOLOGY_CROSSWALK_TIME_FAMILY}` is forbidden in crosswalk v1; time is omitted until a GC time family exists (ADR-084 §5).",
                )
            )
        if effect not in ONTOLOGY_EFFECT_VOCABULARY:
            violations.append(
                _ontology_violation(
                    "ontology-crosswalk-effect-invalid",
                    f"Alignment effect {effect!r} is not in the closed effect vocabulary.",
                    f"allowed: {sorted(ONTOLOGY_EFFECT_VOCABULARY)}",
                )
            )
        if not isinstance(rationale, str) or not rationale.strip():
            violations.append(
                _ontology_violation("ontology-crosswalk-rationale-missing", f"Alignment ({gc_family} -> {aces_family}) must declare a non-empty rationale.")
            )
        if not isinstance(divergences, list) or any(not isinstance(item, str) or not item.strip() for item in divergences):
            violations.append(
                _ontology_violation("ontology-crosswalk-alignment-invalid", f"Alignment ({gc_family} -> {aces_family}) divergences must be a list of non-empty strings.")
            )
        elif effect == "aligns" and divergences:
            violations.append(
                _ontology_violation(
                    "ontology-crosswalk-effect-divergence-mismatch",
                    f"Alignment ({gc_family} -> {aces_family}) is `aligns` but records divergences; aligns means equivalent meaning with no divergence.",
                )
            )
        elif effect == "refines" and not divergences:
            violations.append(
                _ontology_violation(
                    "ontology-crosswalk-effect-divergence-mismatch",
                    f"Alignment ({gc_family} -> {aces_family}) is `refines` but records no divergences; refines must record the narrowing explicitly.",
                )
            )

    omissions = crosswalk.get("omissions")
    time_omission_present = False
    if not isinstance(omissions, list):
        violations.append(_ontology_violation("ontology-crosswalk-omission-invalid", "Crosswalk omissions must be an array."))
        omissions = []
    for omission in omissions:
        if not isinstance(omission, dict):
            violations.append(_ontology_violation("ontology-crosswalk-omission-invalid", "Every omission must be an object."))
            continue
        topic = omission.get("topic")
        reason = omission.get("reason")
        aces_family = omission.get("aces_family")
        if not isinstance(topic, str) or not topic.strip() or not isinstance(reason, str) or not reason.strip():
            violations.append(_ontology_violation("ontology-crosswalk-omission-invalid", "Every omission must declare a non-empty topic and reason."))
        if not isinstance(aces_family, str) or aces_family not in aces_family_ids:
            violations.append(
                _ontology_violation("ontology-crosswalk-omission-family-missing", f"Omission references unknown ACES family {aces_family!r}.")
            )
        elif aces_family == ONTOLOGY_CROSSWALK_TIME_FAMILY:
            time_omission_present = True

    # ADR-084 §5: crosswalk v1 MUST record the time omission explicitly.
    if not time_omission_present:
        violations.append(
            _ontology_violation(
                "ontology-crosswalk-time-omission-required",
                f"Crosswalk v1 must record an omission for ACES `{ONTOLOGY_CROSSWALK_TIME_FAMILY}` (ADR-084 §5).",
            )
        )

    return violations


def run_contract_surface_check(root: Path = REPO_ROOT) -> list[Violation]:
    violations: list[Violation] = []

    for rel in CONTRACT_REQUIRED_PATHS:
        if not (root / rel).exists():
            violations.append(
                Violation(
                    code="contract-surface-missing",
                    message=f"Required contract artifact is missing: {rel}.",
                    details=["Run `make contracts` and commit the generated contract surface."],
                )
            )

    shim = root / FRONTEND_CONTRACT_SHIM_PATH
    if shim.exists():
        text = shim.read_text(encoding="utf-8")
        if GENERATED_CONTRACT_EXPORT not in text:
            violations.append(
                Violation(
                    code="contract-frontend-shim",
                    message="frontend/src/types/api.ts must re-export the generated contract types.",
                    details=[f"expected line: {GENERATED_CONTRACT_EXPORT}"],
                )
            )
        hand_mirror = re.search(r"^\s*export\s+(interface|type|const)\s+", text, re.MULTILINE)
        if hand_mirror:
            violations.append(
                Violation(
                    code="contract-frontend-hand-mirror",
                    message="frontend/src/types/api.ts must not contain hand-mirrored DTOs or enum constants.",
                    details=["Keep compatibility aliases in contracts/gen/typescript/api.ts via the generator inventory."],
                )
            )

    return violations


STATION_CATALOGUE_PATH = "contracts/measurement/gc-station-catalogue-v2.json"


MEASUREMENT_RECORD_SCHEMA_PATH = "contracts/schemas/measurement/measurement-record.v1.schema.json"


IMPLEMENT_MECHANICAL_PATH = "mcp/ground-control/gc-implement-mechanical.js"


# Every source that names a station id at an emission site. Scanning only the
# mechanical module left the review stations — emitted from lib.js — invisible to this
# gate, which is the hole ADR-090 section 8 exists to close: a live emitter the gate
# does not name is not covered by it.
EMITTER_SOURCE_PATHS = (
    IMPLEMENT_MECHANICAL_PATH,
    "mcp/ground-control/gate-finding-adapters.js",
)

# The mechanical action modules, split out of gc-implement-mechanical.js under the 500-LOC limit
# (issue #1355). The station tables live here now, so scanning only the entry module would
# resolve nothing and let the drift gate pass vacuously.
IMPLEMENT_MODULE_DIR = "mcp/ground-control/implement"


# `bootstrap: "issue_branch_resolution",` inside STATION_BY_ACTION.
_STATION_BY_ACTION_ENTRY_RE = re.compile(r"^\s*[\"a-z_-]+\s*:\s*\"([a-z0-9_]+)\"\s*,?\s*$", re.MULTILINE)


# A station id passed literally, e.g. `emitter.station("ci", ...)`.
_STATION_CALL_RE = re.compile(r"\.station\(\s*\"([a-z_]+)\"")


# A station id named as a field, e.g. `recordStationAttempt({ stationId: "spotbugs" })`.
_STATION_ID_FIELD_RE = re.compile(r"stationId:\s*\"([a-z0-9_]+)\"")


# Any table mapping something to a station or marker id. An emitter that names its stations
# through a lookup table is naming them just as much as one that inlines the literal, so the
# gate reads both — otherwise it is guarded only against the style it happens to expect.
_STATION_TABLE_RE = re.compile(
    r"(?:STATION_BY_[A-Z_]+|MARKER_BY_[A-Z_]+|[A-Z_]*STATION[A-Z_]*)\s*=\s*Object\.freeze\(\{(.*?)\}\)",
    re.DOTALL,
)


# `<!-- gc:phase phase="ready_for_review" ...` written by the MCP layer.
_PHASE_MARKER_LITERAL_RE = re.compile(r"gc:phase\s+phase=\\?\"([a-z_]+)\\?\"|phase:\s*\"([a-z_]+)\"")
