"""Policy checks: repository identity drift.

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
    require_scanned,
    REPO_ROOT,
    Violation,
)


# Repository identity drift check (GC-P026 / issue #1383).
#
# After the GitHub owner move to autarchy-ai/Ground-Control, several active
# surfaces still named the stale KeplerOps/Ground-Control owner, and defaulted
# repo-bound operations could target an inaccessible repository. This static
# post-condition pins every ACTIVE repository-identity surface (config,
# workflows, scripts, docs, deploy units, frontend placeholders) to the single
# canonical owner, so a re-introduced stale slug — or the next owner move — fails
# `make policy`. Adding a new active surface that names the repo is one inventory
# row.
#
# Historical records are intentionally EXCLUDED (not in the inventory): ADRs and
# CHANGELOG.md / changelog.d/** record the owner that was current at the time and
# must NOT be rewritten. Test files are excluded too — their generic URL-parsing
# and negative-case fixtures legitimately carry arbitrary owners. The Java
# package `com.keplerops.groundcontrol`, the SonarCloud org/project, and the GHCR
# image namespace are separate concepts, not repository-identity slugs.
CANONICAL_REPO_OWNER = "autarchy-ai"


CANONICAL_REPO_SLUG = "autarchy-ai/Ground-Control"


# Prior owners this issue eradicates (KeplerOps -> Brad-Edwards -> autarchy-ai).
# A bare-owner reference (e.g. a UI placeholder that carries only the owner, not
# a full owner/repo slug) cannot be validated against the canonical slug, so
# active surfaces are also guarded against these specific stale-owner literals.
STALE_REPO_OWNERS = ("KeplerOps", "Brad-Edwards")


# owner/Ground-Control inside a GitHub URL or git@ remote (badges, clone URLs,
# raw-content URLs, issue links). Anchored on the canonical repo NAME so links
# to unrelated third-party repos are not flagged; only the owner is validated.
_REPO_IDENTITY_URL_RE = re.compile(
    r"(?:github\.com[/:]|githubusercontent\.com/)([A-Za-z0-9][A-Za-z0-9-]*)/Ground-Control\b"
)


# Bare owner/Ground-Control config slug (github_repo:, --repo, etc.). The
# negative lookbehind excludes filesystem paths like `src/Ground-Control`, so a
# local checkout path (e.g. in .mcp.json args) is not mistaken for an identity
# slug.
_REPO_IDENTITY_SLUG_RE = re.compile(
    r"(?<![\w./-])([A-Za-z0-9][A-Za-z0-9-]*)/Ground-Control\b"
)


# Identity-declaration assignments (`github_repo:` / `GH_REPO`) carry the FULL
# owner/repo of *this* repository, so BOTH segments are validated against the
# canonical slug — a well-formed drift to a wrong repo NAME (e.g.
# `autarchy-ai/Other`) must fail too, not just a wrong owner. Tolerates the
# YAML `key: value` and JSON/shell `"KEY": "value"` / `KEY=value` forms.
_REPO_IDENTITY_ASSIGN_RE = re.compile(
    r"(?:github_repo|GH_REPO)\b['\"]?\s*[:=]\s*['\"]?"
    r"([A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9][A-Za-z0-9._-]*)"
)


# Bare stale-owner literals (UI placeholders, prose) in active surfaces.
_STALE_OWNER_RE = re.compile(
    r"\b(" + "|".join(re.escape(owner) for owner in STALE_REPO_OWNERS) + r")\b"
)


# The full-slug assignment matcher runs ONLY on real config declarations, where
# `github_repo:` / `GH_REPO` carry this repo's live identity. Docs legitimately
# show a generic `github_repo: owner/repo` format example that is not a drift.
_REPO_IDENTITY_CONFIG_FILES = frozenset({".ground-control.yaml", ".mcp.json"})


REPO_IDENTITY_INVENTORY: tuple[Path, ...] = (
    Path(".ground-control.yaml"),
    Path(".mcp.json"),
    Path(".github/workflows/pack-registry-sync.yml"),
    Path("scripts/pack-sync.sh"),
    Path("scripts/deploy.sh"),
    Path("scripts/check-pr-body.sh"),
    Path("README.md"),
    Path("CONTRIBUTING.md"),
    Path("docs/DEVELOPMENT_WORKFLOW.md"),
    Path("docs/API.md"),
    Path("docs/deployment/DEPLOYMENT.md"),
    Path("docs/operations/backup-restore.md"),
    Path("docs/notes/agent-knowledge-system-design.md"),
    Path("mcp/citation/citation_mcp/http.py"),
    Path("frontend/src/components/traceability-form.tsx"),
    Path("frontend/src/pages/admin.tsx"),
    Path("deploy/systemd/gc-backup.service"),
    Path("deploy/systemd/gc-backup.timer"),
    Path("deploy/systemd/gc-restore-test.service"),
    Path("deploy/systemd/gc-restore-test.timer"),
)


def run_repo_identity_drift(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert every inventoried active surface names the canonical repo identity.

    Three matchers run per line: a canonical-name URL/slug matcher (owner drift
    on `<owner>/Ground-Control` references), a `github_repo`/`GH_REPO` assignment
    matcher (the full `owner/repo` is validated, so a wrong repo NAME fails too),
    and a stale-owner literal matcher (bare prior-owner tokens such as a UI
    placeholder). A drifted reference in any inventoried config/workflow/script/
    doc/deploy artifact is the stale identity that routed defaulted operations at
    an inaccessible repository (#1383). Absent inventory files are skipped.
    """
    offenders: list[str] = []
    scanned = 0
    for rel_path in REPO_IDENTITY_INVENTORY:
        file_path = root / rel_path
        if not file_path.exists():
            continue
        scanned += 1
        text = file_path.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), start=1):
            findings: list[tuple[str, str]] = []
            for pattern in (_REPO_IDENTITY_URL_RE, _REPO_IDENTITY_SLUG_RE):
                for match in pattern.finditer(line):
                    owner = match.group(1)
                    if owner != CANONICAL_REPO_OWNER:
                        findings.append(
                            (f"{owner}/Ground-Control", "non-canonical repository owner")
                        )
            if rel_path.as_posix() in _REPO_IDENTITY_CONFIG_FILES:
                for match in _REPO_IDENTITY_ASSIGN_RE.finditer(line):
                    slug = match.group(1)
                    if slug.lower() != CANONICAL_REPO_SLUG.lower():
                        findings.append((slug, "non-canonical repository identity"))
            for match in _STALE_OWNER_RE.finditer(line):
                findings.append((match.group(1), "stale repository owner literal"))
            seen: set[tuple[str, str]] = set()
            for token, why in findings:
                if (token, why) in seen:
                    continue
                seen.add((token, why))
                offenders.append(
                    f"{rel_path.as_posix()}:{line_number} {why}: '{token}' "
                    f"(expected '{CANONICAL_REPO_SLUG}')"
                )
    guard = require_scanned("repo-identity inventory", scanned)
    if guard:
        return guard

    if not offenders:
        return []
    return [
        Violation(
            code="repo-identity-drift",
            message=(
                "Active repository-identity surfaces must name the single "
                f"canonical '{CANONICAL_REPO_SLUG}' (GC-P026 / #1383). "
                "Historical ADR/changelog references and test fixtures are "
                "exempt (not inventoried)."
            ),
            details=offenders,
        )
    ]


