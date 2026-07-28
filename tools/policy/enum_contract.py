"""Policy checks: enum contract.

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
from .catalog_drift import (
    _contributor_edge_values,
    _contributor_type_identity,
    _is_ontology_enum,
    _java_type_identity,
    _validate_ontology_families,
    _validate_ontology_terms,
)
from .core import (
    REPO_ROOT,
    Violation,
)
from .env_templates import (
    ONTOLOGY_CONTRACT_PATHS,
    ONTOLOGY_SOURCE_ROOT,
    ONTOLOGY_SURFACE_KINDS,
    _load_ontology_contracts,
    _ontology_violation,
    _strip_comments,
    parse_java_enum_constants,
)


def _discover_ontology_surfaces(
    root: Path,
) -> tuple[dict[str, tuple[str, str, set[str]]], dict[str, set[str]], list[Violation]]:
    java_root = root / ONTOLOGY_SOURCE_ROOT
    discovered: dict[str, tuple[str, str, set[str]]] = {}
    dynamic_enum_selectors: dict[str, set[str]] = {}
    violations: list[Violation] = []
    if not java_root.is_dir():
        violations.append(
            _ontology_violation(
                "ontology-source-root-missing",
                f"Ontology Java source root is missing: {ONTOLOGY_SOURCE_ROOT.as_posix()}.",
            )
        )
        return discovered, dynamic_enum_selectors, violations
    for path in sorted(java_root.rglob("*.java")):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            violations.append(
                _ontology_violation("ontology-source-unreadable", f"Cannot read ontology source {path}.", str(exc))
            )
            continue
        without_comments = _strip_comments(text)
        contributor_identity = _contributor_type_identity(text)
        named_contributor_candidate = (
            path.name.endswith("GraphProjectionContributor.java")
            and path.name != "GraphProjectionContributor.java"
        )
        if named_contributor_candidate and contributor_identity is None:
            violations.append(
                _ontology_violation(
                    "ontology-contributor-declaration-unresolved",
                    "Graph contributor candidate does not directly declare GraphProjectionContributor.",
                    f"file: {path.relative_to(root).as_posix()}",
                )
            )
            continue
        identity = contributor_identity or _java_type_identity(text)
        if identity is None:
            continue
        surface_id, type_name = identity
        rel = path.relative_to(root).as_posix()
        if contributor_identity is not None:
            values, enum_selectors, unresolved = _contributor_edge_values(text)
            discovered[surface_id] = ("graph-contributor", rel, values)
            dynamic_enum_selectors[surface_id] = enum_selectors
            for expression in unresolved:
                violations.append(
                    _ontology_violation(
                        "ontology-contributor-edge-unresolved",
                        f"Contributor {surface_id} has an edge expression the ontology inventory cannot resolve.",
                        f"file: {rel}",
                        f"expression: {expression}",
                    )
                )
            continue
        enum_match = re.search(r"\benum\s+(\w+)", _strip_comments(text))
        if enum_match and _is_ontology_enum(enum_match.group(1)):
            values = set(parse_java_enum_constants(text))
            if not values:
                violations.append(
                    _ontology_violation(
                        "ontology-source-parse-error",
                        f"Could not parse ontology enum values from {rel}.",
                    )
                )
            discovered[surface_id] = ("java-enum", rel, values)
    return discovered, dynamic_enum_selectors, violations


def _safe_ontology_source_path(root: Path, raw_path: Any) -> bool:
    if not isinstance(raw_path, str) or not raw_path:
        return False
    rel = Path(raw_path)
    if rel.is_absolute() or ".." in rel.parts or rel.as_posix() != raw_path:
        return False
    if not rel.is_relative_to(ONTOLOGY_SOURCE_ROOT):
        return False
    try:
        (root / rel).resolve().relative_to(root.resolve())
    except (OSError, ValueError):
        return False
    return True


def run_ontology_binding_check(root: Path = REPO_ROOT) -> list[Violation]:
    """Validate ontology artifacts and their bidirectional Java-source bindings."""

    payloads, violations = _load_ontology_contracts(root)
    if set(payloads) != set(ONTOLOGY_CONTRACT_PATHS):
        return violations

    family_ids, family_violations = _validate_ontology_families(payloads["families"])
    terms, term_violations = _validate_ontology_terms(payloads["terms"], family_ids)
    violations.extend(family_violations)
    violations.extend(term_violations)

    discovered, dynamic_enum_selectors, discovery_violations = _discover_ontology_surfaces(root)
    violations.extend(discovery_violations)

    surfaces = payloads["bindings"].get("surfaces")
    if not isinstance(surfaces, list):
        violations.append(
            _ontology_violation(
                "ontology-contract-shape-invalid",
                "gc-artifact-bindings-v1.json must declare a surfaces array.",
            )
        )
        return violations

    declared_surfaces: set[str] = set()
    declared_keys: set[tuple[str, str]] = set()
    for surface in surfaces:
        if not isinstance(surface, dict):
            violations.append(_ontology_violation("ontology-surface-invalid", "Every ontology surface must be an object."))
            continue
        surface_id = surface.get("id")
        kind = surface.get("kind")
        raw_path = surface.get("path")
        if not isinstance(surface_id, str) or not surface_id:
            violations.append(_ontology_violation("ontology-surface-invalid", "Every ontology surface must have an id."))
            continue
        if surface_id in declared_surfaces:
            violations.append(
                _ontology_violation("ontology-surface-duplicate", f"Ontology surface {surface_id} is declared more than once.")
            )
        declared_surfaces.add(surface_id)
        if kind not in ONTOLOGY_SURFACE_KINDS:
            violations.append(
                _ontology_violation(
                    "ontology-surface-kind-invalid",
                    f"Ontology surface {surface_id} has unknown kind {kind!r}.",
                )
            )
        if not _safe_ontology_source_path(root, raw_path):
            violations.append(
                _ontology_violation(
                    "ontology-surface-path-invalid",
                    f"Ontology surface {surface_id} has an unsafe or non-source path {raw_path!r}.",
                )
            )
        actual = discovered.get(surface_id)
        if actual is not None:
            actual_kind, actual_path, _ = actual
            if kind != actual_kind:
                violations.append(
                    _ontology_violation(
                        "ontology-surface-kind-mismatch",
                        f"Ontology surface {surface_id} is {actual_kind}, not {kind!r}.",
                    )
                )
            if raw_path != actual_path:
                violations.append(
                    _ontology_violation(
                        "ontology-surface-path-mismatch",
                        f"Ontology surface {surface_id} path does not match discovered source.",
                        f"declared: {raw_path}",
                        f"discovered: {actual_path}",
                    )
                )
        configured_enum_sources = surface.get("edge_enum_sources", {})
        if not isinstance(configured_enum_sources, dict):
            violations.append(
                _ontology_violation(
                    "ontology-edge-enum-source-invalid",
                    f"Ontology surface {surface_id} must declare edge_enum_sources as an object.",
                )
            )
            configured_enum_sources = {}
        expected_selectors = dynamic_enum_selectors.get(surface_id, set())
        configured_selectors = set(configured_enum_sources)
        for selector in sorted(expected_selectors - configured_selectors):
            violations.append(
                _ontology_violation(
                    "ontology-edge-enum-source-missing",
                    f"Dynamic edge selector {surface_id}:{selector} has no declared enum source.",
                )
            )
        for selector in sorted(configured_selectors - expected_selectors):
            violations.append(
                _ontology_violation(
                    "ontology-edge-enum-source-stale",
                    f"Declared edge enum selector {surface_id}:{selector} no longer appears in source.",
                )
            )
        for selector, enum_surface_id in configured_enum_sources.items():
            enum_surface = discovered.get(enum_surface_id) if isinstance(enum_surface_id, str) else None
            if enum_surface is None or enum_surface[0] != "java-enum":
                violations.append(
                    _ontology_violation(
                        "ontology-edge-enum-source-missing",
                        f"Dynamic edge selector {surface_id}:{selector} does not resolve to a discovered enum surface.",
                        f"declared source: {enum_surface_id!r}",
                    )
                )
                continue
            enum_name = enum_surface_id.rsplit(".", 1)[-1]
            selector_matches = (
                (selector == "getLinkType" and enum_name.endswith("LinkType") and not enum_name.endswith("LinkTargetType"))
                or (selector == "getRelationType" and enum_name.endswith("RelationType"))
                or (selector == "getRelation" and enum_name == "ProvenanceEdgeRelation")
            )
            if not selector_matches:
                violations.append(
                    _ontology_violation(
                        "ontology-edge-enum-source-invalid",
                        f"Dynamic edge selector {surface_id}:{selector} is incompatible with {enum_surface_id}.",
                    )
                )
        bindings = surface.get("bindings")
        if not isinstance(bindings, list):
            violations.append(
                _ontology_violation("ontology-surface-invalid", f"Ontology surface {surface_id} must declare bindings.")
            )
            continue
        for binding in bindings:
            if not isinstance(binding, dict):
                violations.append(
                    _ontology_violation("ontology-binding-invalid", f"Surface {surface_id} has a non-object binding.")
                )
                continue
            local_value = binding.get("local_value")
            term_id = binding.get("term")
            if not isinstance(local_value, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]*", local_value):
                violations.append(
                    _ontology_violation(
                        "ontology-binding-invalid",
                        f"Surface {surface_id} has invalid local_value {local_value!r}.",
                    )
                )
                continue
            key = (surface_id, local_value)
            if key in declared_keys:
                violations.append(
                    _ontology_violation(
                        "ontology-binding-duplicate",
                        f"Ontology binding {surface_id}:{local_value} is declared more than once.",
                    )
                )
            declared_keys.add(key)
            term = terms.get(term_id) if isinstance(term_id, str) else None
            if term is None:
                violations.append(
                    _ontology_violation(
                        "ontology-term-reference-missing",
                        f"Binding {surface_id}:{local_value} references unknown term {term_id!r}.",
                    )
                )
            elif actual is not None:
                expected_term_kind = "classification" if surface_id.endswith(".GraphEntityType") else "edge"
                if term.get("kind") != expected_term_kind:
                    violations.append(
                        _ontology_violation(
                            "ontology-binding-kind-mismatch",
                            f"Binding {surface_id}:{local_value} must target a {expected_term_kind} term.",
                            f"term {term_id} is {term.get('kind')!r}",
                        )
                    )

    discovered_surfaces = set(discovered)
    for surface_id in sorted(discovered_surfaces - declared_surfaces):
        kind, path, _ = discovered[surface_id]
        violations.append(
            _ontology_violation(
                "ontology-surface-missing",
                f"Discovered {kind} surface {surface_id} has no ontology surface declaration.",
                f"file: {path}",
            )
        )
    for surface_id in sorted(declared_surfaces - discovered_surfaces):
        violations.append(
            _ontology_violation(
                "ontology-surface-stale",
                f"Ontology surface {surface_id} no longer exists in source inventory.",
            )
        )

    discovered_keys = {
        (surface_id, local_value)
        for surface_id, (_, _, values) in discovered.items()
        for local_value in values
    }
    for surface_id, local_value in sorted(discovered_keys - declared_keys):
        violations.append(
            _ontology_violation(
                "ontology-binding-missing",
                f"Source vocabulary is unbound: {surface_id}:{local_value}.",
            )
        )
    for surface_id, local_value in sorted(declared_keys - discovered_keys):
        violations.append(
            _ontology_violation(
                "ontology-binding-stale",
                f"Ontology binding points to a vanished source value: {surface_id}:{local_value}.",
            )
        )
    return violations


ONTOLOGY_CROSSWALK_PATH = Path("contracts/ontology/crosswalks/aces-concept-families-v1.json")


ONTOLOGY_CROSSWALK_SCHEMA_VERSION = "aces-concept-families-crosswalk/v1"


ONTOLOGY_EXTERNAL_SNAPSHOT_ROOT = Path("contracts/ontology/external")


ONTOLOGY_EFFECT_VOCABULARY = frozenset({"annotates", "aligns", "refines", "constrains"})


# ADR-084 §4/§5: the crosswalk must bind to the canonical GC family catalog, and
# crosswalk v1 deliberately omits the ACES `time-and-apparatus` family (GC has no
# time family; time is the Envers spine). Both are contract invariants, not data.
ONTOLOGY_CROSSWALK_TIME_FAMILY = "time-and-apparatus"


def _safe_ontology_external_path(root: Path, raw_path: Any) -> bool:
    if not isinstance(raw_path, str) or not raw_path:
        return False
    rel = Path(raw_path)
    if rel.is_absolute() or ".." in rel.parts or rel.as_posix() != raw_path:
        return False
    if not rel.is_relative_to(ONTOLOGY_EXTERNAL_SNAPSHOT_ROOT):
        return False
    try:
        (root / rel).resolve().relative_to(root.resolve())
    except (OSError, ValueError):
        return False
    return True


def _load_ontology_family_ids(path: Path) -> tuple[set[str], Any, str | None]:
    """Return (family_ids, schema_version, error) for a concept-families catalog,
    reading only the family keys and version. Full family-shape validation lives
    in the binding check."""
    try:
        payload = load_json(path, reject_duplicate_keys=True)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        return set(), None, str(exc)
    if not isinstance(payload, dict):
        return set(), None, "catalog is not a JSON object"
    families = payload.get("families")
    if not isinstance(families, dict) or not families:
        return set(), payload.get("schema_version"), "catalog declares no families object"
    return {fid for fid in families if isinstance(fid, str) and fid}, payload.get("schema_version"), None
