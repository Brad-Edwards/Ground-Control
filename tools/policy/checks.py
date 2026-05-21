from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[2]
ADR_POLICY_PATH = REPO_ROOT / "architecture" / "policies" / "adr-policy.json"
CONTROLLER_PATH_RE = re.compile(
    r"^backend/src/main/java/com/keplerops/groundcontrol/api/.+Controller\.java$"
)
MIGRATION_PATH_RE = re.compile(r"^backend/src/main/resources/db/migration/V\d+__.+\.sql$")
PR_REQUIREMENT_RE = re.compile(r"\b[A-Z][A-Z0-9]+-[A-Z0-9]+(?:-\d+|\d+)\b")

# ---------------------------------------------------------------------------
# Deferral-disposition classifier (issue #830, ADR-029).
#
# ADR-029's contract: every reviewer finding gets a recorded disposition, and
# the only valid ones are `fix`, `wontfix` (with explicit user authorization),
# or `not-applicable` (with rationale). `defer` — "to a follow-up PR / issue /
# later iteration" — is not in the contract. This classifier is the shared
# logic behind two mechanical layers: the PreToolUse hook at
# `.claude/hooks/block-defer-language.py` (tool-call time) and
# `run_no_deferral_disposition_check` below (completion-gate time, via
# `bin/policy`). The hook carries a byte-for-byte copy of the regex tables and
# `classify_deferral_language` because it is copied standalone to
# ~/.claude/hooks/ and cannot import this module; `tools/policy/deferral_cases.json`
# is the shared golden-case file both test suites load, so the two copies
# cannot drift without a test failing.
#
# Two tiers:
#   Tier 1 — explicit forward-deferral disposition phrases ("deferred to a
#     follow-up PR", "in a subsequent PR", "will be addressed in a follow-up",
#     "fixed in a subsequent PR", "handled as a follow-up issue"). Forbidden on
#     EVERY surface, including a brand-new issue body.
#   Tier 2 — softer deferral signals (bare "defer*", "TBD later",
#     "to be filed/done/addressed later/separately"). Forbidden ONLY on the
#     two surfaces where you are closing or commenting on the very issue under
#     implementation (`issue-close`, `issue-comment`) — that is the
#     "deferring in a closing comment" failure mode from #830 case #2. On
#     issue/PR creation, editing, or PR comments, these phrases are too
#     overloaded with legitimate scope-bounding / sibling-PR-reference uses to
#     flag mechanically.
#
# Bare "out of scope" is intentionally NOT a pattern: a PR body or new issue
# legitimately scope-bounds its own work with an "## Out of scope" section, and
# the dangerous "out of scope, deferred to a follow-up #831" construction is
# already caught by the Tier-1 deferral-verb patterns.
# ---------------------------------------------------------------------------

DEFERRAL_CASES_PATH = Path(__file__).resolve().parent / "deferral_cases.json"

# Surfaces where Tier 2 (the softer signals) is enforced in addition to Tier 1.
_DEFERRAL_TIER2_SURFACES: frozenset[str] = frozenset({"issue-close", "issue-comment"})

_DEFERRAL_TIER1_PATTERNS: tuple[tuple[str, str], ...] = (
    (
        "defer-to-followup",
        r"\bdefer(?:red|s|ring|ral)?\b[^.\n]{0,40}?\b(?:to|until|for|into)\b[^.\n]{0,25}?"
        r"\b(?:follow[-\s]?up|subsequent|later|future|next|another)\b",
    ),
    (
        "in-subsequent-unit",
        r"\b(?:in|to|as)\s+(?:a\s+|the\s+|another\s+)?(?:subsequent|follow[-\s]?up)\s+"
        r"(?:PR|pull\s+request|issue|ticket|commit|iteration|sprint|cycle|change)\b",
    ),
    (
        "addressed-in-followup",
        r"\b(?:will|to|shall|can)\s+be\s+(?:addressed|handled|done|fixed|implemented|resolved|filed|tracked)\s+"
        r"(?:in|by|as)\s+(?:a\s+|the\s+|another\s+)?(?:follow[-\s]?up|subsequent|later|future|next)\b",
    ),
    (
        "fix-in-followup",
        r"\b(?:address|handle|fix|implement|resolve)(?:ed|d)?\s+(?:this\s+|that\s+|it\s+|them\s+|the\s+rest\s+)?"
        r"(?:in|as)\s+(?:a\s+|the\s+|another\s+)?(?:follow[-\s]?up|subsequent)\s+"
        r"(?:PR|pull\s+request|issue|ticket|commit|change)\b",
    ),
)

_DEFERRAL_TIER2_PATTERNS: tuple[tuple[str, str], ...] = (
    ("tbd-postponement", r"\bTBD\b\s*(?:[.;:,)\]]|$|\b(?:later|in\b|for\b|—|-))"),
    (
        "to-be-done-later",
        r"\bto\s+be\s+(?:done|addressed|filed|tracked|handled|fixed)\s+(?:later|separately|in\s+a\b|elsewhere)\b",
    ),
)

# Bare "defer*" word, Tier 2, applied with a negation guard on the preceding
# window and an exemption for the historical "deferred from #N" framing.
_DEFERRAL_BARE_WORD_RE = re.compile(r"\bdefer(?:red|s|ring|ral)?\b", re.IGNORECASE)
_DEFERRAL_NEGATION_BEFORE_RE = re.compile(
    r"\b(?:do\s+not|don'?t|never|no|not|should\s+not|shall\s+not|cannot|can'?t|without|avoid|stop)\b"
    r"(?:\W+\w+){0,3}\W*$",
    re.IGNORECASE,
)
_DEFERRAL_BARE_WORD_HISTORICAL_AFTER_RE = re.compile(r"^\W*from\b", re.IGNORECASE)

_DEFERRAL_TIER1_RES = tuple(
    (name, re.compile(pat, re.IGNORECASE | re.DOTALL)) for name, pat in _DEFERRAL_TIER1_PATTERNS
)
_DEFERRAL_TIER2_RES = tuple(
    (name, re.compile(pat, re.IGNORECASE)) for name, pat in _DEFERRAL_TIER2_PATTERNS
)


def classify_deferral_language(text: str, surface: str) -> tuple[str, str | None]:
    """Classify body/comment text for deferral-disposition language.

    Returns ``("deny", "<tier>:<pattern-name>")`` when the text contains
    forbidden deferral language for the given ``surface``, or ``("allow", None)``
    otherwise. ``surface`` is one of ``issue-create``, ``issue-edit``,
    ``issue-close``, ``issue-comment``, ``pr-create``, ``pr-edit``,
    ``pr-comment``, ``pr-body``.

    Tier 1 patterns deny on every surface; Tier 2 patterns deny only on the
    surfaces in :data:`_DEFERRAL_TIER2_SURFACES` (closing/commenting on the
    issue under implementation).
    """
    if not text:
        return ("allow", None)
    for name, rx in _DEFERRAL_TIER1_RES:
        if rx.search(text):
            return ("deny", f"tier1:{name}")
    if surface in _DEFERRAL_TIER2_SURFACES:
        for name, rx in _DEFERRAL_TIER2_RES:
            if rx.search(text):
                return ("deny", f"tier2:{name}")
        for match in _DEFERRAL_BARE_WORD_RE.finditer(text):
            before = text[max(0, match.start() - 32) : match.start()]
            if _DEFERRAL_NEGATION_BEFORE_RE.search(before):
                continue
            after = text[match.end() : match.end() + 12]
            if _DEFERRAL_BARE_WORD_HISTORICAL_AFTER_RE.match(after):
                continue
            return ("deny", "tier2:bare-defer")
    return ("allow", None)