DEPLOY_DOCKER_DIR = Path("deploy/docker")


DEPLOY_WRAPPER_PATH = Path("scripts/deploy.sh")


DEPLOY_DEAD_WRAPPER_PATH = Path("deploy/scripts/deploy.sh")


DEPLOY_CANONICAL_ARTIFACTS: tuple[str, ...] = (
    "deploy.sh",
    "docker-compose.prod.yml",
    "validate-env.sh",
    "env.schema",
)


COMPOSE_VAR_REF_RE = re.compile(r"\$\{([A-Z_][A-Z0-9_]*)(:-[^}]*)?\}")


def _parse_env_schema(text: str) -> dict[str, set[str]]:
    """Parse env.schema into a mapping of variable -> set of directives.

    Same line format the bash validator (validate-env.sh) consumes:
    ``<DIRECTIVE> <VAR>`` per line, ``#`` comments and blanks ignored. Keeping
    the parse this simple is deliberate — the schema is the single source both
    this gate and the shell validator read, so neither carries its own copy of
    the rules.
    """
    directives: dict[str, set[str]] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if len(parts) < 2:
            continue
        directives.setdefault(parts[1], set()).add(parts[0])
    return directives


ENV_TEMPLATE_ASSIGNMENT_RE = re.compile(r"^\s*(?:#\s*)?(?:export\s+)?([A-Z][A-Z0-9_]*)=")


COMPOSE_INHERIT_RE = re.compile(r"^(\s*)-\s*([A-Z][A-Z0-9_]*)\s*$")


COMPOSE_ENVIRONMENT_KEY_RE = re.compile(r"^(\s*)environment:\s*$")


