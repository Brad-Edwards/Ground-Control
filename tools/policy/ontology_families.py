"""Policy checks: ontology family and term validation.

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
    Violation,
)
from .enum_contract import (
    ONTOLOGY_OWNERS,
    ONTOLOGY_PROVENANCE,
    ONTOLOGY_TERM_KINDS,
    _nonempty_string_list,
    _ontology_violation,
    _strip_comments,
)


def _validate_ontology_families(payload: dict[str, Any]) -> tuple[set[str], list[Violation]]:
    violations: list[Violation] = []
    owners = payload.get("owners")
    if not _nonempty_string_list(owners) or not set(owners).issubset(ONTOLOGY_OWNERS):
        violations.append(
            _ontology_violation(
                "ontology-owner-invalid",
                "Ontology family owners must use the closed owner vocabulary.",
                f"allowed: {sorted(ONTOLOGY_OWNERS)}",
            )
        )
    families = payload.get("families")
    if not isinstance(families, dict) or not families:
        return set(), violations + [
            _ontology_violation(
                "ontology-contract-shape-invalid",
                "gc-concept-families-v1.json must declare a non-empty families object.",
            )
        ]
    family_ids: set[str] = set()
    for family_id, family in families.items():
        if not isinstance(family_id, str) or not family_id or not isinstance(family, dict):
            violations.append(
                _ontology_violation("ontology-family-invalid", "Every ontology family must be a named object.")
            )
            continue
        family_ids.add(family_id)
        provenance = family.get("provenance")
        if provenance not in ONTOLOGY_PROVENANCE:
            violations.append(
                _ontology_violation(
                    "ontology-provenance-invalid",
                    f"Family {family_id} has invalid provenance {provenance!r}.",
                    f"allowed: {sorted(ONTOLOGY_PROVENANCE)}",
                )
            )
        if family.get("owner") not in ONTOLOGY_OWNERS:
            violations.append(
                _ontology_violation("ontology-owner-invalid", f"Family {family_id} has an unknown owner.")
            )
        for field in ("title", "description"):
            if not isinstance(family.get(field), str) or not family[field].strip():
                violations.append(
                    _ontology_violation(
                        "ontology-family-invalid",
                        f"Family {family_id} must declare non-empty {field}.",
                    )
                )
        if provenance == "native":
            if not isinstance(family.get("extension_scope"), str) or not family["extension_scope"].strip():
                violations.append(
                    _ontology_violation(
                        "ontology-native-family-rules-missing",
                        f"Native family {family_id} must declare extension_scope.",
                    )
                )
            for field in ("relation_rules", "non_ambiguity_constraints"):
                if not _nonempty_string_list(family.get(field)):
                    violations.append(
                        _ontology_violation(
                            "ontology-native-family-rules-missing",
                            f"Native family {family_id} must declare non-empty {field}.",
                        )
                    )
    return family_ids, violations


def _validate_ontology_terms(
    payload: dict[str, Any], family_ids: set[str]
) -> tuple[dict[str, dict[str, Any]], list[Violation]]:
    violations: list[Violation] = []
    terms = payload.get("terms")
    if not isinstance(terms, dict) or not terms:
        return {}, [
            _ontology_violation(
                "ontology-contract-shape-invalid",
                "gc-controlled-vocabularies-v1.json must declare a non-empty terms object.",
            )
        ]
    valid_terms: dict[str, dict[str, Any]] = {}
    for term_id, term in terms.items():
        if not isinstance(term_id, str) or not term_id or not isinstance(term, dict):
            violations.append(_ontology_violation("ontology-term-invalid", "Every ontology term must be a named object."))
            continue
        valid_terms[term_id] = term
        kind = term.get("kind")
        if kind not in ONTOLOGY_TERM_KINDS:
            violations.append(
                _ontology_violation(
                    "ontology-term-kind-invalid",
                    f"Term {term_id} has invalid kind {kind!r}.",
                    f"allowed: {sorted(ONTOLOGY_TERM_KINDS)}",
                )
            )
        if term.get("family") not in family_ids:
            violations.append(
                _ontology_violation(
                    "ontology-family-reference-missing",
                    f"Term {term_id} references unknown family {term.get('family')!r}.",
                )
            )
        if term.get("owner") not in ONTOLOGY_OWNERS:
            violations.append(_ontology_violation("ontology-owner-invalid", f"Term {term_id} has an unknown owner."))
        for field in ("title", "description"):
            if not isinstance(term.get(field), str) or not term[field].strip():
                violations.append(
                    _ontology_violation("ontology-term-invalid", f"Term {term_id} must declare non-empty {field}.")
                )
        if kind == "edge":
            if term.get("direction") not in {"source-to-target", "symmetric"}:
                violations.append(
                    _ontology_violation(
                        "ontology-edge-semantics-invalid",
                        f"Edge term {term_id} must declare a closed direction.",
                    )
                )
            if not _nonempty_string_list(term.get("source_roles")) or not _nonempty_string_list(
                term.get("target_roles")
            ):
                violations.append(
                    _ontology_violation(
                        "ontology-edge-semantics-invalid",
                        f"Edge term {term_id} must declare source_roles and target_roles.",
                    )
                )
    return valid_terms, violations


def _java_type_identity(text: str) -> tuple[str, str] | None:
    without_comments = _strip_comments(text)
    type_match = re.search(r"\b(?:class|enum)\s+(\w+)", without_comments)
    if not type_match:
        return None
    package_match = re.search(r"\bpackage\s+([\w.]+)\s*;", without_comments)
    name = type_match.group(1)
    package = package_match.group(1) if package_match else ""
    return (f"{package}.{name}" if package else name, name)


def _is_ontology_enum(name: str) -> bool:
    return (
        name == "GraphEntityType"
        or (name.endswith("LinkType") and not name.endswith("LinkTargetType"))
        or name.endswith("RelationType")
        or name == "ProvenanceEdgeRelation"
    )


def _split_java_arguments(body: str) -> list[str]:
    args: list[str] = []
    start = 0
    depth = 0
    in_string = False
    escaped = False
    for index, char in enumerate(body):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            args.append(body[start:index].strip())
            start = index + 1
    args.append(body[start:].strip())
    return args


def _java_call_argument_lists(text: str, marker: str) -> list[tuple[int, list[str]]]:
    calls: list[tuple[int, list[str]]] = []
    cursor = 0
    while True:
        start = text.find(marker, cursor)
        if start < 0:
            return calls
        body_start = start + len(marker)
        depth = 1
        in_string = False
        escaped = False
        index = body_start
        while index < len(text) and depth:
            char = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            elif char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            index += 1
        if depth:
            calls.append((start, []))
            return calls
        calls.append((start, _split_java_arguments(text[body_start : index - 1])))
        cursor = index


def _graph_edge_argument_lists(text: str) -> list[list[str]]:
    calls: list[list[str]] = []
    constructor_pattern = re.compile(r"\bnew\s+(?:[\w.]+\.)?GraphEdge\s*\(")
    for constructor in constructor_pattern.finditer(text):
        body_start = constructor.end()
        depth = 1
        in_string = False
        escaped = False
        index = body_start
        while index < len(text) and depth:
            char = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            elif char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            index += 1
        if depth:
            calls.append([])
        else:
            calls.append(_split_java_arguments(text[body_start : index - 1]))
    return calls


def _edge_enum_selector(expression: str) -> str | None:
    match = re.search(
        r"\.(getLinkType|getRelationType|getRelation)\(\)\.name\(\)\s*$",
        expression,
    )
    return match.group(1) if match else None


def _is_enum_forwarded_parameter(text: str, parameter_name: str) -> set[str]:
    method_pattern = re.compile(
        r"^[ \t]*(?:(?:public|protected|private)\s+)?(?:static\s+)?[\w<>,?.\[\]]+\s+(\w+)\s*\((.*?)\)\s*(?:throws\s+[^\{]+)?\{",
        re.DOTALL | re.MULTILINE,
    )
    for declaration in method_pattern.finditer(text):
        parameters = _split_java_arguments(declaration.group(2))
        parameter_index = next(
            (
                index
                for index, parameter in enumerate(parameters)
                if re.search(rf"\bString\s+{re.escape(parameter_name)}\b", parameter)
            ),
            None,
        )
        if parameter_index is None:
            continue
        method_name = declaration.group(1)
        forwarded_arguments: list[str] = []
        for call_start, call_args in _java_call_argument_lists(text, f"{method_name}("):
            if declaration.start() <= call_start < declaration.end():
                continue
            if parameter_index >= len(call_args):
                return set()
            forwarded_arguments.append(call_args[parameter_index])
        selectors = {_edge_enum_selector(argument) for argument in forwarded_arguments}
        if forwarded_arguments and None not in selectors:
            return {selector for selector in selectors if selector is not None}
    return set()


def _contributor_edge_values(text: str) -> tuple[set[str], set[str], list[str]]:
    without_comments = _strip_comments(text)
    constants = {
        name: value
        for name, value in re.findall(
            r"\b(?:public|protected|private)?\s*static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*\"([A-Z][A-Z0-9_]*)\"\s*;",
            without_comments,
        )
    }
    values: set[str] = set()
    enum_selectors: set[str] = set()
    unresolved: list[str] = []
    for factory in re.finditer(r"\bGraphEdge\s*\.\s*([A-Za-z_$][\w$]*)\s*\(", without_comments):
        unresolved.append(f"GraphEdge.{factory.group(1)}(...) factory form")
    constructor_tokens = len(re.findall(r"\bnew\s+(?:[\w.]+\.)?GraphEdge\b", without_comments))
    argument_lists = _graph_edge_argument_lists(without_comments)
    if constructor_tokens != len(argument_lists):
        unresolved.append("unsupported GraphEdge constructor form")
    for args in argument_lists:
        if len(args) < 2:
            unresolved.append("unparseable GraphEdge constructor")
            continue
        expression = args[1].strip()
        literal = re.fullmatch(r'"([A-Z][A-Z0-9_]*)"', expression)
        if literal:
            values.add(literal.group(1))
            continue
        if expression in constants:
            values.add(constants[expression])
            continue
        selector = _edge_enum_selector(expression)
        if selector is not None:
            enum_selectors.add(selector)
            continue
        if re.fullmatch(r"[A-Za-z_$][\w$]*", expression):
            forwarded_selectors = _is_enum_forwarded_parameter(without_comments, expression)
            if forwarded_selectors:
                enum_selectors.update(forwarded_selectors)
                continue
        unresolved.append(expression)
    return values, enum_selectors, unresolved


def _contributor_type_identity(text: str) -> tuple[str, str] | None:
    without_comments = _strip_comments(text)
    declaration = re.search(
        r"\b(?:class|record)\s+(\w+)\b[^;\{]*\bimplements\s+[^\{;]*\bGraphProjectionContributor\b",
        without_comments,
    )
    if declaration is None:
        return None
    package_match = re.search(r"\bpackage\s+([\w.]+)\s*;", without_comments)
    name = declaration.group(1)
    package = package_match.group(1) if package_match else ""
    return (f"{package}.{name}" if package else name, name)
