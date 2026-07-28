"""Policy checks: environment template consumers.

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
from .repo_identity import (
    BACKEND_JAVA_GLOB,
    ENV_TEMPLATE_CONTRACTS,
    SPRING_CLASS_RE,
    SPRING_COLLECTION_RE,
    SPRING_FIELD_RE,
    SPRING_PREFIX_RE,
    SpringBindingIndex,
    SpringField,
    _camel_to_env,
    _compose_consumed_names,
    _env_template_keys,
    _node_env_consumed_names,
    _shell_consumed_names,
    _yaml_placeholder_consumed_names,
)
from .core import (
    Violation,
)


def _spring_binding_index(root: Path) -> SpringBindingIndex:
    """Index the backend's @ConfigurationProperties roots and their POJO fields.

    Fields are attributed to their nearest enclosing class declaration, so the
    nested ``SecurityProperties.ApiCredential`` is indexed separately from its
    outer class - which is what lets a binding path be validated to its leaf
    rather than merely to its top-level field.
    """
    roots: dict[str, str] = {}
    fields: dict[str, list[SpringField]] = {}
    for source in root.glob(BACKEND_JAVA_GLOB):
        try:
            text = source.read_text(encoding="utf-8")
        except OSError:
            continue
        classes = [(m.start(), m.group(1)) for m in SPRING_CLASS_RE.finditer(text)]
        if not classes:
            continue
        prefix_match = SPRING_PREFIX_RE.search(text)
        if prefix_match:
            # The root POJO is the first class declared after the annotation.
            after = [name for pos, name in classes if pos > prefix_match.start()]
            if after:
                prefix_env = prefix_match.group(1).replace("-", "").replace(".", "_").upper()
                roots[prefix_env] = after[0]
        for field_match in SPRING_FIELD_RE.finditer(text):
            enclosing = [name for pos, name in classes if pos < field_match.start()]
            if not enclosing:
                continue
            declared_type = field_match.group(1).strip()
            collection = SPRING_COLLECTION_RE.match(declared_type)
            if collection:
                element, is_collection = collection.group(1), True
            elif declared_type.endswith("[]"):
                element, is_collection = declared_type[:-2].strip(), True
            else:
                element, is_collection = declared_type, False
            element = element.split("<")[0].split(".")[-1].strip()
            fields.setdefault(enclosing[-1], []).append(
                SpringField(_camel_to_env(field_match.group(2)), element, is_collection)
            )
    return SpringBindingIndex(
        roots=roots, fields={cls: tuple(fs) for cls, fs in fields.items()}
    )


def _binds_within(cls: str, tail: str, index: SpringBindingIndex, depth: int = 0) -> bool:
    """Walk a relaxed-binding tail through ``cls``'s declared fields.

    ``CREDENTIALS_0_TOKEN`` against SecurityProperties resolves field
    ``credentials`` (a List<ApiCredential>), consumes the ``0`` index, then
    requires ``TOKEN`` to be a declared field of ApiCredential. A misspelled
    ``CREDENTIALS_0_TOKNE`` binds to nothing and stays an orphan - which is the
    whole point of resolving the path instead of matching its prefix.
    """
    if not tail:
        return True
    if depth > 8:  # cyclic type graph; refuse rather than recurse forever
        return False
    for field in index.fields.get(cls, ()):
        if tail == field.env_name:
            return True
        if not tail.startswith(f"{field.env_name}_"):
            continue
        rest = tail[len(field.env_name) + 1 :]
        if field.is_collection:
            head, _, remainder = rest.partition("_")
            if not head.isdigit():
                continue  # an indexed property needs an index
            if not remainder:
                return True  # scalar element, e.g. IP_ALLOWLIST_0
            return _binds_within(field.element_type, remainder, index, depth + 1)
        if field.element_type in index.fields:
            return _binds_within(field.element_type, rest, index, depth + 1)
        continue  # a scalar with a trailing path binds to nothing
    return False


def _spring_binds(key: str, index: SpringBindingIndex) -> bool:
    """True when ``key`` binds to a real property under a @ConfigurationProperties root.

    Spring's relaxed binding maps ``GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN``
    onto ``groundcontrol.security.credentials[0].token``, so the indexed ADR-026
    credential slots have a real consumer without appearing literally in any yaml.
    Resolving the whole path - not just the prefix - is what keeps an unknown
    child of a live prefix from certifying itself.
    """
    for prefix_env, cls in index.roots.items():
        if key == prefix_env:
            continue  # the prefix alone is not a property
        if key.startswith(f"{prefix_env}_") and _binds_within(
            cls, key[len(prefix_env) + 1 :], index
        ):
            return True
    return False


def _run_env_template_consumer_check(root: Path) -> list[Violation]:
    """Assert every key an active env template advertises has a live consumer."""
    violations: list[Violation] = []
    spring_index: SpringBindingIndex | None = None

    def _scan(globs: Iterable[str], extract) -> set[str]:
        found: set[str] = set()
        for glob in globs:
            for source in root.glob(glob):
                try:
                    found |= extract(source.read_text(encoding="utf-8"))
                except OSError:
                    continue
        return found

    for contract in ENV_TEMPLATE_CONTRACTS:
        template_path = root / contract.template
        if not template_path.exists():
            # Absence of the canonical prod template is already reported as
            # deploy-env-template-missing; the dev template is optional.
            continue
        keys = _env_template_keys(template_path.read_text(encoding="utf-8"))
        if not keys:
            continue

        consumed = _scan(contract.compose_globs, _compose_consumed_names)
        consumed |= _scan(contract.yaml_placeholder_globs, _yaml_placeholder_consumed_names)
        consumed |= _scan(contract.node_env_globs, _node_env_consumed_names)
        consumed |= _scan(contract.shell_globs, _shell_consumed_names)

        if contract.spring_binding:
            if spring_index is None:
                spring_index = _spring_binding_index(root)
            consumed.update(key for key in keys if _spring_binds(key, spring_index))

        orphans = [key for key in keys if key not in consumed]
        if orphans:
            surfaces = ", ".join(
                contract.compose_globs
                + contract.yaml_placeholder_globs
                + contract.node_env_globs
                + contract.shell_globs
            )
            violations.append(
                Violation(
                    code="deploy-env-template-orphan-key",
                    message=(
                        f"{contract.template} advertises configuration keys that nothing "
                        "reads. An operator setting them gets silence, not behavior. "
                        "Delete the key, or wire the consumer that honors it "
                        "(GC-P023 / #1384). A compose literal (KEY=value) pins the value "
                        "and is not a consumer of the operator's; an env.schema "
                        "declaration or a mention in a comment or message is not a read."
                    ),
                    details=sorted(orphans) + [f"consumer surfaces searched: {surfaces}"],
                )
            )
    return violations