# Spring placeholders default with a single colon (${VAR:default}), unlike
# compose's ${VAR:-default}, so the compose pattern would miss every defaulted one.
SPRING_PLACEHOLDER_RE = re.compile(r"\$\{([A-Z_][A-Z0-9_]*)[:}]")


SHELL_EXPANSION_RE = re.compile(r"\$\{?([A-Z][A-Z0-9_]*)\b")


SHELL_ENV_LOOKUP_RE = re.compile(r"ENV_VALUES\[\s*[\"']?([A-Z][A-Z0-9_]*)[\"']?\s*\]")


SPRING_PREFIX_RE = re.compile(r"@ConfigurationProperties\s*\(\s*prefix\s*=\s*\"([^\"]+)\"")


SPRING_FIELD_RE = re.compile(
    r"^[ \t]*private\s+(?:final\s+)?(.+?)\s+([a-z]\w*)\s*[=;]", re.MULTILINE
)


SPRING_CLASS_RE = re.compile(r"\b(?:class|record)\s+(\w+)")


SPRING_COLLECTION_RE = re.compile(r"^(?:List|Set|Collection|Iterable)\s*<\s*(.+?)\s*>$")


BACKEND_JAVA_GLOB = "backend/src/main/java/**/*.java"


# JS line/block comments, then string literals. A key named in either is a
# mention, not a read - but the bracket read form (process.env["VAR"]) keeps its
# key inside a string legitimately, so comments and strings are stripped in two
# stages rather than one.
JS_COMMENT_RE = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)


JS_STRING_RE = re.compile(r"\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`", re.DOTALL)


# A yaml comment runs from an unquoted ` #` (or a line-leading #) to end of line.
YAML_TRAILING_COMMENT_RE = re.compile(r"(?:^|\s)#.*$")


# Single-quoted shell text is literal - no expansion happens inside it.
SHELL_SINGLE_QUOTED_RE = re.compile(r"'[^']*'")


SHELL_INLINE_COMMENT_RE = re.compile(r"(?:^|\s)#.*$")


@dataclass(frozen=True)
class EnvTemplateContract:
    """One active env template and the surfaces allowed to consume its keys.

    Each glob group is matched on that surface's real read syntax; see the
    module comment above for why a declaration or a mention is not a consumer.
    """

    template: str
    compose_globs: tuple[str, ...] = ()
    yaml_placeholder_globs: tuple[str, ...] = ()
    node_env_globs: tuple[str, ...] = ()
    shell_globs: tuple[str, ...] = ()
    spring_binding: bool = False


ENV_TEMPLATE_CONTRACTS: tuple[EnvTemplateContract, ...] = (
    # Local dev + the MCP client's startup .env (mcp/ground-control/index.js).
    # The backend runs from source here (bootRun), so an application.yml
    # placeholder IS an effective consumer - unlike the production case, where
    # compose has to forward the value into the container first.
    EnvTemplateContract(
        template=".env.example",
        compose_globs=("docker-compose.yml",),
        yaml_placeholder_globs=("backend/src/main/resources/application*.yml",),
        node_env_globs=("mcp/ground-control/*.js",),
        spring_binding=True,
    ),
    # The operator's /opt/gc/.env contract. A value here reaches the backend only
    # if the production compose interpolates or inherits it; the deploy scripts
    # are the only other executable reader.
    EnvTemplateContract(
        template="deploy/docker/.env.example",
        compose_globs=("deploy/docker/docker-compose.prod.yml",),
        shell_globs=("deploy/docker/validate-env.sh", "deploy/docker/deploy.sh"),
    ),
)


def _code_lines(text: str, strip_comment: re.Pattern[str] | None = None) -> list[str]:
    """Lines with comments removed - a mention in a comment is not a read.

    Drops whole-line comments, and (when ``strip_comment`` is given) the trailing
    comment on an otherwise executable line, so ``- FOO   # GC_DEAD is gone``
    cannot certify GC_DEAD.
    """
    lines: list[str] = []
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        lines.append(strip_comment.sub("", line) if strip_comment else line)
    return lines


def _env_template_keys(text: str) -> list[str]:
    """Every key an env template advertises, commented assignments included.

    A commented `# GC_EMBEDDING_MODEL=...` still tells an operator the key is
    supported - uncommenting it is the documented way to use it - so a dead one
    misleads exactly as much as a live one.
    """
    keys: list[str] = []
    for line in text.splitlines():
        match = ENV_TEMPLATE_ASSIGNMENT_RE.match(line)
        if match and match.group(1) not in keys:
            keys.append(match.group(1))
    return keys