_DEFERRAL_DENIAL_GUIDANCE = (
    "Deferral is not a valid disposition (ADR-029). Every reviewer finding must "
    "be one of: (a) fixed now in the same diff; (b) recorded `wontfix` with "
    "explicit user authorization quoted; or (c) recorded `not-applicable` with a "
    "rationale. 'Defer to a follow-up PR / issue / later iteration' is not in the "
    "contract — filing a tracking issue does not make it one. Re-route to fix-now "
    "or escalate to the user on the issue thread for authorization."
)


def run_no_deferral_disposition_check(
    *, pr_body: str | None = None, root: Path = REPO_ROOT
) -> list[Violation]:
    """Flag deferral-disposition language in the PR body at completion gate.

    This is the completion-gate layer over ADR-029's contract; the PreToolUse
    hook at ``.claude/hooks/block-defer-language.py`` is the tool-call-time
    layer. The PR body is treated as a ``pr-body`` surface — Tier 1 deferral
    phrases are flagged; the ``## Out of scope`` section heading and
    sibling-PR references are not (Tier 2 is not applied to ``pr-body``).
    Text scanning cannot prove a *silently dropped* finding — that remains the
    province of the issue-thread findings-vs-decisions record (ADR-029); this
    check only catches deferral language that was actually written.

    ``root`` is accepted for signature symmetry with the other ``run_*`` checks
    and is unused here.
    """
    del root  # signature symmetry; this check operates purely on the PR body text
    if pr_body is None:
        return []
    decision, pattern = classify_deferral_language(pr_body, "pr-body")
    if decision == "deny":
        return [
            Violation(
                code="pr-body-deferral-disposition",
                message="PR body contains deferral-disposition language (ADR-029 / #830).",
                details=[
                    f"matched pattern: {pattern}",
                    _DEFERRAL_DENIAL_GUIDANCE,
                ],
            )
        ]
    return []


DEPLOY_COMPOSE_PROD_PATH = Path("deploy/docker/docker-compose.prod.yml")
REQUIRED_ADR026_CREDENTIAL_SLOT_COUNT = 5
REQUIRED_ADR026_ALLOWLIST_SLOT_COUNT = 5


def _required_adr026_backend_env_keys() -> tuple[tuple[str, ...], tuple[str, ...]]:
    """Return the (always-set, inherit-only) ADR-026 keys.

    Always-set keys are typed config (security toggles); list or map form is
    fine for them because their defaults are guaranteed non-empty. Inherit-only
    keys are the optional indexed credential / allowlist slots — those MUST
    use bare list form (``- KEY`` with no ``=``) so unset host variables
    are NOT injected as empty strings (Spring's
    ``SecurityProperties.validate()`` rejects blank principal/token/role/CIDR
    entries and the container fails startup).
    """
    always_set: list[str] = ["GC_SECURITY_ENABLED", "GC_SECURITY_OPENAPI_PUBLIC"]
    inherit_only: list[str] = []
    for index in range(REQUIRED_ADR026_CREDENTIAL_SLOT_COUNT):
        inherit_only.append(f"GROUNDCONTROL_SECURITY_CREDENTIALS_{index}_PRINCIPAL_NAME")
        inherit_only.append(f"GROUNDCONTROL_SECURITY_CREDENTIALS_{index}_TOKEN")
        inherit_only.append(f"GROUNDCONTROL_SECURITY_CREDENTIALS_{index}_ROLE")
    for index in range(REQUIRED_ADR026_ALLOWLIST_SLOT_COUNT):
        inherit_only.append(f"GROUNDCONTROL_SECURITY_IP_ALLOWLIST_{index}")
    return tuple(always_set), tuple(inherit_only)


(
    REQUIRED_ADR026_ALWAYS_SET_KEYS,
    REQUIRED_ADR026_INHERIT_ONLY_KEYS,
) = _required_adr026_backend_env_keys()
REQUIRED_ADR026_BACKEND_ENV_KEYS: tuple[str, ...] = (
    *REQUIRED_ADR026_ALWAYS_SET_KEYS,
    *REQUIRED_ADR026_INHERIT_ONLY_KEYS,
)
COMPOSE_ENV_KEY_RE = re.compile(r"^\s*-\s*([A-Z][A-Z0-9_]*)(?:=.*)?\s*$|^\s*([A-Z][A-Z0-9_]*)\s*:.*$")
COMPOSE_ENV_INHERIT_FORM_RE = re.compile(r"^\s*-\s*([A-Z][A-Z0-9_]*)\s*$")


@dataclass
class Violation:
    code: str
    message: str
    details: list[str]

    def render(self) -> str:
        if not self.details:
            return f"[{self.code}] {self.message}"
        formatted = "\n".join(f"  - {detail}" for detail in self.details)
        return f"[{self.code}] {self.message}\n{formatted}"


def normalize_path(path: str) -> str:
    return Path(path).as_posix().lstrip("./")


def run_git(args: list[str], root: Path = REPO_ROOT) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def read_changed_files(
    *,
    files: Iterable[str] | None = None,
    base: str | None = None,
    staged: bool = False,
    env_var: str | None = None,
    root: Path = REPO_ROOT,
) -> list[str]:
    if files:
        return sorted({normalize_path(path) for path in files if path})
    if env_var:
        raw = os.getenv(env_var, "")
        return sorted({normalize_path(path) for path in raw.splitlines() if path.strip()})
    if base:
        output = run_git(["diff", "--name-only", "--diff-filter=ACDMRTUXB", base, "--"], root=root)
        return sorted({normalize_path(path) for path in output.splitlines() if path.strip()})
    if staged:
        output = run_git(["diff", "--cached", "--name-only", "--diff-filter=ACDMRTUXB", "--"], root=root)
        return sorted({normalize_path(path) for path in output.splitlines() if path.strip()})

    tracked = run_git(["diff", "--name-only", "--diff-filter=ACDMRTUXB", "HEAD", "--"], root=root)
    untracked = run_git(["ls-files", "--others", "--exclude-standard"], root=root)
    combined = tracked.splitlines() + untracked.splitlines()
    return sorted({normalize_path(path) for path in combined if path.strip()})


def matches_any(path: str, patterns: Iterable[str]) -> bool:
    return any(fnmatch.fnmatch(path, pattern) for pattern in patterns)


def filter_matches(paths: Iterable[str], patterns: Iterable[str]) -> list[str]:
    return sorted({path for path in paths if matches_any(path, patterns)})


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def get_repo_relative_files(root: Path, glob_pattern: str) -> list[str]:
    return sorted(
        normalize_path(str(path.relative_to(root)))
        for path in root.glob(glob_pattern)
        if path.is_file()
    )


