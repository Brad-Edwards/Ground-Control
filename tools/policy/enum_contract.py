"""Policy checks: enum contract.

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
from .adr_guard import (
    load_json,
)
from .core import (
    REPO_ROOT,
    Violation,
)
from .deploy_artifacts import (
    FRONTEND_API_TYPES_PATH,
    MCP_LIB_PATH,
    _AUDIT_ENUM_STATE_DIR,
    _BLOCK_COMMENT_RE,
    _ENUM_CONSTANT_RE,
    _ENUM_STATE_DIR,
    _GRAPH_MODEL_DIR,
    _JAVA_ENUM_BODY_RE,
    _LINE_COMMENT_RE,
    _PAREN_GROUP_RE,
    _STRING_LITERAL_RE,
    _VERIFICATION_ENUM_STATE_DIR,
    read_mcp_library,
)


def _strip_comments(text: str) -> str:
    """Remove ``//`` line comments and ``/* ... */`` block comments.

    Java, TypeScript, and JavaScript all use this comment syntax. Block comments
    are replaced with a space (so adjacent tokens are not glued); line comments
    are removed up to the newline. Used so a value that exists only inside a
    comment — or a value that was commented *out* — is not counted as an active
    enum member by the regex extractors below.

    Line comments are stripped first so that ``/**`` sequences embedded inside a
    ``//`` comment (e.g. ``// valid for all '/api/v1/**' paths``) are consumed by
    the line-comment pass and cannot be mistaken for block-comment openers by the
    subsequent block-comment pass.
    """
    text = _LINE_COMMENT_RE.sub("", text)
    return _BLOCK_COMMENT_RE.sub(" ", text)


@dataclass(frozen=True)
class EnumContract:
    label: str
    java_path: str
    ts_union: str
    # The api.ts iterated constant array (``export const FOO: T[] = [...]``), or
    # None for enums the UI does not iterate (only the union type is mirrored).
    ts_const: str | None
    # The lib.js constant array, or None for enums with no MCP-side mirror.
    mcp_const: str | None


ENUM_CONTRACT_INVENTORY: tuple[EnumContract, ...] = (
    EnumContract(
        "GraphEntityType",
        f"{_GRAPH_MODEL_DIR}/GraphEntityType.java",
        "GraphEntityType",
        "GRAPH_ENTITY_TYPES",
        None,
    ),
    EnumContract("RequirementType", f"{_ENUM_STATE_DIR}/RequirementType.java", "RequirementType", "REQUIREMENT_TYPES", "REQUIREMENT_TYPES"),
    EnumContract("RelationType", f"{_ENUM_STATE_DIR}/RelationType.java", "RelationType", "RELATION_TYPES", "RELATION_TYPES"),
    EnumContract("ArtifactType", f"{_ENUM_STATE_DIR}/ArtifactType.java", "ArtifactType", "ARTIFACT_TYPES", "ARTIFACT_TYPES"),
    EnumContract("LinkType", f"{_ENUM_STATE_DIR}/LinkType.java", "LinkType", "LINK_TYPES", "LINK_TYPES"),
    EnumContract("Status", f"{_ENUM_STATE_DIR}/Status.java", "Status", "STATUSES", "STATUSES"),
    EnumContract("Priority", f"{_ENUM_STATE_DIR}/Priority.java", "Priority", "PRIORITIES", "PRIORITIES"),
    # SyncStatus has no MCP mirror today; only the api.ts union type carries it.
    EnumContract("SyncStatus", f"{_ENUM_STATE_DIR}/SyncStatus.java", "SyncStatus", None, None),
    EnumContract("ChangeCategory", f"{_ENUM_STATE_DIR}/ChangeCategory.java", "ChangeCategory", "CHANGE_CATEGORIES", "CHANGE_CATEGORIES"),
    # GC-U001 / ADR-047 audit entity enums. AuditType and AuditStatus are iterated
    # by UI and exposed by the MCP gc_audit tool.
    EnumContract("AuditType", f"{_AUDIT_ENUM_STATE_DIR}/AuditType.java", "AuditType", "AUDIT_TYPES", "AUDIT_TYPES"),
    EnumContract("AuditStatus", f"{_AUDIT_ENUM_STATE_DIR}/AuditStatus.java", "AuditStatus", "AUDIT_STATUSES", "AUDIT_STATUSES"),
    # Verification and Assurance enums. VerificationStatus and AssuranceLevel
    # are domain/verification/state enums used in evidence/control verification
    # workflows; both are mirrored at the frontend TypeScript boundary and MCP
    # surfaces. ADR-034. (The domain/riskscenarios/state NIST/crosswalk/
    # methodology enums this comment used to describe — ThreatEventKind,
    # ThreatSourceRelevance, NistLikelihoodBand, NistImpactBand,
    # NormalizedConcept, CrosswalkVocabularySurface, MethodologyFamily — were
    # retired with the composed GRC product surface; ADR-089, issue #1346.)
    EnumContract(
        "VerificationStatus",
        f"{_VERIFICATION_ENUM_STATE_DIR}/VerificationStatus.java",
        "VerificationStatus",
        "VERIFICATION_STATUSES",
        "VERIFICATION_STATUSES",
    ),
    EnumContract(
        "AssuranceLevel",
        f"{_VERIFICATION_ENUM_STATE_DIR}/AssuranceLevel.java",
        "AssuranceLevel",
        "ASSURANCE_LEVELS",
        "ASSURANCE_LEVELS",
    ),
)


def parse_java_enum_constants(text: str) -> list[str]:
    """Return the ordered enum constants of the (single) ``enum X { ... }`` in ``text``.

    Comments are stripped first. The constant list is the body between the
    opening ``{`` and the first ``;`` (for enums with methods/fields) or closing
    ``}`` (constant-only enums); constructor-argument groups (``FOO("x")``) are
    stripped, then the body is split on commas/whitespace and the
    ``[A-Z][A-Z0-9_]*`` tokens are returned in declaration order. Returns ``[]``
    when no enum declaration is found (the caller treats that as a parse error).
    """
    without_comments = _strip_comments(text)
    match = _JAVA_ENUM_BODY_RE.search(without_comments)
    if not match:
        return []
    body = _PAREN_GROUP_RE.sub(" ", match.group(1))
    tokens: list[str] = []
    for raw in re.split(r"[,\s]+", body):
        token = raw.strip()
        if token and _ENUM_CONSTANT_RE.match(token):
            tokens.append(token)
    return tokens


def parse_const_string_array(text: str, name: str) -> list[str] | None:
    """Return the ordered string literals of ``const <name> [: T[]] = [ ... ]``.

    Works for both TypeScript (``export const FOO: FooType[] = [...]``) and
    plain JS (``export const FOO = [...]``). Comments are stripped first, so a
    commented-out element is not counted. Returns ``None`` when no such
    declaration exists, or the (possibly empty) ordered list of string-literal
    values when it does. The name is matched whole-word so ``LINK_TYPES`` does
    not match ``ASSET_LINK_TYPES`` and ``ARTIFACT_TYPES`` does not match
    ``ARTIFACT_TYPES_FOO``.
    """
    pattern = re.compile(
        r"\bconst\s+" + re.escape(name) + r"\b\s*(?::[^=]*)?=\s*\[(.*?)\]",
        re.DOTALL,
    )
    match = pattern.search(_strip_comments(text))
    if not match:
        return None
    return _STRING_LITERAL_RE.findall(match.group(1))


def parse_ts_union_literals(text: str, name: str) -> set[str] | None:
    """Return the set of string-literal members of ``type <name> = "A" | "B" | ...;``.

    Comments are stripped first. Returns ``None`` when no such type alias exists.
    Union member order is not significant, so a set is returned.
    """
    pattern = re.compile(r"\btype\s+" + re.escape(name) + r"\b\s*=([^;]*);", re.DOTALL)
    match = pattern.search(_strip_comments(text))
    if not match:
        return None
    return set(_STRING_LITERAL_RE.findall(match.group(1)))


def run_enum_contract_check(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert backend Java enums == frontend api.ts == MCP lib.js for the enum inventory.

    A static post-condition (independent of ``changed_files``) so any diff that
    lets the layers diverge fails ``make policy``. Emits:
      ``enum-contract-source-missing`` — a required source file is absent.
      ``enum-contract-parse-error``    — a file exists but the enum/const/union
                                         could not be parsed out of it.
      ``enum-contract-drift``          — the values do not match (the message
                                         names the enum, the layer, and the
                                         symmetric difference).
    """
    violations: list[Violation] = []

    api_ts_path = root / FRONTEND_API_TYPES_PATH
    mcp_path = root / MCP_LIB_PATH
    api_ts_text: str | None = None
    mcp_text: str | None = None
    if api_ts_path.exists():
        api_ts_text = api_ts_path.read_text(encoding="utf-8")
    else:
        violations.append(
            Violation(
                code="enum-contract-source-missing",
                message="Frontend API types file is missing — enum contract cannot be verified.",
                details=[f"expected at {FRONTEND_API_TYPES_PATH}"],
            )
        )
    mcp_text = read_mcp_library(root)
    if mcp_text is None:
        violations.append(
            Violation(
                code="enum-contract-source-missing",
                message="MCP library file is missing — enum contract cannot be verified.",
                details=[f"expected at {MCP_LIB_PATH}"],
            )
        )

    for contract in ENUM_CONTRACT_INVENTORY:
        java_path = root / contract.java_path
        if not java_path.exists():
            violations.append(
                Violation(
                    code="enum-contract-source-missing",
                    message=f"Backend enum source for {contract.label} is missing.",
                    details=[f"expected at {contract.java_path}"],
                )
            )
            continue
        java_consts = parse_java_enum_constants(java_path.read_text(encoding="utf-8"))
        if not java_consts:
            violations.append(
                Violation(
                    code="enum-contract-parse-error",
                    message=f"Could not parse the {contract.label} enum constants from the Java source.",
                    details=[f"file: {contract.java_path}"],
                )
            )
            continue
        java_set = set(java_consts)

        if api_ts_text is not None:
            if contract.ts_const is not None:
                ts_const = parse_const_string_array(api_ts_text, contract.ts_const)
                if ts_const is None:
                    violations.append(
                        Violation(
                            code="enum-contract-parse-error",
                            message=f"Frontend constant {contract.ts_const} not found in {FRONTEND_API_TYPES_PATH}.",
                            details=[f"backend {contract.label} = {java_consts}"],
                        )
                    )
                elif ts_const != java_consts:
                    violations.append(_drift_violation(contract.label, f"frontend {contract.ts_const} (api.ts)", java_consts, ts_const))

            ts_union = parse_ts_union_literals(api_ts_text, contract.ts_union)
            if ts_union is None:
                violations.append(
                    Violation(
                        code="enum-contract-parse-error",
                        message=f"Frontend union type {contract.ts_union} not found in {FRONTEND_API_TYPES_PATH}.",
                        details=[f"backend {contract.label} = {java_consts}"],
                    )
                )
            elif ts_union != java_set:
                violations.append(_drift_violation(contract.label, f"frontend type {contract.ts_union} (api.ts)", sorted(java_set), sorted(ts_union)))

        if mcp_text is not None and contract.mcp_const is not None:
            mcp_const = parse_const_string_array(mcp_text, contract.mcp_const)
            if mcp_const is None:
                violations.append(
                    Violation(
                        code="enum-contract-parse-error",
                        message=f"MCP constant {contract.mcp_const} not found in {MCP_LIB_PATH}.",
                        details=[f"backend {contract.label} = {java_consts}"],
                    )
                )
            elif mcp_const != java_consts:
                violations.append(_drift_violation(contract.label, f"MCP {contract.mcp_const} (lib.js)", java_consts, mcp_const))

    return violations


def _drift_violation(label: str, layer: str, expected: list[str], actual: list[str]) -> Violation:
    expected_set, actual_set = set(expected), set(actual)
    missing = sorted(expected_set - actual_set)
    extra = sorted(actual_set - expected_set)
    details = [
        f"backend {label} (source of truth): {expected}",
        f"{layer}: {actual}",
    ]
    if missing:
        details.append(f"missing from {layer}: {missing}")
    if extra:
        details.append(f"not in backend {label}: {extra}")
    if not missing and not extra:
        details.append(f"order differs from backend {label} declaration order")
    return Violation(
        code="enum-contract-drift",
        message=f"{label} enum drift between backend and {layer} (issue #433 / ADR-034).",
        details=details,
    )


ONTOLOGY_CONTRACT_PATHS = {
    "families": Path("contracts/ontology/gc-concept-families-v1.json"),
    "terms": Path("contracts/ontology/gc-controlled-vocabularies-v1.json"),
    "bindings": Path("contracts/ontology/gc-artifact-bindings-v1.json"),
}


ONTOLOGY_SCHEMA_VERSIONS = {
    "families": "gc-concept-families/v1",
    "terms": "gc-controlled-vocabularies/v1",
    "bindings": "gc-artifact-bindings/v1",
}


ONTOLOGY_PROVENANCE = frozenset({"adopted", "adapted", "native"})


ONTOLOGY_OWNERS = frozenset({"ground-control"})


ONTOLOGY_TERM_KINDS = frozenset({"edge", "classification"})


ONTOLOGY_SURFACE_KINDS = frozenset({"java-enum", "graph-contributor"})


ONTOLOGY_SOURCE_ROOT = Path("backend/src/main/java")


def _ontology_violation(code: str, message: str, *details: str) -> Violation:
    return Violation(code=code, message=message, details=list(details))


def _load_ontology_contracts(root: Path) -> tuple[dict[str, dict[str, Any]], list[Violation]]:
    payloads: dict[str, dict[str, Any]] = {}
    violations: list[Violation] = []
    for label, rel in ONTOLOGY_CONTRACT_PATHS.items():
        path = root / rel
        if not path.is_file():
            violations.append(
                _ontology_violation(
                    "ontology-contract-missing",
                    f"Required ontology contract is missing: {rel.as_posix()}.",
                )
            )
            continue
        try:
            payload = load_json(path, reject_duplicate_keys=True)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
            violations.append(
                _ontology_violation(
                    "ontology-contract-invalid-json",
                    f"Ontology contract is not readable JSON: {rel.as_posix()}.",
                    str(exc),
                )
            )
            continue
        if not isinstance(payload, dict):
            violations.append(
                _ontology_violation(
                    "ontology-contract-shape-invalid",
                    f"Ontology contract must contain a JSON object: {rel.as_posix()}.",
                )
            )
            continue
        payloads[label] = payload
        actual_version = payload.get("schema_version")
        if actual_version != ONTOLOGY_SCHEMA_VERSIONS[label]:
            violations.append(
                _ontology_violation(
                    "ontology-contract-version-invalid",
                    f"Ontology contract has an unsupported schema version: {rel.as_posix()}.",
                    f"expected {ONTOLOGY_SCHEMA_VERSIONS[label]}, got {actual_version!r}",
                )
            )
    return payloads, violations


def _nonempty_string_list(value: Any) -> bool:
    return isinstance(value, list) and bool(value) and all(isinstance(item, str) and item.strip() for item in value)
