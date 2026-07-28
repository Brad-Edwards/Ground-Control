"""Policy checks: deploy artifact consistency and methodology catalog drift.

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
    COMPOSE_VAR_REF_RE,
    DEPLOY_CANONICAL_ARTIFACTS,
    DEPLOY_DEAD_WRAPPER_PATH,
    DEPLOY_DOCKER_DIR,
    DEPLOY_WRAPPER_PATH,
    _parse_env_schema,
)
from .core import (
    DEPLOY_COMPOSE_PROD_PATH,
    REPO_ROOT,
    Violation,
)
from .env_templates import (
    _run_env_template_consumer_check,
)


def run_deploy_artifact_consistency(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert the operator-driven deploy artifacts have a single source of truth."""
    violations: list[Violation] = []
    ddir = root / DEPLOY_DOCKER_DIR
    schema_path = ddir / "env.schema"
    manifest_path = ddir / "MANIFEST.sha256"
    compose_path = root / DEPLOY_COMPOSE_PROD_PATH
    wrapper_path = root / DEPLOY_WRAPPER_PATH
    env_example = ddir / ".env.example"
    env_template = ddir / ".env.template"

    # 1. Single canonical production env template.
    if not env_example.exists():
        violations.append(
            Violation(
                code="deploy-env-template-missing",
                message="Canonical production env template is missing (GC-P023).",
                details=[f"expected {(DEPLOY_DOCKER_DIR / '.env.example').as_posix()}"],
            )
        )
    if env_template.exists():
        violations.append(
            Violation(
                code="deploy-env-template-duplicate",
                message=(
                    "Two production env templates exist; .env.template contradicted "
                    ".env.example and must stay removed in favor of the single "
                    "canonical .env.example (GC-P023 / #855)."
                ),
                details=[f"remove {(DEPLOY_DOCKER_DIR / '.env.template').as_posix()}"],
            )
        )

    # 2. env.schema is the single env contract.
    if not schema_path.exists():
        violations.append(
            Violation(
                code="deploy-env-schema-missing",
                message="deploy/docker/env.schema (single env contract) is missing (GC-P023).",
                details=[f"expected {(DEPLOY_DOCKER_DIR / 'env.schema').as_posix()}"],
            )
        )
        schema: dict[str, set[str]] = {}
    else:
        schema = _parse_env_schema(schema_path.read_text(encoding="utf-8"))
        if "RELEASE_PIN" not in schema.get("GC_IMAGE", set()):
            violations.append(
                Violation(
                    code="deploy-env-schema-release-pin",
                    message=(
                        "env.schema must mark GC_IMAGE RELEASE_PIN so the deploy-time "
                        "validator requires an immutable versioned release pin and "
                        "rejects a floating branch tag like :main (ADR-063 / #1222)."
                    ),
                    details=["add 'RELEASE_PIN GC_IMAGE' to env.schema"],
                )
            )

    # 3. Schema completeness vs the production compose contract.
    if schema and compose_path.exists():
        # Scan only non-comment lines: the compose file documents the
        # inherit-only form with a literal `${VAR:-}` inside a comment, which
        # is not a real variable reference.
        compose_text = "\n".join(
            line
            for line in compose_path.read_text(encoding="utf-8").splitlines()
            if not line.lstrip().startswith("#")
        )
        absent: list[str] = []
        not_required: list[str] = []
        seen: set[str] = set()
        for match in COMPOSE_VAR_REF_RE.finditer(compose_text):
            var, default = match.group(1), match.group(2)
            if var in seen:
                continue
            seen.add(var)
            present = schema.get(var, set())
            if not present:
                absent.append(var)
            elif default is None and "REQUIRED" not in present:
                not_required.append(var)
        if absent:
            violations.append(
                Violation(
                    code="deploy-env-schema-incomplete",
                    message=(
                        "deploy/docker/docker-compose.prod.yml dereferences variables "
                        "not declared in env.schema (schema/compose drift, GC-P023)."
                    ),
                    details=sorted(absent),
                )
            )
        if not_required:
            violations.append(
                Violation(
                    code="deploy-env-schema-required-mismatch",
                    message=(
                        "These variables are dereferenced with no compose default but "
                        "env.schema does not mark them REQUIRED (GC-P023)."
                    ),
                    details=sorted(not_required),
                )
            )

    # 4. MANIFEST.sha256 matches the canonical artifacts byte-for-byte.
    if not manifest_path.exists():
        violations.append(
            Violation(
                code="deploy-manifest-missing",
                message=(
                    "deploy/docker/MANIFEST.sha256 is missing; the deploy-time drift "
                    "guard has nothing to verify against (GC-P023). Generate it with "
                    "'make deploy-manifest'."
                ),
                details=[f"expected {(DEPLOY_DOCKER_DIR / 'MANIFEST.sha256').as_posix()}"],
            )
        )
    else:
        manifest: dict[str, str] = {}
        for line in manifest_path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            parts = stripped.split(None, 1)
            if len(parts) != 2:
                continue
            manifest[parts[1].strip()] = parts[0]
        if set(manifest) != set(DEPLOY_CANONICAL_ARTIFACTS):
            violations.append(
                Violation(
                    code="deploy-manifest-coverage",
                    message=(
                        "MANIFEST.sha256 must list exactly the canonical deploy "
                        "artifacts (GC-P023). Regenerate with 'make deploy-manifest'."
                    ),
                    details=[
                        f"expected: {', '.join(DEPLOY_CANONICAL_ARTIFACTS)}",
                        f"found: {', '.join(sorted(manifest)) or '<none>'}",
                    ],
                )
            )
        for name, expected_sha in manifest.items():
            artifact = ddir / name
            if not artifact.exists():
                violations.append(
                    Violation(
                        code="deploy-manifest-stale",
                        message="MANIFEST.sha256 lists a file that does not exist (GC-P023).",
                        details=[name],
                    )
                )
                continue
            actual_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
            if actual_sha != expected_sha:
                violations.append(
                    Violation(
                        code="deploy-manifest-stale",
                        message=(
                            f"{name} content does not match MANIFEST.sha256; the "
                            "deploy-time drift guard would reject a current /opt/gc "
                            "mirror. Regenerate with 'make deploy-manifest' (GC-P023)."
                        ),
                        details=[name],
                    )
                )

    # 5. Single operator wrapper: the dead divergent duplicate stays removed.
    if (root / DEPLOY_DEAD_WRAPPER_PATH).exists():
        violations.append(
            Violation(
                code="deploy-wrapper-duplicate",
                message=(
                    "deploy/scripts/deploy.sh was a dead divergent duplicate of the "
                    "operator wrapper (broken host-side health check) and must stay "
                    "removed; scripts/deploy.sh is the single wrapper (GC-P023 / #855)."
                ),
                details=[f"remove {DEPLOY_DEAD_WRAPPER_PATH.as_posix()}"],
            )
        )

    # 6. Single-source rollout logic: the wrapper orchestrates, it does not
    #    reimplement the docker compose pull/up rollout primitives.
    if wrapper_path.exists():
        wrapper_code = "\n".join(
            line
            for line in wrapper_path.read_text(encoding="utf-8").splitlines()
            if not line.lstrip().startswith("#")
        )
        if re.search(r"docker\s+compose\b[^\n]*\b(pull|up)\b", wrapper_code):
            violations.append(
                Violation(
                    code="deploy-wrapper-duplicates-logic",
                    message=(
                        "deploy/scripts/deploy.sh (operator wrapper) must not run "
                        "'docker compose pull/up'; the rollout lives only in the "
                        "canonical deploy/docker/deploy.sh (GC-P023 single-source)."
                    ),
                    details=[],
                )
            )

    # 7. Active templates advertise only keys something actually reads (#1384).
    violations.extend(_run_env_template_consumer_check(root))

    return violations