def run_adr_guard(changed_files: list[str], root: Path = REPO_ROOT) -> list[Violation]:
    policy = load_json(ADR_POLICY_PATH)
    violations: list[Violation] = []

    for policy_entry in policy["policies"]:
        for rule in policy_entry.get("rules", []):
            triggers = filter_matches(changed_files, rule.get("whenAny", []))
            if not triggers:
                continue

            missing_all = [
                required
                for required in rule.get("requireAll", [])
                if required not in changed_files
            ]
            missing_any = []
            require_any = rule.get("requireAny", [])
            if require_any and not any(required in changed_files for required in require_any):
                missing_any.append(f"one of: {', '.join(require_any)}")

            if missing_all or missing_any:
                details = [f"triggered by: {', '.join(triggers)}"]
                if missing_all:
                    details.append(f"missing required file updates: {', '.join(missing_all)}")
                details.extend(missing_any)
                violations.append(
                    Violation(
                        code=rule["id"],
                        message=rule["message"],
                        details=details,
                    )
                )

    return violations


def run_controller_contracts(changed_files: list[str], root: Path = REPO_ROOT) -> list[Violation]:
    controllers = [path for path in changed_files if CONTROLLER_PATH_RE.match(path)]
    if not controllers:
        return []

    violations: list[Violation] = []
    required_changed = []
    for required in ("docs/API.md", "mcp/ground-control/lib.js", "mcp/ground-control/index.js"):
        if required not in changed_files:
            required_changed.append(required)
    if required_changed:
        violations.append(
            Violation(
                code="controller-parity",
                message="Controller changes require API docs and MCP parity updates.",
                details=[
                    f"controllers changed: {', '.join(controllers)}",
                    f"missing companion updates: {', '.join(required_changed)}",
                ],
            )
        )

    for controller in controllers:
        test_name = f"{Path(controller).stem}Test.java"
        matching_test_updates = [path for path in changed_files if path.endswith(f"/{test_name}")]
        if not matching_test_updates:
            violations.append(
                Violation(
                    code="controller-webmvctest-update",
                    message="Controller changes require a matching @WebMvcTest update.",
                    details=[f"missing changed test for {controller}: expected {test_name}"],
                )
            )
            continue

        test_paths = get_repo_relative_files(root, f"backend/src/test/java/**/{test_name}")
        if not test_paths:
            violations.append(
                Violation(
                    code="controller-webmvctest-missing",
                    message="Controller is missing a matching @WebMvcTest class.",
                    details=[f"expected test file {test_name} for {controller}"],
                )
            )
            continue

        for test_path in test_paths:
            content = (root / test_path).read_text(encoding="utf-8")
            if "@WebMvcTest(" not in content:
                violations.append(
                    Violation(
                        code="controller-webmvctest-annotation",
                        message="Controller test exists but is not a @WebMvcTest.",
                        details=[f"{test_path} must use @WebMvcTest for {controller}"],
                    )
                )

    return violations


def git_diff_for_paths(paths: Iterable[str], root: Path = REPO_ROOT) -> str:
    path_list = [normalize_path(path) for path in paths]
    if not path_list:
        return ""
    return run_git(["diff", "--unified=0", "HEAD", "--", *path_list], root=root)


def run_migration_policy(changed_files: list[str], root: Path = REPO_ROOT) -> list[Violation]:
    migrations = [path for path in changed_files if MIGRATION_PATH_RE.match(path)]
    violations: list[Violation] = []

    if migrations:
        required = [
            "backend/src/test/java/com/keplerops/groundcontrol/integration/MigrationSmokeTest.java",
            "backend/src/test/java/com/keplerops/groundcontrol/integration/RequirementsE2EIntegrationTest.java",
        ]
        missing = [path for path in required if path not in changed_files]
        if missing:
            violations.append(
                Violation(
                    code="migration-smoke-sync",
                    message="Migration changes require the hardcoded integration version lists to be updated.",
                    details=[
                        f"migrations changed: {', '.join(migrations)}",
                        f"missing companion updates: {', '.join(missing)}",
                    ],
                )
            )

    java_files = [path for path in changed_files if path.endswith(".java")]
    diff = git_diff_for_paths(java_files, root=root)
    audited_files: set[str] = set()
    current_file = ""
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current_file = normalize_path(line.removeprefix("+++ b/"))
        elif line.startswith("+") and not line.startswith("+++") and "@Audited" in line and current_file:
            audited_files.add(current_file)

    if audited_files and not migrations:
        violations.append(
            Violation(
                code="audited-entity-migration",
                message="Adding @Audited requires matching Flyway migration updates.",
                details=[
                    f"@Audited added in: {', '.join(sorted(audited_files))}",
                    "expected at least one db/migration/V*.sql change in the same diff",
                ],
            )
        )

    return violations


def _extract_compose_backend_env_entries(text: str) -> dict[str, str]:
    """Extract environment entries declared on the `backend` service.

    Returns a mapping of key → form, where form is one of:
      ``"inherit"`` — list shorthand ``- KEY`` (no value, host-inheritance only).
      ``"list-value"`` — list form with explicit value ``- KEY=...``.
      ``"map"``    — map form ``KEY: ...``.

    Compose allows both list-form (``- KEY=VALUE`` / ``- KEY``) and map-form
    (``KEY: VALUE``) under ``environment:``; honor either. A handwritten
    indentation walker is intentional here — adding a PyYAML dependency for
    one check would make ``make policy`` fail with ``ModuleNotFoundError`` on
    a clean Python installation, since the rest of ``tools/policy/`` is
    stdlib-only.
    """
    found: dict[str, str] = {}
    in_backend = False
    backend_indent = -1
    in_environment = False
    env_indent = -1
    for raw_line in text.splitlines():
        stripped = raw_line.lstrip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw_line) - len(stripped)

        # Track sibling-out-of-block exits before recognizing new starts.
        if in_environment and indent <= env_indent:
            in_environment = False
        if in_backend and indent <= backend_indent and not stripped.startswith("backend:"):
            in_backend = False

        if stripped.startswith("backend:"):
            in_backend = True
            backend_indent = indent
            in_environment = False
            continue
        if in_backend and stripped.startswith("environment:") and not in_environment:
            in_environment = True
            env_indent = indent
            continue
        if in_environment:
            inherit_match = COMPOSE_ENV_INHERIT_FORM_RE.match(raw_line)
            if inherit_match:
                found.setdefault(inherit_match.group(1), "inherit")
                continue
            match = COMPOSE_ENV_KEY_RE.match(raw_line)
            if match:
                key = match.group(1) or match.group(2)
                form = "list-value" if match.group(1) is not None else "map"
                found.setdefault(key, form)
    return found