def _compose_consumed_names(text: str) -> set[str]:
    """Names a compose file actually reads from the env file.

    Two forms read the operator's value: interpolation (``${VAR}``,
    ``${VAR:-default}``) anywhere, and list-form inherit (``- VAR``) inside an
    ``environment:`` block, which forwards the variable only when it is set.

    A literal assignment (``- VAR=8000``) is excluded - it overrides the env file
    rather than reading it - and a bare ``- item`` outside an ``environment:``
    block is a list entry, not a variable.
    """
    consumed: set[str] = set()
    env_block_indent: int | None = None
    for line in _code_lines(text, YAML_TRAILING_COMMENT_RE):
        env_key = COMPOSE_ENVIRONMENT_KEY_RE.match(line)
        if env_key:
            env_block_indent = len(env_key.group(1))
            continue
        consumed.update(match.group(1) for match in COMPOSE_VAR_REF_RE.finditer(line))
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        if env_block_indent is not None and indent <= env_block_indent:
            env_block_indent = None
        inherit = COMPOSE_INHERIT_RE.match(line)
        if inherit and env_block_indent is not None:
            consumed.add(inherit.group(2))
    return consumed


def _yaml_placeholder_consumed_names(text: str) -> set[str]:
    """Names an application*.yml dereferences as a ``${VAR}`` placeholder.

    Spring's default syntax is a single colon (``${GC_SERVER_PORT:8000}``), not
    compose's ``:-``, so this cannot reuse COMPOSE_VAR_REF_RE.
    """
    consumed: set[str] = set()
    for line in _code_lines(text, YAML_TRAILING_COMMENT_RE):
        consumed.update(match.group(1) for match in SPRING_PLACEHOLDER_RE.finditer(line))
    return consumed


def _node_env_consumed_names(text: str) -> set[str]:
    """Names a Node source reads via ``process.env.VAR`` / ``process.env["VAR"]``.

    Comments are stripped from both forms: ``// process.env.GC_DEAD`` is a
    mention, not a read. String literals are then stripped for the dotted form
    only, so a quoted ``"process.env.GC_DEAD"`` cannot certify a key while the
    bracket form - whose key legitimately lives inside a string - still can.
    """
    code = JS_COMMENT_RE.sub(" ", text)
    bracket_re = re.compile(r"process\.env\[\s*['\"]([A-Z][A-Z0-9_]*)['\"]\s*\]")
    consumed: set[str] = {match.group(1) for match in bracket_re.finditer(code)}
    dotted_re = re.compile(r"process\.env\.([A-Z][A-Z0-9_]*)")
    consumed.update(match.group(1) for match in dotted_re.finditer(JS_STRING_RE.sub(" ", code)))
    return consumed


def _shell_consumed_names(text: str) -> set[str]:
    """Names a shell script actually expands or looks up.

    ``$VAR`` / ``${VAR...}`` expansion, and the ``ENV_VALUES[VAR]`` associative
    lookup validate-env.sh uses to read the parsed env file. A key named only in
    an error-message string or a comment is a mention, not a read.

    The two patterns are applied independently because they nest: in
    ``${ENV_VALUES[GC_FOO]:-}`` a single alternation would match ``${ENV_VALUES``
    and consume the very lookup whose key we need.

    Inline comments and single-quoted text are dropped first - the shell does not
    expand either, so a key appearing there is a mention, not a read.
    """
    consumed: set[str] = set()
    for line in _code_lines(text, SHELL_INLINE_COMMENT_RE):
        executable = SHELL_SINGLE_QUOTED_RE.sub(" ", line)
        consumed.update(match.group(1) for match in SHELL_EXPANSION_RE.finditer(executable))
        consumed.update(match.group(1) for match in SHELL_ENV_LOOKUP_RE.finditer(executable))
    consumed.discard("ENV_VALUES")  # the array's own name is not an env key
    return consumed


def _camel_to_env(name: str) -> str:
    """``ipAllowlist`` -> ``IP_ALLOWLIST`` (Spring's relaxed-binding env form)."""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()


@dataclass(frozen=True)
class SpringField:
    """One bindable property: its env-form name, whether it is indexed, its type."""

    env_name: str
    element_type: str
    is_collection: bool


@dataclass(frozen=True)
class SpringBindingIndex:
    """@ConfigurationProperties roots plus the declared fields of every POJO."""

    roots: dict[str, str]  # env-form prefix -> declaring class
    fields: dict[str, tuple[SpringField, ...]]  # class -> its declared fields