SKILL_METHODOLOGY_CATALOG_PATH = Path("skills/lit-review/methodology/catalog.yaml")


BACKEND_METHODOLOGY_CATALOG_PATH = Path(
    "backend/src/main/resources/research/methodology-catalog.yaml"
)


_METHODOLOGY_METHOD_KEY_RE = re.compile(r"^\s*-\s+key:\s*(\S+)\s*$")


_METHODOLOGY_SKILL_SOURCE_RE = re.compile(r"^\s*-\s+zotero_key:\s*(\S+)\s*$")


_METHODOLOGY_BACKEND_SOURCE_RE = re.compile(r"^\s*-\s+ref:\s*(\S+)\s*$")


def _parse_methodology_catalog(text: str, source_re: re.Pattern[str]) -> dict[str, set[str]]:
    """Map each method key to its set of source identifiers from a catalog file."""
    methods: dict[str, set[str]] = {}
    current: str | None = None
    for line in text.splitlines():
        key_match = _METHODOLOGY_METHOD_KEY_RE.match(line)
        if key_match:
            current = key_match.group(1)
            methods.setdefault(current, set())
            continue
        source_match = source_re.match(line)
        if source_match and current is not None:
            methods[current].add(source_match.group(1))
    return methods


def run_methodology_catalog_drift(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert the skill and backend methodology catalogs agree (ADR-078)."""
    skill_path = root / SKILL_METHODOLOGY_CATALOG_PATH
    backend_path = root / BACKEND_METHODOLOGY_CATALOG_PATH
    missing = [
        p.as_posix()
        for p in (SKILL_METHODOLOGY_CATALOG_PATH, BACKEND_METHODOLOGY_CATALOG_PATH)
        if not (root / p).exists()
    ]
    if missing:
        return [
            Violation(
                code="methodology-catalog-drift",
                message=(
                    "Both methodology catalogs must exist so the skill mirror and "
                    "backend source-of-truth can be drift-checked (ADR-078)."
                ),
                details=[f"missing catalog file: {p}" for p in missing],
            )
        ]

    skill = _parse_methodology_catalog(
        skill_path.read_text(encoding="utf-8"), _METHODOLOGY_SKILL_SOURCE_RE
    )
    backend = _parse_methodology_catalog(
        backend_path.read_text(encoding="utf-8"), _METHODOLOGY_BACKEND_SOURCE_RE
    )

    details: list[str] = []
    skill_keys = set(skill)
    backend_keys = set(backend)
    only_skill = sorted(skill_keys - backend_keys)
    only_backend = sorted(backend_keys - skill_keys)
    if only_skill:
        details.append(f"methods only in skill catalog: {only_skill}")
    if only_backend:
        details.append(f"methods only in backend catalog: {only_backend}")

    for key in sorted(skill_keys & backend_keys):
        if skill[key] != backend[key]:
            skill_only = sorted(skill[key] - backend[key])
            backend_only = sorted(backend[key] - skill[key])
            details.append(
                f"method '{key}' source drift: "
                f"skill-only={skill_only} backend-only={backend_only}"
            )

    for key in sorted(skill_keys):
        if not skill[key]:
            details.append(f"method '{key}' has zero sources in the skill catalog")
    for key in sorted(backend_keys):
        if not backend[key]:
            details.append(f"method '{key}' has zero required sources in the backend catalog")

    if not details:
        return []
    return [
        Violation(
            code="methodology-catalog-drift",
            message=(
                "The skill methodology catalog "
                f"({SKILL_METHODOLOGY_CATALOG_PATH.as_posix()}) and the backend "
                f"source-of-truth ({BACKEND_METHODOLOGY_CATALOG_PATH.as_posix()}) "
                "must declare the same method keys and the same source identifiers "
                "per method (skill zotero_key == backend required_sources[].ref, "
                "ADR-078). Reconcile the two catalogs."
            ),
            details=details,
        )
    ]


FRONTEND_API_TYPES_PATH = "contracts/gen/typescript/api.ts"


MCP_LIB_PATH = "mcp/ground-control/lib.js"


MCP_LIB_DIR = "mcp/ground-control/lib"


def read_mcp_library(root: Path = REPO_ROOT) -> str | None:
    """The MCP shared library's full source, barrel plus every extracted module.

    lib.js was a single 20,634-line file until issue #1355 split it under the repo's
    500-LOC limit; it is now a barrel of star re-exports. A check that reads only that
    file sees no implementation at all and silently passes, so every content check reads
    the whole surface instead of one path.
    """
    barrel = root / MCP_LIB_PATH
    if not barrel.exists():
        return None
    parts = [barrel.read_text(encoding="utf-8")]
    lib_dir = root / MCP_LIB_DIR
    if lib_dir.is_dir():
        parts.extend(
            path.read_text(encoding="utf-8") for path in sorted(lib_dir.glob("*.js"))
        )
    return "\n".join(parts)


_ENUM_STATE_DIR = "backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state"


_AUDIT_ENUM_STATE_DIR = "backend/src/main/java/com/keplerops/groundcontrol/domain/audits/state"


_VERIFICATION_ENUM_STATE_DIR = "backend/src/main/java/com/keplerops/groundcontrol/domain/verification/state"


_GRAPH_MODEL_DIR = "backend/src/main/java/com/keplerops/groundcontrol/domain/graph/model"


# Java enum body: from the opening `{` to whichever comes first — the `;` that
# terminates the constant list (present when the enum has methods/fields, e.g.
# Status) or the closing `}` (constant-only enums). `[^{;]*` between `enum NAME`
# and `{` tolerates `implements`/generics clauses.
_JAVA_ENUM_BODY_RE = re.compile(r"\benum\s+\w+[^{;]*\{(.*?)(?:;|\})", re.DOTALL)


_PAREN_GROUP_RE = re.compile(r"\([^)]*\)")  # strip enum-constant constructor args: FOO("x")


_LINE_COMMENT_RE = re.compile(r"//[^\n]*")


_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)


_ENUM_CONSTANT_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")


_STRING_LITERAL_RE = re.compile(r'"([^"\\]*)"')