# ---------------------------------------------------------------------------
# Changelog-fragment workflow (issue #848, ADR-021 Phase B amendment).
#
# Ground-Control routes per-PR changelog entries through towncrier-style
# fragments under `changelog.d/` instead of direct `CHANGELOG.md` edits so
# concurrent PRs cannot conflict on the same line range. Two structural
# gates back the convention here:
#
#   1. `parse_fragment_filename` — parses a fragment file's basename against
#      a closed vocabulary. Accepts `<digits>.<type>.md` (issue-anchored) or
#      `+<slug>.<type>.md` (issue-free), where `<type>` is one of the six
#      Keep-a-Changelog categories. Anything else returns ``None``. This is
#      not a substring test against fragment prose — it is a parser over a
#      fixed grammar, which is the kind of structural gate the documentation
#      carve-out at SKILL Step 4.4 requires when the diff is otherwise doc.
#
#   2. `run_changelog_fragment_check` — completion-gate enforcement. Two
#      independent rules:
#        - Together-ness: if `changelog.d/` exists in the repo, the
#          canonical infrastructure files (`towncrier.toml`,
#          `changelog.d/_template.md.jinja`, `changelog.d/README.md`) must
#          all exist. A repo that ships `changelog.d/` without those is
#          broken (towncrier won't run).
#        - Diff signal: if a diff touches application source, it MUST carry
#          a valid fragment under `changelog.d/`. Direct `CHANGELOG.md`
#          edits do NOT satisfy a source-changing diff — accepting them
#          would re-open the rebase-storm pathology this convention exists
#          to prevent (codex review finding, issue #848). Release-collation
#          commits (`towncrier build`) touch `CHANGELOG.md` and delete the
#          fragments they consumed, neither of which is application source,
#          so they fall through the predicate naturally. CI-only and
#          docs-only diffs likewise carry no source paths and require no
#          signal.
#
# The same vocabulary is mirrored in
# `.claude/hooks/verify-implementation.sh` (host-local Stop hook) so the
# repo-native check and the user-level hook agree on what counts.
# ---------------------------------------------------------------------------

CHANGELOG_FRAGMENT_TYPES: tuple[str, ...] = (
    "security",
    "added",
    "changed",
    "deprecated",
    "removed",
    "fixed",
)

_FRAGMENT_INFRASTRUCTURE_FILES: tuple[str, ...] = (
    "towncrier.toml",
    "changelog.d/_template.md.jinja",
    "changelog.d/README.md",
)

# Reserved files inside `changelog.d/` that are infrastructure, not fragments.
# Towncrier itself reads `_template.md.jinja`; `README.md` documents the
# convention. Neither should be parsed by `parse_fragment_filename`.
_FRAGMENT_RESERVED_NAMES: frozenset[str] = frozenset({"README.md", "_template.md.jinja"})

# Filename grammar:
#   <issue>   ::= 1+ ASCII digit
#   <slug>    ::= "+" then 1+ chars from [a-zA-Z0-9._-]
#   filename  ::= (<issue> | <slug>) "." <type> ".md"
_FRAGMENT_ISSUE_RE = re.compile(r"^(\d+)\.([a-z]+)\.md$")
_FRAGMENT_SLUG_RE = re.compile(r"^(\+[A-Za-z0-9][A-Za-z0-9._-]*)\.([a-z]+)\.md$")

# Path prefixes for diffs that count as "application source" for the
# diff-signal rule. Anything under these prefixes requires a changelog
# signal; anything outside (docs, ADRs, skills prose, plan-rules,
# `.github/workflows/`, repo metadata, tests-for-policy-tooling) does not.
_SOURCE_PATH_PREFIXES: tuple[str, ...] = (
    "backend/src/main/",
    "backend/src/test/",
    "frontend/src/",
    "mcp/",
)

# `tools/` mostly carries policy tooling (which is itself infrastructure for
# the workflow rather than application source). Subdirectories of `tools/`
# that exist purely to support `bin/policy` and its tests are not counted
# as "application source" for the diff-signal rule.
_TOOLS_NON_SOURCE_PREFIXES: tuple[str, ...] = (
    "tools/policy/",
    "tools/tests/",
)


def parse_fragment_filename(name: str) -> tuple[str, str] | None:
    """Parse a fragment filename against the convention vocabulary.

    Returns ``(stem, type)`` for a well-formed fragment name, or ``None``
    otherwise. The grammar is intentionally narrow: anything that doesn't
    match exactly is rejected so towncrier can't silently skip a
    misspelled fragment a contributor thought they had filed.

    Reserved names inside ``changelog.d/`` (``README.md`` and the Jinja
    template) are not fragments — they return ``None`` too.
    """
    if name in _FRAGMENT_RESERVED_NAMES:
        return None

    issue_match = _FRAGMENT_ISSUE_RE.match(name)
    if issue_match:
        stem, ftype = issue_match.group(1), issue_match.group(2)
        if ftype in CHANGELOG_FRAGMENT_TYPES:
            return (stem, ftype)
        return None

    slug_match = _FRAGMENT_SLUG_RE.match(name)
    if slug_match:
        stem, ftype = slug_match.group(1), slug_match.group(2)
        # Reject the bare ``+`` slug — the slug body must be non-empty.
        if stem == "+":
            return None
        if ftype in CHANGELOG_FRAGMENT_TYPES:
            return (stem, ftype)
        return None

    return None


def _diff_touches_application_source(changed_files: Iterable[str]) -> bool:
    for path in changed_files:
        normalized = normalize_path(path)
        if normalized.startswith(_SOURCE_PATH_PREFIXES):
            return True
        if normalized.startswith("tools/") and not normalized.startswith(
            _TOOLS_NON_SOURCE_PREFIXES
        ):
            return True
    return False


def run_changelog_fragment_check(
    changed_files: list[str], root: Path = REPO_ROOT
) -> list[Violation]:
    violations: list[Violation] = []

    changelog_d_dir = root / "changelog.d"
    if changelog_d_dir.is_dir():
        missing = [
            rel
            for rel in _FRAGMENT_INFRASTRUCTURE_FILES
            if not (root / rel).exists()
        ]
        if missing:
            violations.append(
                Violation(
                    code="changelog-fragment-infrastructure",
                    message=(
                        "changelog.d/ exists but the canonical fragment "
                        "infrastructure is incomplete (towncrier will not run)."
                    ),
                    details=[f"missing: {', '.join(missing)}"],
                )
            )

    # Validate any fragments staged in this diff. A fragment with a bad
    # filename is invisible to towncrier, so the contributor would think
    # they had filed an entry that never gets collated.
    #
    # The signal predicate is "fragment file exists in the working tree
    # AFTER the diff applies" — not "any valid-looking fragment path is
    # named anywhere in the diff". `read_changed_files` now includes
    # deletions (filter `ACDMRTUXB`), so a release-collation commit that
    # deletes `changelog.d/old.added.md` will list that path in
    # `changed_files`; without the on-disk check, that deleted path would
    # count as a "signal" for an unrelated source change in the same PR.
    fragments_in_diff: list[str] = []
    invalid_fragment_names: list[str] = []
    for path in changed_files:
        normalized = normalize_path(path)
        if not normalized.startswith("changelog.d/"):
            continue
        relative = normalized[len("changelog.d/") :]
        if relative in _FRAGMENT_RESERVED_NAMES:
            continue
        # Nested paths (`changelog.d/foo/848.added.md`) are NOT part of the
        # convention — towncrier won't consume them — and silently skipping
        # them would let a contributor file a fragment that never lands.
        # Treat them as invalid so the violation surfaces in `make policy`.
        if "/" in relative:
            invalid_fragment_names.append(normalized)
            continue
        parsed = parse_fragment_filename(relative)
        if parsed is None:
            invalid_fragment_names.append(normalized)
        elif (root / normalized).is_file():
            fragments_in_diff.append(normalized)
        # Else: parsed correctly but absent from the working tree —
        # i.e. the fragment was deleted. Deleted fragments do not count
        # as a release-notes signal.

    if invalid_fragment_names:
        violations.append(
            Violation(
                code="changelog-fragment-invalid-name",
                message=(
                    "Changelog fragment filename does not match the convention "
                    "<issue>.<type>.md or +<slug>.<type>.md where <type> is one "
                    f"of {', '.join(CHANGELOG_FRAGMENT_TYPES)}."
                ),
                details=[f"invalid: {name}" for name in invalid_fragment_names],
            )
        )

    # Diff-signal rule: source-changing diff MUST carry a valid fragment
    # under `changelog.d/`. A direct `CHANGELOG.md` edit is intentionally
    # NOT a substitute for source diffs — that branch would re-open the
    # rebase-storm pathology this convention exists to prevent. Release-
    # collation commits touch `CHANGELOG.md` and the fragments they
    # consume, neither of which counts as application source, so they
    # fall through the predicate and need no fragment signal.
    if _diff_touches_application_source(changed_files):
        if not fragments_in_diff:
            violations.append(
                Violation(
                    code="changelog-signal-missing",
                    message=(
                        "Source-changing diff has no valid changelog fragment "
                        "under changelog.d/. Add a fragment named "
                        "<issue>.<type>.md (or +<slug>.<type>.md for "
                        "issue-free entries), type in "
                        f"{{{','.join(CHANGELOG_FRAGMENT_TYPES)}}}. Editing "
                        "CHANGELOG.md directly is reserved for release "
                        "collation and does not satisfy this gate. See "
                        "changelog.d/README.md."
                    ),
                    details=[],
                )
            )

    return violations


def run_deploy_compose_credential_passthrough(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert the production compose file enumerates ADR-026 credential env vars.

    #828 was triggered because the operator filled `GROUNDCONTROL_SECURITY_*`
    values into `/opt/gc/.env` but `deploy/docker/docker-compose.prod.yml` did
    not list them on the backend service's `environment:` block, so docker
    compose never propagated them into the container. The first deploy of the
    ADR-026 image therefore 401'd every consumer. The check below is a static
    post-condition — independent of `changed_files` — so any future diff that
    silently strips one of the required keys fails `make policy`. All five
    documented credential slots and all five allowlist slots must remain
    enumerated; partial removal is itself the regression.
    """
    compose_path = root / DEPLOY_COMPOSE_PROD_PATH
    if not compose_path.exists():
        return [
            Violation(
                code="deploy-compose-missing",
                message=(
                    "Canonical production compose file is missing — ADR-026 "
                    "credential passthrough cannot be verified."
                ),
                details=[f"expected at {DEPLOY_COMPOSE_PROD_PATH.as_posix()}"],
            )
        ]

    text = compose_path.read_text(encoding="utf-8")
    entries = _extract_compose_backend_env_entries(text)

    violations: list[Violation] = []

    missing = [key for key in REQUIRED_ADR026_BACKEND_ENV_KEYS if key not in entries]
    if missing:
        violations.append(
            Violation(
                code="deploy-compose-adr026-passthrough",
                message=(
                    "deploy/docker/docker-compose.prod.yml backend service is "
                    "missing ADR-026 credential env-var passthroughs (GC-P011)."
                ),
                details=[f"missing keys: {', '.join(missing)}"],
            )
        )

    # Indexed credential / allowlist slots MUST use bare list form (- KEY).
    # Map form with ${VAR:-} or list-with-value form ${VAR:-} both inject the
    # variable into the container as an empty string when the host variable
    # is unset, which Spring's SecurityProperties.validate() then rejects —
    # exactly the brittle path #828 cycle 1 surfaced. Bare list form inherits
    # only when the host has the variable set.
    wrong_form = [
        key
        for key in REQUIRED_ADR026_INHERIT_ONLY_KEYS
        if key in entries and entries[key] != "inherit"
    ]
    if wrong_form:
        violations.append(
            Violation(
                code="deploy-compose-adr026-inherit-only",
                message=(
                    "Optional ADR-026 credential / allowlist slots in "
                    "deploy/docker/docker-compose.prod.yml must use bare list form "
                    "(- KEY) so unset host variables are not injected as blank "
                    "(GC-P011 / SecurityProperties.validate)."
                ),
                details=[f"keys not in inherit-only form: {', '.join(wrong_form)}"],
            )
        )

    return violations


# ---------------------------------------------------------------------------
# API enum contract check (issue #433, ADR-034).
#
# The backend Java enums under domain/requirements/state/ are the single source
# of truth for the requirement/traceability enum vocabularies. Every such enum
# that is mirrored at the API boundary — frontend/src/types/api.ts (union types
# and, where the UI iterates them, constant arrays) and mcp/ground-control/lib.js
# constants — is listed in ENUM_CONTRACT_INVENTORY. Earlier the frontend carried
# impossible values (PERFORMANCE, GITHUB_PR, TRACES_TO, ...) and then, after a
# partial fix, the inverse drift (ArtifactType missing PULL_REQUEST /
# RISK_SCENARIO / CONTROL; SyncStatus still typed SYNCED/NOT_SYNCED/ERROR while
# the backend has SYNCED/STALE/BROKEN). This static post-condition parses the
# Java enum sources and asserts every mirror matches — so the next diff that lets
# them diverge fails `make policy` (the `policy` CI job runs `bin/policy` on every
# PR). Adding another mirrored enum is one inventory row, not new parsing logic.
# (ADR-017 contemplates OpenAPI-generated TypeScript types; until that exists this
# source extractor is the authoritative contract — see ADR-034. The frontend
# vitest mirror in enum-contract.test.ts is a developer convenience, not the CI
# gate, because the frontend test suite does not run in PR CI today.)
# ---------------------------------------------------------------------------

FRONTEND_API_TYPES_PATH = "frontend/src/types/api.ts"
MCP_LIB_PATH = "mcp/ground-control/lib.js"
_ENUM_STATE_DIR = "backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/state"
_AUDIT_ENUM_STATE_DIR = "backend/src/main/java/com/keplerops/groundcontrol/domain/audits/state"

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
    if mcp_path.exists():
        mcp_text = mcp_path.read_text(encoding="utf-8")
    else:
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


def run_pr_body_check(event_path: Path) -> list[Violation]:
    """Backwards-compatible wrapper that loads the PR body from a GitHub event payload."""
    event = json.loads(event_path.read_text(encoding="utf-8"))
    pull_request = event.get("pull_request") or {}
    body = pull_request.get("body") or ""
    return check_pr_body(body)


def check_pr_body(body: str) -> list[Violation]:
    """Validate a PR body against the Ground Control template requirements.

    Pure function over the body string so it can be driven from GitHub event
    payloads (CI), a local draft file (pre-push hook), or `gh pr view --json
    body`. The CI path is `run_pr_body_check`; local tooling should call this
    directly.
    """
    violations: list[Violation] = []

    required_headers = [
        "## Requirement UIDs",
        "## ADR Impact",
        "## Ground Control Checks",
        "## Traceability",
    ]
    missing_headers = [header for header in required_headers if header not in body]
    if missing_headers:
        violations.append(
            Violation(
                code="pr-template-sections",
                message="PR body is missing required Ground Control sections.",
                details=[f"missing headers: {', '.join(missing_headers)}"],
            )
        )
        return violations

    if not PR_REQUIREMENT_RE.search(body):
        violations.append(
            Violation(
                code="pr-requirement-uid",
                message="PR body must name at least one requirement UID.",
                details=["expected a UID like GC-O007 in the Requirement UIDs section"],
            )
        )

    if "No ADR required" not in body and "ADR-" not in body:
        violations.append(
            Violation(
                code="pr-adr-impact",
                message="PR body must call out ADR impact or say 'No ADR required'.",
                details=[],
            )
        )

    required_checks = [
        "- [x] `make policy` passes",
        "- [x] `gc_evaluate_quality_gates` passes or is unchanged by this repo-only change",
        "- [x] `gc_run_sweep` reviewed; findings fixed or recorded with rationale",
    ]
    missing_checks = [entry for entry in required_checks if entry not in body]
    if missing_checks:
        violations.append(
            Violation(
                code="pr-ground-control-checks",
                message="PR body must record the Ground Control verification checklist.",
                details=missing_checks,
            )
        )

    traceability_markers = ["- IMPLEMENTS:", "- TESTS:"]
    missing_traceability = [marker for marker in traceability_markers if marker not in body]
    if missing_traceability:
        violations.append(
            Violation(
                code="pr-traceability-summary",
                message="PR body must summarize IMPLEMENTS and TESTS traceability.",
                details=missing_traceability,
            )
        )

    # The no-deferral check is composed into the PR-body validator so EVERY
    # PR-body validation route — bin/policy main(), run_pr_body_check (the
    # GitHub-event-payload path / bin/check-pr-body), and a direct
    # check_pr_body(body) call — enforces ADR-029's contract, not just main().
    violations.extend(run_no_deferral_disposition_check(pr_body=body))

    return violations


# ---------------------------------------------------------------------------
# Test-quality decision-record contract (issue #884; step moved by #906).
#
# The `/implement` test-quality review used to halt the workflow when the
# `review-tests` skill returned a clean cycle, because the workflow contract
# was prose-only: there was no structured signal the parent agent could
# branch on to advance without a user turn. The fix is to reuse the existing
# `gc_post_decision_record` contract uniformly across the codex review (Step
# 6.5) and the test-quality review (Step 6.6 per #906; formerly Step 13):
# every cycle ends with a decision-record post carrying
# `reviewer: "test-quality"` and the findings list (empty for a clean
# cycle). A clean record IS the advance signal.
#
# `run_test_quality_decision_record_contract` enforces that the SKILL prose
# continues to mandate that contract. It parses the test-quality section out
# of the SKILL body and checks for the structural tokens that encode the
# contract — the canonical tool name, the test-quality reviewer literal,
# the empty-findings clean cycle case, and a continuation signal. This is
# the same "parser over a fixed vocabulary" structural-gate pattern as
# `run_changelog_fragment_check`, not a snapshot of specific prose. Wording
# can change; the contract cannot. Section heading the check looks for is
# `### Step 6.6: Pre-push Test-Quality Review` (the #906 placement). If the
# step is ever renumbered again, update the `extract_step_section` call
# below — the contract substance is independent of the step number.
# ---------------------------------------------------------------------------

IMPLEMENT_SKILL_PATH = "skills/implement/SKILL.md"
# After issue #934 the monolithic SKILL.md is a thin orchestrator and the
# per-step prose lives at `skills/implement/steps/step-NN-<id>.md`. The
# test-quality contract check reads from the step file when the
# orchestrator no longer carries the Step 6.6 heading inline.
IMPLEMENT_STEP_TEST_QUALITY_PATH = (
    "skills/implement/steps/step-06.6-test-quality-review.md"
)

# Matches a Markdown step heading at any level (`#` through `####`) whose
# text starts with the given step label (e.g. `Step 13`). Captures the
# heading line so the splitter knows where the section begins. The H1
# branch covers per-step files split out of SKILL.md by issue #934 — each
# step file uses an H1 step heading rather than the H3 used inline in
# the monolithic SKILL.
_STEP_HEADING_RE = re.compile(r"^#{1,4}\s+(Step\s+\d+(?:\.\d+)?)\b.*$", re.MULTILINE)


def extract_step_section(body: str, step_label: str) -> str | None:
    """Return the body of a Markdown `### Step N: ...` section, or None.

    The section runs from its heading up to the next step heading of any
    level (or end of file). Returns None when the step is not present.
    """
    headings = list(_STEP_HEADING_RE.finditer(body))
    target_idx = None
    for i, match in enumerate(headings):
        if match.group(1).strip() == step_label:
            target_idx = i
            break
    if target_idx is None:
        return None
    start = headings[target_idx].end()
    end = headings[target_idx + 1].start() if target_idx + 1 < len(headings) else len(body)
    return body[start:end]


def run_test_quality_decision_record_contract(
    *,
    text: str | None = None,
    root: Path = REPO_ROOT,
) -> list[Violation]:
    """Assert the test-quality review step mandates the decision-record contract.

    The contract is: every test-quality cycle ends with a
    `gc_post_decision_record` post carrying `reviewer: "test-quality"` and
    the findings list, and a clean cycle (`findings: []`) is the structured
    advance signal (no user acknowledgment turn).

    Section heading the check looks for is `### Step 6.6: ...` (the #906
    placement; the step was at `### Step 13: ...` from #884 v2 until #906
    moved it pre-push).

    Pass `text=` directly to validate a fixture string. With no `text`, the
    check reads `skills/implement/SKILL.md` from `root`.

    Emits:
      ``test-quality-section-missing``       — `### Step 6.6: ...` heading absent.
      ``test-quality-decision-record-contract`` — section present, contract not.
    """
    if text is None:
        # The Step 6.6 section moved from inline in SKILL.md to its own
        # per-step file under skills/implement/steps/ when issue #934
        # split the monolithic SKILL into a thin orchestrator + per-step
        # prose. Prefer reading from the orchestrator's per-step file
        # directly; fall back to the orchestrator itself (covering both
        # the new layout and the older inline layout).
        step_path = root / IMPLEMENT_STEP_TEST_QUALITY_PATH
        skill_path = root / IMPLEMENT_SKILL_PATH
        if step_path.exists():
            text = step_path.read_text(encoding="utf-8")
        elif skill_path.exists():
            text = skill_path.read_text(encoding="utf-8")
        else:
            return [
                Violation(
                    code="test-quality-skill-missing",
                    message=(
                        f"Neither {IMPLEMENT_STEP_TEST_QUALITY_PATH} nor "
                        f"{IMPLEMENT_SKILL_PATH} exists — test-quality "
                        "contract cannot be verified."
                    ),
                    details=[
                        f"expected at {IMPLEMENT_STEP_TEST_QUALITY_PATH}",
                        f"or at {IMPLEMENT_SKILL_PATH}",
                    ],
                )
            ]

    section = extract_step_section(text, "Step 6.6")
    if section is None:
        return [
            Violation(
                code="test-quality-section-missing",
                message="`Step 6.6: ...` section is missing from the test-quality contract source.",
                details=[
                    "Step 6.6 is the pre-push test-quality review phase "
                    "(moved from former Step 13 by issue #906); the section "
                    "must exist and must mandate the gc_post_decision_record "
                    "contract per issue #884. After issue #934 the section "
                    f"lives at {IMPLEMENT_STEP_TEST_QUALITY_PATH}.",
                ],
            )
        ]

    violations: list[Violation] = []

    # Anti-contract patterns: prose that would re-introduce the #884
    # regression in a different shape. Substring/regex presence of any of
    # these in Step 13 fails the check regardless of which positive tokens
    # are also present. Each pattern is intentionally specific — the goal
    # is "this exact failure mode," not a broad ban on the words.
    #
    # Negation guard. Some anti-patterns look like "skip ... decision
    # record" or "do not advance to Step 14". These same word sequences
    # appear in *correct* guardrail prose ("do not skip the decision
    # record", "on `ok: false`, do not advance to Step 14"). Without a
    # guard, the check rejects the prose it is supposed to require.
    # _ANTI_NEGATION_PRECEDES_RE matches a negator within ~60 chars
    # immediately before the matched anti-pattern; when present the match
    # is skipped. Negators recognized:
    #   "do(es) not ..." / "don't" / "must not"
    #   "never" (when used as a directive: "must never", "agents never")
    #   "on `ok: false`,"  (the success-precondition context)
    #   "until `ok: true`,"
    #   "unless ..." (defensive — usually scopes a permitted exception)
    # The window is small enough that legitimate anti-contract prose
    # ("Skip the decision record on clean cycles") which lacks an
    # immediately-preceding negator still matches.
    # Anti-pattern windows allow newlines (anti-contract prose often wraps
    # across multiple lines in a numbered list); they stop at a sentence
    # boundary (`.`) and are length-capped so they cannot span paragraphs.
    anti_patterns: tuple[tuple[str, str], ...] = (
        (
            "do-not-call-post-record",
            # "do not call/post/invoke gc_post_decision_record"
            r"(?is)\bdo(?:es)?\s+not\s+(?:call|post|invoke)\b"
            r"[^.]{0,120}?\bgc_post_decision_record\b",
        ),
        (
            "skip-decision-record",
            # "skip the decision record / skip posting decision record on clean"
            r"(?is)\bskip(?:ping)?\b[^.]{0,60}?\bdecision[-\s]?record\b",
        ),
        (
            "do-not-proceed-step14",
            # "do not proceed to Step 14" / "do not advance to Step 14"
            r"(?is)\bdo(?:es)?\s+not\s+(?:proceed|advance|continue)\b"
            r"[^.]{0,120}?\bstep\s*14\b",
        ),
        (
            "findings-empty-not-enough",
            # "`findings: []` is not enough / does not suffice / is insufficient"
            r"(?is)`?findings:\s*\[\]`?[^.]{0,60}?"
            r"(?:is\s+not\s+enough|does\s+not\s+suffice|is\s+insufficient|"
            r"is\s+not\s+sufficient)",
        ),
        (
            "require-user-acknowledgment-turn",
            # "wait for the user to acknowledge" + Step 14 / clean cycle context
            r"(?is)\bwait\s+for\s+(?:the\s+)?user\b"
            r"[^.]{0,120}?(?:acknowledg|sign[-\s]?off|approval|confirm)"
            r"[^.]{0,120}?(?:clean|step\s*14)",
        ),
        (
            "findings-routed-to-user",
            # The exact failure mode the user reported after #884 v1
            # shipped: parent treats returned findings as a status report
            # to the user instead of work to fix in the same turn.
            # Patterns: "echo / report / return / hand findings to the
            # user" (literal) and "echo them to the user" (pronoun).
            # The verb is paired with a "to/back to/for ... user/human"
            # phrase to distinguish it from the legitimate
            # "return control to the parent" prose. The pronouns "them"
            # / "it" are accepted because the noun "findings" often sits
            # in the preceding clause ("when findings are returned, echo
            # them to the user"). The whole point of the review is to
            # fix the tests; surfacing findings to the user defeats that
            # contract.
            r"(?is)\b(?:echo|report|return|hand|surface|present|forward)\b"
            r"[^.]{0,120}?\b(?:findings|them|it)\b"
            r"[^.]{0,80}?\b(?:to|back\s+to|for)\s+(?:the\s+)?(?:user|human)\b",
        ),
    )
    # Negation guard: scan the ~60 chars immediately preceding each match
    # for any of the negator phrases listed above. Cap at 60 because real
    # negation usually sits in the same clause as the verb it negates;
    # widening the window invites false negatives where unrelated negators
    # appear earlier in the paragraph.
    negation_window_re = re.compile(
        r"(?is)\b(?:do(?:es)?\s+not|don['’]t|must\s+not|"
        r"(?:must|may|should|shall|agents?)\s+never|never\s+skip|"
        r"on\s+`?ok\s*:\s*false`?|until\s+`?ok\s*:\s*true`?|unless)\b"
    )
    matched_anti: list[str] = []
    for code, pattern in anti_patterns:
        for match in re.finditer(pattern, section):
            window_start = max(0, match.start() - 60)
            preceding = section[window_start:match.start()]
            if negation_window_re.search(preceding):
                continue
            matched_anti.append(code)
            break
    if matched_anti:
        violations.append(
            Violation(
                code="test-quality-anti-contract-prose",
                message=(
                    "Step 6.6 (test-quality review) contains anti-contract prose "
                    "that re-introduces the #884 silent-advance failure mode."
                ),
                details=[f"matched anti-contract pattern: {code}" for code in matched_anti],
            )
        )

    missing: list[str] = []
    # Per #884 v2: Step 13 must invoke the MCP tool (`gc_test_quality_review`),
    # not the legacy `Skill("review-tests")` boundary. The Skill-tool
    # boundary's autoregressive bias was the root cause of the #884 v1
    # regression (parent agent echoed prose findings back to the user
    # instead of fixing them in-turn); the MCP tool returns a structured
    # `next_action` envelope that overrides the bias. Pin that requirement
    # structurally so a future SKILL edit cannot silently regress to the
    # old boundary while keeping the decision-record prose intact.
    if "gc_test_quality_review" not in section:
        missing.append(
            "MCP-tool invocation `gc_test_quality_review` (per #884 v2 the "
            "test-quality reviewer is an MCP tool returning a structured "
            "`next_action` envelope, not a `Skill(\"review-tests\")` call; "
            "the Skill-tool boundary's prose-return shape was the root cause "
            "of the v1 regression)"
        )
    # `next_action` is the dispatch field the parent reads as a directive
    # rather than as text to summarize. The SKILL must surface it
    # explicitly so the dispatch branches are visible to readers.
    if "next_action" not in section:
        missing.append(
            "`next_action` dispatch field (the parent reads it as a directive; "
            "must be explicit in Step 13 prose, not implied)"
        )
    if "gc_post_decision_record" not in section:
        missing.append(
            "tool name `gc_post_decision_record` (the canonical durable-record "
            "post; do not invent a new marker family)"
        )
    if not re.search(r'reviewer\s*[:=]\s*"test-quality"', section):
        missing.append(
            "reviewer enum literal `reviewer: \"test-quality\"` (must use "
            "the existing enum value, not a synthetic alias)"
        )
    if "findings: []" not in section:
        missing.append(
            "clean-cycle case `findings: []` (a clean cycle must still post "
            "a decision record; empty findings render as `0 (clean run)`)"
        )
    # Continuation signal: the prose must make explicit that a clean cycle
    # advances into Step 14 in the same turn. Accept any of "advance",
    # "proceed", or "continue" within a window that mentions Step 14, OR
    # the explicit "no acknowledgment turn" / "no user turn" phrasing.
    # The advance target was "Step 14" under the pre-#906 placement; under
    # #906 the test-quality review moved pre-push to Step 6.6 and the
    # advance target is "Phase C" (stage / commit / push). Accept either
    # token so the gate doesn't pin to a historical step number that the
    # workflow may renumber again.
    has_continuation = bool(
        re.search(
            r"(?is)(?:advance|proceed|continue)[^\n]{0,200}?(?:step\s*14|phase\s*c)"
            r"|(?:step\s*14|phase\s*c)[^\n]{0,200}?(?:advance|proceed|continue)"
            r"|no\s+(?:user\s+)?(?:acknowledg(?:e)?ment|user)\s+turn",
            section,
        )
    )
    if not has_continuation:
        missing.append(
            "explicit advance signal (a clean decision record IS "
            "the continuation signal — no acknowledgment turn between the "
            "test-quality review and the next phase; cite Phase C or Step 14 "
            "explicitly)"
        )
    # Success precondition: advancement must be gated on the
    # `gc_post_decision_record` call returning `ok: true`. Without this,
    # `ok: false` envelopes (sensitive-content rejection, body-size cap,
    # `gh` posting failure, network) would let Step 13 advance without the
    # durable issue-thread marker, re-introducing #884 in a different
    # shape. Accept the literal `ok: true` (matching the MCP envelope key)
    # or the prose "success precondition" wording.
    has_success_precondition = bool(
        re.search(
            r"(?is)`?ok\s*:\s*true`?"
            r"|\bsuccess\s+precondition\b",
            section,
        )
    )
    if not has_success_precondition:
        missing.append(
            "success-precondition clause `ok: true` (advance to Step 14 only "
            "after `gc_post_decision_record` returns `ok: true`; on `ok: false` "
            "fix the underlying tooling issue and retry — do not enter Step 14 "
            "with the durable marker missing)"
        )
    # Findings-fix-in-same-turn directive. The whole point of the
    # test-quality review is to fix the tests, not to file a status
    # report on them. After #884 v1 shipped, the regression reappeared
    # in a different shape: when `review-tests` returns findings, the
    # parent agent echoes them back to the user and stops, instead of
    # fixing them in the same agent turn. This required clause pairs an
    # action verb ("fix" / "address" / "resolve") against `findings`
    # with a "same turn" / "do not stop" continuity marker so the
    # contract is unambiguous: findings are work, not a status report.
    has_fix_same_turn_directive = bool(
        re.search(
            r"(?is)\b(?:fix|address|resolve)\b"
            r"[^.]{0,200}?\bfinding[s]?\b"
            r"[^.]{0,200}?\b(?:in\s+the\s+same\s+(?:turn|agent\s+turn)"
            r"|same\s+(?:agent\s+)?turn"
            r"|do\s+not\s+stop"
            r"|without\s+stopping)\b",
            section,
        )
    ) or bool(
        re.search(
            r"(?is)\b(?:in\s+the\s+same\s+(?:turn|agent\s+turn)"
            r"|same\s+(?:agent\s+)?turn"
            r"|do\s+not\s+stop"
            r"|without\s+stopping)\b"
            r"[^.]{0,200}?\b(?:fix|address|resolve)\b"
            r"[^.]{0,200}?\bfinding[s]?\b",
            section,
        )
    )
    if not has_fix_same_turn_directive:
        missing.append(
            "findings-fix-in-same-turn directive (when `review-tests` returns "
            "findings, the parent MUST fix them in the same agent turn — do "
            "not stop, do not echo the findings to the user as a status "
            "report; the whole point of the review is to fix the tests, "
            "issue #884 follow-up)"
        )

    if missing:
        violations.append(
            Violation(
                code="test-quality-decision-record-contract",
                message=(
                    "Step 6.6 (test-quality review) must mandate the "
                    "gc_post_decision_record contract for test-quality cycles "
                    "(issue #884; step moved pre-push by #906)."
                ),
                details=missing,
            )
        )
    return violations


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run repo policy checks.")
    parser.add_argument("--base", help="Git base ref to diff against.")
    parser.add_argument(
        "--files",
        nargs="*",
        default=None,
        help="Explicit repo-relative files to evaluate.",
    )
    parser.add_argument(
        "paths",
        nargs="*",
        help="Positional repo-relative files to evaluate. Used by pre-commit.",
    )
    parser.add_argument("--files-env", help="Read newline-delimited files from an env var.")
    parser.add_argument("--staged", action="store_true", help="Read staged files from git.")
    parser.add_argument(
        "--skip-pr-body",
        action="store_true",
        help="Do not evaluate the GitHub pull request body.",
    )
    parser.add_argument(
        "--event-path",
        help="Path to a GitHub event payload. Defaults to GITHUB_EVENT_PATH when present.",
    )
    parser.add_argument(
        "--pr-body-file",
        help=(
            "Path to a plain-text PR body draft. Use this in pre-push hooks to "
            "validate the PR body before push. Mutually exclusive with --event-path / "
            "--pr-number; --pr-body-file wins when supplied."
        ),
    )
    parser.add_argument(
        "--pr-number",
        type=int,
        help=(
            "GitHub PR number. When set (and neither --pr-body-file nor "
            "--event-path is supplied), the body is fetched via "
            "`gh pr view <n> --json body`."
        ),
    )
    return parser.parse_args(argv)


def render_and_exit(violations: list[Violation]) -> int:
    if not violations:
        print("Policy checks passed.")
        return 0

    print("Policy checks failed:")
    for violation in violations:
        print(violation.render())
    return 1


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    explicit_files = args.files if args.files is not None else args.paths
    if args.files and args.paths:
        explicit_files = [*args.files, *args.paths]
    changed_files = read_changed_files(
        files=explicit_files,
        base=args.base,
        staged=args.staged,
        env_var=args.files_env,
    )

    violations = []
    violations.extend(run_adr_guard(changed_files))
    violations.extend(run_controller_contracts(changed_files))
    violations.extend(run_migration_policy(changed_files))
    violations.extend(run_changelog_fragment_check(changed_files))
    violations.extend(run_deploy_compose_credential_passthrough())
    violations.extend(run_enum_contract_check())
    violations.extend(run_test_quality_decision_record_contract())

    if not args.skip_pr_body:
        body = _resolve_pr_body(args)
        if body is not None:
            # check_pr_body composes the no-deferral check (ADR-029) so all
            # PR-body validation routes share the same contract.
            violations.extend(check_pr_body(body))

    return render_and_exit(violations)


def _resolve_pr_body(args: argparse.Namespace) -> str | None:
    """Resolve the PR body string from CLI args / environment, in priority order.

    1. ``--pr-body-file`` — local pre-push hook driver.
    2. ``--pr-number`` — fetched via ``gh pr view <n> --json body``.
    3. ``--event-path`` or ``GITHUB_EVENT_PATH`` — CI driver.

    Returns ``None`` when no source is configured (the check is skipped).
    """
    if args.pr_body_file:
        return Path(args.pr_body_file).read_text(encoding="utf-8")
    if args.pr_number is not None:
        result = subprocess.run(
            ["gh", "pr", "view", str(args.pr_number), "--json", "body", "--jq", ".body"],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout
    event_path = args.event_path or os.getenv("GITHUB_EVENT_PATH")
    if event_path:
        event = json.loads(Path(event_path).read_text(encoding="utf-8"))
        pull_request = event.get("pull_request") or {}
        return pull_request.get("body") or ""
    return None


if __name__ == "__main__":
    raise SystemExit(main())
