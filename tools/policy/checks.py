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
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parents[2]
ADR_POLICY_PATH = REPO_ROOT / "architecture" / "policies" / "adr-policy.json"
BRANCH_PROTECTION_BASELINE_PATH = Path(".github/branch-protection-baseline.json")
CODEOWNERS_PATH = Path(".github/CODEOWNERS")
CI_WORKFLOW_PATH = Path(".github/workflows/ci.yml")
PRE_COMMIT_CONFIG_PATH = Path(".pre-commit-config.yaml")
SONAR_NEW_ISSUE_GATE_PATH = Path("tools/sonar/assert_no_new_issues.py")
MODULE_GRAPH_LOCK_LEVELS = frozenset({"locked", "guarded", "fluid"})
MODULE_GRAPH_SURFACES = frozenset({"backend", "frontend", "mcp"})
# Surfaces enforced by the in-process import scanner below. The backend surface
# is enforced against the same registry by RegistryBoundaryArchitectureTest
# (ArchUnit reads compiled bytecode, which a source import scan cannot match).
MODULE_GRAPH_SCANNED_SURFACES = frozenset({"frontend", "mcp"})
MODULE_GRAPH_MODULE_KEYS = frozenset(
    {"id", "name", "surface", "owner", "lock_level", "risk_score", "selectors", "package", "projection"}
)
MODULE_GRAPH_REQUIRED_MODULE_KEYS = frozenset(
    {"id", "name", "surface", "owner", "lock_level", "risk_score", "selectors"}
)
MODULE_GRAPH_EDGE_KEYS = frozenset({"from", "to"})
MODULE_GRAPH_MAX_RISK_SCORE = 5
MODULE_GRAPH_SCAN_GLOBS = (
    "frontend/src/**/*.ts",
    "frontend/src/**/*.tsx",
    "frontend/src/**/*.js",
    "frontend/src/**/*.jsx",
    "mcp/ground-control/*.js",
)
# ESM/TS import specifier after `from`, bare `import`, dynamic `import(`, or `require(`.
MODULE_GRAPH_IMPORT_RE = re.compile(
    r"""(?:\bfrom\s+|\bimport\s+|\bimport\s*\(\s*|\brequire\s*\(\s*)['"]([^'"\n]+)['"]"""
)
DESIGN_AUTHORITY_APPROVAL_SCHEMA_VERSION = "gc.cld.design-authority-approval/v1"
DESIGN_AUTHORITY_APPROVAL_MARKER_PREFIX = "<!-- gc:design-authority-approval"
DESIGN_AUTHORITY_APPROVAL_MARKER_RE = re.compile(
    r"<!--\s*gc:design-authority-approval\s+([^>]*)-->", re.IGNORECASE
)
DESIGN_AUTHORITY_APPROVAL_DATA_RE = re.compile(
    r"<!--\s*gc:design-authority-approval-data\s+({.*?})\s*-->",
    re.IGNORECASE | re.DOTALL,
)
HTML_ATTR_RE = re.compile(r"([a-zA-Z_][a-zA-Z0-9_-]*)=\"([^\"]*)\"")

IMPLEMENTATION_PATH_SELECTORS = (
    "backend/src/main/**",
    "backend/src/test/**",
    "frontend/src/**",
    "mcp/**",
    "tools/**",
)
SKIPPED_TEST_ADDITION_RE = re.compile(
    r"^\+(?!\+\+).*(?:@Disabled\b|\.skip\s*\(|\b(?:describe|it|test)\.skip\s*\(|\b(?:xdescribe|xit|xtest)\s*\(|@pytest\.mark\.skip\b)",
    re.IGNORECASE,
)
CONTROLLER_PATH_RE = re.compile(
    r"^backend/src/main/java/com/keplerops/groundcontrol/api/.+Controller\.java$"
)
MIGRATION_PATH_RE = re.compile(r"^backend/src/main/resources/db/migration/V\d+__.+\.sql$")
PR_REQUIREMENT_RE = re.compile(r"\b[A-Z][A-Z0-9]+-[A-Z0-9]+(?:-\d+|\d+)\b")
CI_STRICTNESS_BRANCHES = ("main", "dev")
CI_STRICTNESS_REQUIRED_CONTEXTS = frozenset(
    {
        "GitGuardian Security Checks",
        "SonarCloud Code Analysis",
        "build",
        "integration",
        "mutation",
        "osv-scanner",
        "policy",
        "sonar",
        "test",
        "trivy",
        "verify",
    }
)
CI_PRE_COMMIT_HOOKS = (
    "trailing-whitespace",
    "end-of-file-fixer",
    "check-yaml",
    "check-json",
    "check-added-large-files",
    "check-merge-conflict",
    "detect-private-key",
    "gitleaks",
)
CONTRACT_REQUIRED_PATHS = (
    "contracts/openapi/openapi.json",
    "contracts/gen/typescript/api.ts",
    "contracts/ontology/gc-concept-families-v1.json",
    "contracts/ontology/gc-controlled-vocabularies-v1.json",
    "contracts/ontology/gc-artifact-bindings-v1.json",
    "contracts/schemas/records/implement-final-report.v1.schema.json",
    "contracts/schemas/workflow/workflow-run-record.v1.schema.json",
    "contracts/authz/path-matrix.yaml",
    "contracts/CHANGES.md",
)
FRONTEND_CONTRACT_SHIM_PATH = Path("frontend/src/types/api.ts")
GENERATED_CONTRACT_EXPORT = 'export * from "../../../contracts/gen/typescript/api";'


class RefUnreadableError(RuntimeError):
    """Raised when a caller supplied a base ref that cannot be resolved."""

    def __init__(self, base: str, detail: str):
        super().__init__(detail)
        self.base = base
        self.detail = detail

GROUND_CONTROL_YAML_PATH = Path(".ground-control.yaml")
# /implement routing stages whose step drives a gc_codex_job async poll loop
# (gc_codex_architecture_preflight / gc_codex_review_cycle /
# gc_test_quality_review_cycle, all called with async=true then polled). A
# dispatched subagent cannot drive that loop — a `Bash run_in_background sleep`
# poll-wait notification lands in the parent's stream, not the subagent's, so
# the subagent ends its turn and returns a degenerate envelope (issue #1168).
# These stages MUST resolve to agent: parent.
POLL_LOOP_ROUTING_STAGES = frozenset(
    {"architecture_preflight", "review_cycle_1_consume", "test_quality_review"}
)

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


def merge_base_or(base: str, ref: str = "HEAD", root: Path = REPO_ROOT) -> str:
    """Resolve the point from which ``ref`` diverged from ``base``.

    ``git diff <base> --`` is a two-dot comparison: it reports every path that
    differs between the tip of ``base`` and the working tree, so any commit
    ``base`` gained AFTER the branch forked is wrongly attributed to the branch.
    On a busy repo where ``dev`` advances while a PR is open, that mis-attributes
    ``dev``'s own later changes to the PR — spuriously tripping the diff-scoped
    gates (documentation coverage, changelog fragments) on files the branch
    never touched.

    Diffing from ``merge-base(base, ref)`` instead yields only what the branch
    itself changed, matching what GitHub shows as the PR diff. Comparing that
    merge base against the working tree (rather than ``base...ref``) preserves
    the caller's intent of catching still-uncommitted changes in the local /
    pre-push path.

    Falls back to ``base`` when no common ancestor exists (unrelated histories)
    or ``git merge-base`` fails, so the check degrades to the prior behavior
    instead of raising.
    """
    try:
        result = subprocess.run(
            ["git", "merge-base", base, ref],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        return base
    return result.stdout.strip() or base


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
        diff_base = merge_base_or(base, root=root)
        output = run_git(
            ["diff", "--name-only", "--diff-filter=ACDMRTUXB", diff_base, "--"], root=root
        )
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


def load_json(path: Path, *, reject_duplicate_keys: bool = False) -> dict:
    object_pairs_hook = None
    if reject_duplicate_keys:
        def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
            result: dict[str, Any] = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError(f"duplicate JSON key: {key}")
                result[key] = value
            return result

        object_pairs_hook = reject_duplicates
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=object_pairs_hook)














































































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


JAVA_MAIN_SOURCE_PREFIX = "backend/src/main/java/"
JAVA_TEST_SOURCE_PREFIX = "backend/src/test/java/"
WEBMVCTEST_ANNOTATION_RE = re.compile(r"@WebMvcTest\s*\(([^)]*)\)", re.DOTALL)
# Dotted Java identifier (`a.b.C`). Matched WITHOUT a trailing `.class` literal:
# a `(?:\.[\w$]+)*\.class` form overlaps the quantified segment with the final
# `.class` and backtracks super-linearly (Sonar S8786). The `.class` suffix is
# stripped in code instead, which keeps the match linear.
JAVA_DOTTED_NAME_RE = re.compile(r"[\w$]+(?:\.[\w$]+)*")
_CLASS_LITERAL_SUFFIX = ".class"
# Non-static single-type imports only: `import static ...;` has a space after
# `import` that `[\w.]+` cannot span, so it never matches here.
JAVA_IMPORT_RE = re.compile(r"^\s*import\s+([\w.]+)\s*;", re.MULTILINE)


def controller_fully_qualified_name(controller_path: str) -> str | None:
    """Fully-qualified class name for a controller from its repo-relative path."""
    normalized = normalize_path(controller_path)
    if not normalized.startswith(JAVA_MAIN_SOURCE_PREFIX) or not normalized.endswith(".java"):
        return None
    relative = normalized[len(JAVA_MAIN_SOURCE_PREFIX) : -len(".java")]
    return relative.replace("/", ".")


def test_covers_controller(content: str, controller_fqcn: str) -> bool:
    """True when a test's @WebMvcTest annotation resolves to ``controller_fqcn``.

    Resolution mirrors Java name binding: a fully-qualified literal matches
    directly; a simple name binds through the file's single-type import for that
    name; absent such an import the simple name binds in the file's own package.
    The import check is what disambiguates same-simple-name controllers in
    different packages (issue #1167) — matching on the bare filename stem, or on
    the annotation's simple name alone, cannot.
    """
    referenced: set[str] = set()
    for args in WEBMVCTEST_ANNOTATION_RE.findall(content):
        for token in JAVA_DOTTED_NAME_RE.findall(args):
            if token.endswith(_CLASS_LITERAL_SUFFIX):
                referenced.add(token[: -len(_CLASS_LITERAL_SUFFIX)])
    if not referenced:
        return False
    if controller_fqcn in referenced:
        return True
    simple_name = controller_fqcn.rsplit(".", 1)[-1]
    if simple_name not in referenced:
        return False
    imports = {imported.rsplit(".", 1)[-1]: imported for imported in JAVA_IMPORT_RE.findall(content)}
    bound = imports.get(simple_name)
    if bound is not None:
        return bound == controller_fqcn
    # No single-type import of the simple name: it binds in the test's own
    # package (or via a wildcard import that cannot be resolved statically).
    # The conflicting-import collision this check exists to prevent has already
    # been excluded above, so accept the simple-name match.
    return True


def run_controller_contracts(changed_files: list[str], root: Path = REPO_ROOT) -> list[Violation]:
    controllers = [path for path in changed_files if CONTROLLER_PATH_RE.match(path)]
    if not controllers:
        return []

    violations: list[Violation] = []
    missing: list[str] = []
    if "docs/API.md" not in changed_files:
        missing.append("docs/API.md")
    if "mcp/ground-control/lib.js" not in changed_files:
        missing.append("mcp/ground-control/lib.js")
    # MCP server adapter companion: most tools register inline in index.js, but
    # gc_risk_governance and gc_risk_scenario were factored into gc-risk-governance.js
    # and gc-risk-scenario.js respectively (their Zod shapes, descriptions, and handlers
    # live there; index.js only registers the imports). Either file satisfies the
    # MCP-adapter requirement for those controllers; index.js stays mandatory for any
    # tool still registered inline.
    adapter_files = (
        "mcp/ground-control/index.js",
        "mcp/ground-control/gc-risk-governance.js",
        "mcp/ground-control/gc-risk-scenario.js",
    )
    if not any(adapter in changed_files for adapter in adapter_files):
        missing.append("one of: " + ", ".join(adapter_files))
    if missing:
        violations.append(
            Violation(
                code="controller-parity",
                message="Controller changes require API docs and MCP parity updates.",
                details=[
                    f"controllers changed: {', '.join(controllers)}",
                    f"missing companion updates: {', '.join(missing)}",
                ],
            )
        )

    # Resolve each controller's @WebMvcTest companion by reverse-lookup on the
    # controller's fully-qualified class, not its filename stem. The stem
    # collides whenever two packages declare a same-named controller (issue
    # #1167: api/audit/AuditController vs api/audits/AuditController).
    repo_test_files = get_repo_relative_files(root, "backend/src/test/java/**/*.java")
    changed_test_files = [
        path
        for path in changed_files
        if path.startswith(JAVA_TEST_SOURCE_PREFIX) and path.endswith(".java")
    ]

    def covers(rel_path: str, fqcn: str) -> bool:
        try:
            content = (root / rel_path).read_text(encoding="utf-8")
        except OSError:
            return False
        return test_covers_controller(content, fqcn)

    for controller in controllers:
        # A controller deleted in this diff has no request mapping left to slice-test,
        # and its @WebMvcTest companion is deleted along with it. Demanding a companion
        # for a file that no longer exists would make route removal unshippable.
        if not (root / controller).exists():
            continue
        fqcn = controller_fully_qualified_name(controller)
        if fqcn is None:
            continue
        simple_name = fqcn.rsplit(".", 1)[-1]

        if any(covers(path, fqcn) for path in changed_test_files):
            # The controller's @WebMvcTest companion was updated in this diff.
            continue

        existing = [path for path in repo_test_files if covers(path, fqcn)]
        if existing:
            violations.append(
                Violation(
                    code="controller-webmvctest-update",
                    message="Controller changes require a matching @WebMvcTest update.",
                    details=[
                        f"changed {controller} but did not update its @WebMvcTest "
                        f"companion; expected one of: {', '.join(existing)}"
                    ],
                )
            )
            continue

        # No @WebMvcTest slice resolves to this controller. Distinguish "a
        # same-named test exists but is not a slice" (annotation) from "no test
        # exists at all" (missing) so the message points at the real gap.
        stem_tests = get_repo_relative_files(
            root, f"backend/src/test/java/**/{simple_name}Test.java"
        )
        non_slice_tests = [
            path
            for path in stem_tests
            if "@WebMvcTest(" not in (root / path).read_text(encoding="utf-8")
        ]
        if non_slice_tests:
            violations.append(
                Violation(
                    code="controller-webmvctest-annotation",
                    message="Controller test exists but is not a @WebMvcTest.",
                    details=[
                        f"{', '.join(non_slice_tests)} must use @WebMvcTest for {controller}"
                    ],
                )
            )
            continue

        violations.append(
            Violation(
                code="controller-webmvctest-missing",
                message="Controller is missing a matching @WebMvcTest class.",
                details=[f"no @WebMvcTest({simple_name}.class) slice found for {controller}"],
            )
        )

    return violations


def git_diff_for_paths(paths: Iterable[str], root: Path = REPO_ROOT) -> str:
    path_list = [normalize_path(path) for path in paths]
    if not path_list:
        return ""
    return run_git(["diff", "--unified=0", "HEAD", "--", *path_list], root=root)


def _resolve_baseline_ref(base: str | None, root: Path = REPO_ROOT) -> str | None:
    """Resolve the released-baseline ref to diff migration content against.

    Prefers the explicit ``--base`` ref, then ``origin/main`` (the released
    line), then ``main``. Returns ``None`` when none resolve (e.g. a shallow
    clone without the baseline fetched) so the immutability check skips
    gracefully rather than failing the run.
    """
    for ref in (base, "origin/main", "main"):
        if not ref:
            continue
        try:
            run_git(["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], root=root)
            return ref
        except subprocess.CalledProcessError:
            continue
    return None


def _migration_content_at_ref(ref: str, path: str, root: Path = REPO_ROOT) -> str | None:
    """Return ``path`` content at ``ref``, or ``None`` if it does not exist there."""
    try:
        return run_git(["show", f"{ref}:{path}"], root=root)
    except subprocess.CalledProcessError:
        return None


def run_migration_policy(
    changed_files: list[str], root: Path = REPO_ROOT, base: str | None = None
) -> list[Violation]:
    migrations = [path for path in changed_files if MIGRATION_PATH_RE.match(path)]
    violations: list[Violation] = []

    if migrations:
        # Flyway immutability: a migration already present on the released
        # baseline (origin/main) must never have its content changed — Flyway
        # validates checksums on every startup, so editing an applied migration
        # crashes every database that already ran it (the V043/V045 incident,
        # which a fresh-DB smoke test cannot catch). New migrations are exempt
        # (absent from the baseline); the only correct way to change applied
        # data/schema is a new forward migration.
        baseline = _resolve_baseline_ref(base, root)
        if baseline:
            for path in migrations:
                released = _migration_content_at_ref(baseline, path, root)
                if released is None:
                    continue  # new migration — not on the baseline, allowed.
                target = root / path
                current = target.read_text(encoding="utf-8") if target.exists() else None
                if current != released:
                    change = "removed" if current is None else "modified"
                    violations.append(
                        Violation(
                            code="migration-immutability",
                            message=(
                                "An applied Flyway migration was changed. Migrations on the "
                                "released baseline are immutable — add a new forward migration "
                                "instead of editing one."
                            ),
                            details=[
                                f"{change} migration: {path}",
                                f"baseline ref: {baseline}",
                                "Flyway validates checksums on startup; changing an applied "
                                "migration breaks every database that already ran it.",
                            ],
                        )
                    )

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


_DOCUMENTATION_COVERAGE_FIXTURE = REPO_ROOT / "tools" / "documentation_coverage_fixture.mjs"
_DOCUMENTATION_SECTION_RE = re.compile(r"^##\s+Documentation\b", re.MULTILINE)


def run_documentation_coverage_check(
    changed_files: list[str],
    root: Path = REPO_ROOT,
    pr_body: str | None = None,
) -> list[Violation]:
    """Classify the diff and verify the PR body carries a documentation outcome.

    Violation codes:
    - ``doc-coverage-outcome-missing``: a classified surface requires a
      documentation outcome but the PR body has no ``## Documentation`` section.
    - ``doc-coverage-fixture-error``: the Node classifier fixture failed
      (treat as a drift signal, not a pass).

    The check skips gracefully when ``node`` is unavailable or the PR body
    cannot be resolved (matches the changelog-fragment check style).
    """
    import shutil

    if shutil.which("node") is None:
        return []

    if not _DOCUMENTATION_COVERAGE_FIXTURE.exists():
        return [
            Violation(
                code="doc-coverage-fixture-error",
                message=(
                    "documentation_coverage_fixture.mjs not found — "
                    "documentation coverage check cannot run."
                ),
                details=[f"expected at {_DOCUMENTATION_COVERAGE_FIXTURE}"],
            )
        ]

    fixture_input = {
        "repo_path": str(root),
        "changed_paths": list(changed_files),
    }
    try:
        proc = subprocess.run(
            ["node", str(_DOCUMENTATION_COVERAGE_FIXTURE)],
            input=json.dumps(fixture_input),
            capture_output=True,
            text=True,
            cwd=str(root),
            timeout=30,
        )
    except Exception as exc:  # noqa: BLE001
        return [
            Violation(
                code="doc-coverage-fixture-error",
                message=f"documentation_coverage_fixture.mjs failed to execute: {exc}",
                details=[],
            )
        ]

    if proc.returncode != 0:
        return [
            Violation(
                code="doc-coverage-fixture-error",
                message="documentation_coverage_fixture.mjs exited with non-zero status.",
                details=[f"stderr: {proc.stderr.strip()[:500]}"] if proc.stderr.strip() else [],
            )
        ]

    try:
        result = json.loads(proc.stdout)
    except Exception:  # noqa: BLE001
        return [
            Violation(
                code="doc-coverage-fixture-error",
                message="documentation_coverage_fixture.mjs produced invalid JSON output.",
                details=[],
            )
        ]

    if not result.get("outcome_required"):
        return []

    # outcome_required is true — check the PR body for a ## Documentation section.
    if pr_body is None:
        # No PR body available; skip gracefully (mirrors changelog check style).
        return []

    if not _DOCUMENTATION_SECTION_RE.search(pr_body):
        surface_classes = sorted({
            c["surface_class"]
            for c in result.get("classifications", [])
            if c.get("surface_class") not in ("doc", "unclassified")
        })
        suggested = result.get("suggested_doc_targets", [])
        details = []
        if surface_classes:
            details.append(f"classified surfaces: {', '.join(surface_classes)}")
        if suggested:
            details.append(f"suggested doc targets: {', '.join(suggested)}")
        return [
            Violation(
                code="doc-coverage-outcome-missing",
                message=(
                    "Diff touches a documented surface but the PR body has no "
                    "## Documentation section. Add a documentation_outcome field "
                    "when calling gc_render_pr_body (ADR-054)."
                ),
                details=details,
            )
        ]

    return []


def run_ci_strictness_contract(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert the repo carries the CI strictness baseline from issue #1155."""
    violations: list[Violation] = []
    workflow_path = root / CI_WORKFLOW_PATH
    pre_commit_path = root / PRE_COMMIT_CONFIG_PATH
    sonar_gate_path = root / SONAR_NEW_ISSUE_GATE_PATH
    branch_protection_path = root / BRANCH_PROTECTION_BASELINE_PATH

    if not workflow_path.exists():
        violations.append(
            Violation(
                code="ci-strictness-workflow-missing",
                message="CI workflow is missing, so required merge checks cannot be verified.",
                details=[f"expected at {CI_WORKFLOW_PATH.as_posix()}"],
            )
        )
        return violations

    workflow_text = workflow_path.read_text(encoding="utf-8")
    repo_policy_step = re.search(
        r"(?ms)^\s{6}- name: Repo policy checks\n(?P<body>.*?)(?=^\s{6}- name: |\Z)",
        workflow_text,
    )
    if repo_policy_step is None:
        violations.append(
            Violation(
                code="ci-strictness-repo-policy-step-missing",
                message="CI policy job must run repo policy checks for pull requests.",
                details=[f"missing Repo policy checks step in {CI_WORKFLOW_PATH.as_posix()}"],
            )
        )
    else:
        repo_policy_body = repo_policy_step.group("body")
        if "GH_TOKEN" in repo_policy_body:
            violations.append(
                Violation(
                    code="ci-strictness-policy-token-exposure",
                    message="PR-head repo policy code must not receive the GitHub token.",
                    details=[
                        "remove GH_TOKEN from the Repo policy checks step",
                        "fetch sanitized PR comments in a separate shell step and pass --pr-comments-json instead",
                    ],
                )
            )
        if "--pr-comments-json" not in repo_policy_body:
            violations.append(
                Violation(
                    code="ci-strictness-policy-comments-json",
                    message="CI repo policy checks must consume sanitized PR comments from a file.",
                    details=["expected --pr-comments-json in the Repo policy checks step"],
                )
            )
    if "python3 -m pip install --user pre-commit" not in workflow_text:
        violations.append(
            Violation(
                code="ci-strictness-precommit-install",
                message="CI policy job must install pre-commit before running hygiene and secret-scan hooks.",
                details=[f"expected install command in {CI_WORKFLOW_PATH.as_posix()}"],
            )
        )
    missing_workflow_hooks = [
        hook
        for hook in CI_PRE_COMMIT_HOOKS
        if hook not in workflow_text or 'pre-commit run "$hook" --all-files' not in workflow_text
    ]
    if missing_workflow_hooks:
        violations.append(
            Violation(
                code="ci-strictness-precommit-hooks",
                message="CI policy job must run the pre-commit file-hygiene and secret-scan hooks.",
                details=[*(f"missing workflow hook run: {hook}" for hook in missing_workflow_hooks)],
            )
        )

    if not pre_commit_path.exists():
        violations.append(
            Violation(
                code="ci-strictness-precommit-config-missing",
                message="pre-commit config is missing, so local hygiene and secret-scan hooks cannot be verified.",
                details=[f"expected at {PRE_COMMIT_CONFIG_PATH.as_posix()}"],
            )
        )
    else:
        pre_commit_text = pre_commit_path.read_text(encoding="utf-8")
        missing_config_hooks = [hook for hook in CI_PRE_COMMIT_HOOKS if f"id: {hook}" not in pre_commit_text]
        if missing_config_hooks:
            violations.append(
                Violation(
                    code="ci-strictness-precommit-config-hooks",
                    message=".pre-commit-config.yaml must define every CI-enforced hygiene and secret-scan hook.",
                    details=[*(f"missing configured hook: {hook}" for hook in missing_config_hooks)],
                )
            )

    if "-Dsonar.qualitygate.wait=true" not in workflow_text:
        violations.append(
            Violation(
                code="ci-strictness-sonar-qualitygate-wait",
                message="SonarCloud CI must wait for the quality gate result.",
                details=[f"missing -Dsonar.qualitygate.wait=true in {CI_WORKFLOW_PATH.as_posix()}"],
            )
        )
    if SONAR_NEW_ISSUE_GATE_PATH.as_posix() not in workflow_text:
        violations.append(
            Violation(
                code="ci-strictness-sonar-new-issue-gate",
                message="SonarCloud CI must fail when Sonar reports any new open issue.",
                details=[f"missing {SONAR_NEW_ISSUE_GATE_PATH.as_posix()} invocation"],
            )
        )
    if not sonar_gate_path.exists():
        violations.append(
            Violation(
                code="ci-strictness-sonar-gate-script-missing",
                message="SonarCloud new-issue gate script is missing.",
                details=[f"expected at {SONAR_NEW_ISSUE_GATE_PATH.as_posix()}"],
            )
        )

    if not branch_protection_path.exists():
        violations.append(
            Violation(
                code="ci-strictness-branch-protection-missing",
                message="Versioned branch-protection baseline is missing.",
                details=[f"expected at {BRANCH_PROTECTION_BASELINE_PATH.as_posix()}"],
            )
        )
        return violations

    try:
        baseline = load_json(branch_protection_path)
    except json.JSONDecodeError as exc:
        violations.append(
            Violation(
                code="ci-strictness-branch-protection-invalid",
                message="Versioned branch-protection baseline is not valid JSON.",
                details=[str(exc)],
            )
        )
        return violations

    branches = baseline.get("branches", {})
    for branch in CI_STRICTNESS_BRANCHES:
        config = branches.get(branch)
        if not isinstance(config, dict):
            violations.append(
                Violation(
                    code="ci-strictness-branch-protection-branch",
                    message=f"{branch} must be present in the branch-protection baseline.",
                    details=[f"missing branches.{branch}"],
                )
            )
            continue
        if config.get("changes_land_via_pull_request") is not True:
            violations.append(
                Violation(
                    code="ci-strictness-branch-protection-pr",
                    message=f"{branch} must require changes to land via pull request.",
                    details=[f"branches.{branch}.changes_land_via_pull_request must be true"],
                )
            )
        if config.get("admin_bypass_allowed") is not True:
            violations.append(
                Violation(
                    code="ci-strictness-branch-protection-admin-bypass",
                    message=f"{branch} must retain admin bypass.",
                    details=[f"branches.{branch}.admin_bypass_allowed must be true"],
                )
            )
        required_status_checks = config.get("required_status_checks", {})
        if required_status_checks.get("strict") is not True:
            violations.append(
                Violation(
                    code="ci-strictness-branch-protection-strict",
                    message=f"{branch} required status checks must use strict mode.",
                    details=[f"branches.{branch}.required_status_checks.strict must be true"],
                )
            )
        contexts = set(required_status_checks.get("contexts", []))
        missing_contexts = sorted(CI_STRICTNESS_REQUIRED_CONTEXTS - contexts)
        if missing_contexts:
            violations.append(
                Violation(
                    code="ci-strictness-branch-protection-contexts",
                    message=f"{branch} branch protection must require all PR CI contexts.",
                    details=[*(f"missing required context: {context}" for context in missing_contexts)],
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
# GHCR image-namespace drift check (issue #953, GC-P022).
#
# red-dragon silently served a stale build for ~10 days: the CI publish
# namespace (`ghcr.io/<owner>/ground-control`) diverged from the deploy-host
# image pin when the repo moved orgs (KeplerOps -> Brad-Edwards -> autarchy-ai).
# `docker compose pull` kept resolving a frozen image under the abandoned
# namespace while the still-healthy old container kept the deploy health check
# green, so nothing failed. This static post-condition pins every deploy/CI/doc
# artifact that names the image to the single canonical namespace, so the next
# diff that reintroduces a stale namespace fails `make policy`. Adding a new
# artifact that names the image is one inventory row.
#
# CHANGELOG.md is intentionally excluded — it records historical namespaces
# (towncrier-collated release notes) that must NOT be rewritten.
CANONICAL_GHCR_NAMESPACE = "autarchy-ai"
GHCR_IMAGE_REF_RE = re.compile(r"ghcr\.io/([A-Za-z0-9._-]+)/ground-control")
GHCR_NAMESPACE_INVENTORY: tuple[Path, ...] = (
    Path("Makefile"),
    Path(".github/workflows/ci.yml"),
    Path("deploy/docker/.env.example"),
    Path("deploy/docker/docker-compose.prod.yml"),
    Path("deploy/docker/deploy.sh"),
    Path("deploy/docker/README.md"),
    Path("scripts/deploy.sh"),
    Path("docs/deployment/DEPLOYMENT.md"),
    Path("docs/architecture/ARCHITECTURE.md"),
    Path("skills/deploy/SKILL.md"),
    Path("architecture/adrs/030-on-prem-hetzner-deployment.md"),
)
# Test files are intentionally excluded: a drift check's own negative-case
# fixtures must carry a non-canonical literal (e.g. tools/tests/test_policy.py),
# which is correct, not drift.


def run_ghcr_namespace_drift(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert every inventoried artifact names the canonical GHCR namespace.

    A non-canonical `ghcr.io/<ns>/ground-control` reference in any inventoried
    deploy/CI/doc file is the drift that froze red-dragon's deploy silently
    (#953). Absent inventory files are skipped — the gate catches drift in the
    files that exist, it does not assert their presence (other checks own
    file-existence post-conditions).
    """
    offenders: list[str] = []
    for rel_path in GHCR_NAMESPACE_INVENTORY:
        file_path = root / rel_path
        if not file_path.exists():
            continue
        text = file_path.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), start=1):
            for match in GHCR_IMAGE_REF_RE.finditer(line):
                namespace = match.group(1)
                if namespace != CANONICAL_GHCR_NAMESPACE:
                    offenders.append(
                        f"{rel_path.as_posix()}:{line_number} references "
                        f"non-canonical namespace '{namespace}' "
                        f"(expected '{CANONICAL_GHCR_NAMESPACE}')"
                    )
    if not offenders:
        return []
    return [
        Violation(
            code="ghcr-namespace-drift",
            message=(
                "Deploy/CI artifacts must reference the single canonical GHCR "
                f"namespace 'ghcr.io/{CANONICAL_GHCR_NAMESPACE}/ground-control' "
                "(GC-P022 / #953). A divergent namespace lets `docker compose "
                "pull` silently resolve a frozen image."
            ),
            details=offenders,
        )
    ]


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
    for rel_path in REPO_IDENTITY_INVENTORY:
        file_path = root / rel_path
        if not file_path.exists():
            continue
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


# ---------------------------------------------------------------------------
# Deploy artifact consistency check (issue #855, GC-P023).
#
# The red-dragon deploy has broken silently many times because the deploy
# artifacts had no single source of truth: two divergent deploy scripts, two
# contradictory env templates, and a /opt/gc mirror hand-copied out of band.
# This static post-condition pins the repo-side invariants the operator-driven
# ADR-030 path depends on, so the next diff that reintroduces the drift fails
# `make policy` before it can ship:
#   - exactly one canonical prod env template (.env.example); the contradictory
#     .env.template is gone.
#   - env.schema is the single env contract: every ${VAR} the prod compose
#     dereferences is declared there (REQUIRED when the compose ref has no
#     default), and GC_IMAGE is marked RELEASE_PIN so the deploy-time validator
#     requires an immutable versioned release pin, not a floating tag (ADR-063).
#   - MANIFEST.sha256 matches the canonical artifacts byte-for-byte, so the
#     deploy-time drift guard in deploy.sh checks against a current manifest.
#   - exactly one operator wrapper (scripts/deploy.sh); the dead divergent
#     duplicate at deploy/scripts/deploy.sh stays removed.
#   - the operator wrapper (scripts/deploy.sh) does not duplicate the rollout
#     primitives (`docker compose pull`/`up`); those live only in the canonical
#     deploy/docker/deploy.sh.
# Regenerate the manifest after editing any canonical artifact with
# `make deploy-manifest`.
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Env-template orphan-key check (issue #1384, GC-P023 (a)/(e)).
#
# run_deploy_artifact_consistency already proves one direction: every ${VAR} the
# production compose dereferences is declared in env.schema. Nothing proved the
# reverse, so a key could outlive the service that read it. The Temporal removal
# (#1359) left exactly that residue: the templates went on advertising a worker
# target, a namespace, a task queue, and a database password for a service that
# no longer exists, and an operator reading the template could not tell.
#
# The invariant: a key an active template advertises must have an *executable*
# consumer - something that, at runtime, reads the value the operator set. Tests,
# docs, superseded ADRs, and historical migrations are not consumers: they are
# history, and history is not an input.
#
# "Executable consumer" is a strict bar, and every loosening of it re-admits the
# exact dead config the check exists to catch. Four rules carry that weight:
#
#   1. A compose LITERAL is not a consumer. `- GC_SERVER_PORT=8000` pins the
#      value; it does not read the operator's. A template advertising a key that
#      compose pins is advertising control the operator does not have - the same
#      lie in a quieter voice. Only ${VAR} interpolation and list-form inherit
#      count, and inherit only inside an `environment:` block (a bare `- FOO`
#      elsewhere in the yaml is a list item, not a variable).
#   2. A DECLARATION is not a consumer. env.schema says a key must be present and
#      well-formed; it never reads it. Treating schema membership as consumption
#      would certify any key left stale in BOTH the template and the schema -
#      a false negative precisely where drift hides. env.schema is therefore not
#      a consumer surface here; the production compose is what forwards an
#      operator's value into the container, and that is what must prove it.
#   3. A MENTION is not a consumer. A key named in a comment, an error message,
#      or any other string is not a read. Each surface is matched on its actual
#      read syntax - `${VAR}` in yaml, `process.env.VAR` in the MCP client,
#      `$VAR` / `ENV_VALUES[VAR]` in the deploy shell - and never on bare
#      textual occurrence.
#   4. The production template does NOT get the backend-application surface. An
#      application.yml placeholder is irrelevant to /opt/gc/.env unless compose
#      forwards the value into the container.
#
# Spring relaxed binding is resolved against the declared @ConfigurationProperties
# FIELDS, not merely the prefix: GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN binds
# because SecurityProperties declares `credentials`, while a hypothetical
# GROUNDCONTROL_SECURITY_BOGUS binds to nothing and is still an orphan. Prefix
# matching alone would bless every unknown child of a real prefix.
#
# The seam is the inventory below: a new template or service is one row, never a
# per-key exception. A per-key allowlist would just re-bless dead config quietly.
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Methodology catalog drift check (issue #1005, ADR-078).
#
# The backend-owned methodology catalog
# (backend/src/main/resources/research/methodology-catalog.yaml) is the single
# source of truth the research methodology gate derives required-source coverage
# from; the skill-side lookup (skills/lit-review/methodology/catalog.yaml) is a
# mirror the phase-1 lit-review skill reads. If the two disagree on method keys
# or source identifiers, the skill can steer an agent to read sources the backend
# gate does not require (or vice versa) — a silent scientific-behavior drift.
# This static check fails `make policy` when they diverge.
#
# Parsing is a deliberate stdlib-only line walk over the two controlled catalog
# shapes (see _extract_compose_backend_env_entries for why tools/policy/ avoids a
# PyYAML dependency): a `- key: <k>` line opens a method; `- zotero_key: <id>`
# (skill) / `- ref: <id>` (backend) lines name that method's source identifiers.
# ---------------------------------------------------------------------------

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

FRONTEND_API_TYPES_PATH = "contracts/gen/typescript/api.ts"
MCP_LIB_PATH = "mcp/ground-control/lib.js"
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


# ---------------------------------------------------------------------------
# Context-graph ontology bindings (ADR-084 / issue #1307).
#
# The inventory is discovered from Java source independently of the binding
# artifact.  This is intentionally separate from ENUM_CONTRACT_INVENTORY:
# that inventory governs API mirror parity, while this check governs graph
# concept identity and must also see package-local contributors.
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Contract surface foundation (GC-O014 / ADR-082).
#
# The contract surface is intentionally artifact-backed: backend-generated
# OpenAPI, generated TypeScript, durable-record/workflow schemas, the authz
# path matrix, and the breaking-change ledger live under contracts/. These
# checks are lightweight static policy checks; the heavier regenerate-and-diff
# gate runs through `make contracts-check`.
# ---------------------------------------------------------------------------


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


def run_contract_invariant_enforcement_check(root: Path = REPO_ROOT) -> list[Violation]:
    violations: list[Violation] = []
    schemas_dir = root / "contracts" / "schemas"
    if not schemas_dir.exists():
        return [
            Violation(
                code="contract-schema-dir-missing",
                message="contracts/schemas/ is missing; GC-O014 schema invariant coverage cannot be checked.",
            )
        ]

    for schema_path in sorted(schemas_dir.rglob("*.schema.json")):
        rel = schema_path.relative_to(root).as_posix()
        try:
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            violations.append(
                Violation(
                    code="contract-schema-json-invalid",
                    message=f"{rel} is not valid JSON.",
                    details=[str(exc)],
                )
            )
            continue

        invariants = schema.get("x-ground-control-invariants")
        if invariants is None:
            violations.append(
                Violation(
                    code="contract-invariant-inventory-missing",
                    message=f"{rel} must declare x-ground-control-invariants.",
                    details=["Use [{\"id\":\"none\", \"rationale\":\"...\"}] only when the schema has no declared invariant."],
                )
            )
            continue
        if not isinstance(invariants, list) or len(invariants) == 0:
            violations.append(
                Violation(
                    code="contract-invariant-inventory-invalid",
                    message=f"{rel} has an empty or non-list x-ground-control-invariants value.",
                )
            )
            continue

        for entry in invariants:
            if not isinstance(entry, dict) or not entry.get("id"):
                violations.append(
                    Violation(
                        code="contract-invariant-entry-invalid",
                        message=f"{rel} has an invariant entry without an id.",
                    )
                )
                continue
            if entry["id"] == "none":
                if not entry.get("rationale"):
                    violations.append(
                        Violation(
                            code="contract-invariant-none-rationale-missing",
                            message=f"{rel} declares no invariants but omits a rationale.",
                        )
                    )
                continue
            enforced_by = entry.get("enforcedBy")
            if not isinstance(enforced_by, list) or len(enforced_by) == 0:
                violations.append(
                    Violation(
                        code="contract-invariant-enforcement-missing",
                        message=f"{rel} invariant {entry['id']} must name at least one enforcing test or spec file.",
                    )
                )
                continue
            for target in enforced_by:
                if not isinstance(target, str):
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-invalid",
                            message=f"{rel} invariant {entry['id']} has an invalid enforcement path.",
                            details=[str(target)],
                        )
                    )
                    continue

                target_path_text, separator, target_anchor = target.partition("::")
                target_path = Path(target_path_text)
                if not separator or not target_anchor:
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-anchor-missing",
                            message=f"{rel} invariant {entry['id']} must name a specific test/spec anchor.",
                            details=[f"use '<repo-path>::<test-or-rule-id>', got {target}"],
                        )
                    )
                    continue
                if target_path_text.startswith("/") or ".." in target_path.parts:
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-invalid",
                            message=f"{rel} invariant {entry['id']} has an invalid enforcement path.",
                            details=[target],
                        )
                    )
                    continue

                resolved = root / target_path
                if not resolved.exists():
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-missing-file",
                            message=f"{rel} invariant {entry['id']} references a missing enforcement file.",
                            details=[target_path_text],
                        )
                    )
                elif target_anchor not in resolved.read_text(encoding="utf-8"):
                    violations.append(
                        Violation(
                            code="contract-invariant-enforcement-anchor-missing-file",
                            message=f"{rel} invariant {entry['id']} references an enforcement anchor that is not present.",
                            details=[target],
                        )
                    )

    return violations


def _parse_authz_contract_rows(text: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    current: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if line.startswith("- id:"):
            if current:
                rows.append(current)
            current = {"id": line.split(":", 1)[1].strip().strip('"')}
        elif current and ":" in line:
            key, value = line.split(":", 1)
            current[key.strip()] = value.strip().strip('"')
    if current:
        rows.append(current)
    return rows


def _java_admin_matrix_paths(java_text: str) -> set[str]:
    constants = {
        name: value
        for name, value in re.findall(r'private\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"', java_text)
    }
    paths: set[str] = set()
    for match in re.finditer(r"\.requestMatchers\((.*?)\)\s*\.hasRole\(ROLE_ADMIN\)", java_text, re.DOTALL):
        block = match.group(1)
        paths.update(value for value in re.findall(r'"(/api/v1/[^"]+)"', block))
        for token in re.findall(r"\b[A-Z][A-Z0-9_]+\b", block):
            if token in constants and constants[token].startswith("/api/v1/"):
                paths.add(constants[token])
    return paths


def run_authz_matrix_sync_check(root: Path = REPO_ROOT) -> list[Violation]:
    matrix_path = root / "contracts" / "authz" / "path-matrix.yaml"
    java_path = root / "backend" / "src" / "main" / "java" / "com" / "keplerops" / "groundcontrol" / "shared" / "security" / "ApiPathMatrix.java"
    if not matrix_path.exists() or not java_path.exists():
        return [
            Violation(
                code="authz-matrix-source-missing",
                message="Authz matrix sync check needs contracts/authz/path-matrix.yaml and ApiPathMatrix.java.",
            )
        ]

    rows = _parse_authz_contract_rows(matrix_path.read_text(encoding="utf-8"))
    contract_admin_paths = {row.get("path", "") for row in rows if row.get("access") == "ROLE_ADMIN"}
    contract_admin_paths.discard("")
    java_admin_paths = _java_admin_matrix_paths(java_path.read_text(encoding="utf-8"))

    violations: list[Violation] = []
    missing_from_contract = sorted(java_admin_paths - contract_admin_paths)
    missing_from_java = sorted(contract_admin_paths - java_admin_paths)
    if missing_from_contract or missing_from_java:
        details: list[str] = []
        if missing_from_contract:
            details.append(f"admin paths in ApiPathMatrix.java but not contracts/authz/path-matrix.yaml: {missing_from_contract}")
        if missing_from_java:
            details.append(f"admin paths in contracts/authz/path-matrix.yaml but not ApiPathMatrix.java: {missing_from_java}")
        violations.append(
            Violation(
                code="authz-matrix-drift",
                message="contracts/authz/path-matrix.yaml drifted from ApiPathMatrix.java.",
                details=details,
            )
        )
    return violations


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


# ---------------------------------------------------------------------------
# Traceability-reconciliation gate contract (issues #1058, #1103; ADR-089/#1346)
#
# Asserts the /implement workflow's traceability + post-merge close gate
# is wired across all prose surfaces. The MCP-tool layer enforces:
#   - Step 17 calls gc_assert_completion, which sequences
#     gc_assert_traceability_reconciled (posting traceability_reconciled) and
#     gc_post_final_report in one deterministic call. plain_english_outcome
#     is required for the user-facing closeout.
#   - Step 20 (Phase E) calls gc_close_issue_after_merge after PR merge and
#     performs only linked-PR resolution, merge-state verification, and
#     idempotent closure — no GRC assertion and no next-issue recommendation
#     (both retired by ADR-089).
#   - SKILL.md documents Phase E and gc_close_issue_after_merge as the
#     canonical close path.
#
# This check is the prose-side guardrail that catches drift between the
# enforcement and the contract documentation. A future skill edit that
# strips one of these references would silently break the contract for
# any reader of the skill; the check prevents that.
# ---------------------------------------------------------------------------

IMPLEMENT_STEP_17_PATH = "skills/implement/steps/step-17-completion.md"
IMPLEMENT_STEP_20_PATH = "skills/implement/steps/step-20-close-issue-on-merge.md"


def run_traceability_reconciliation_gate_contract(
    *,
    root: Path = REPO_ROOT,
) -> list[Violation]:
    """Assert the traceability + closeout gate prose surfaces are wired.

    The gate has two MCP-tool surfaces and three prose anchors:

      step-17-completion.md   must mention `gc_assert_completion` and
                              `traceability_reconciled` and `plain_english_outcome`
      step-20-close-issue-on-merge.md must exist AND mention
                              `gc_close_issue_after_merge`
      SKILL.md                must mention `Phase E` and
                              `gc_close_issue_after_merge`

    Emits one violation per missing anchor with a stable code so CI surfaces
    the specific gap. A repo whose policy-tests file isn't yet up to date
    (e.g., the test fixture path needs a workflow run) flags here rather
    than going silent.
    """
    violations: list[Violation] = []

    requirements = (
        (
            IMPLEMENT_STEP_17_PATH,
            ("gc_assert_completion", "traceability_reconciled", "plain_english_outcome"),
            "traceability-gate-step17-missing",
            "Step 17 must use gc_assert_completion with traceability_reconciled and plain_english_outcome (issue #1103).",
        ),
        (
            IMPLEMENT_STEP_20_PATH,
            ("gc_close_issue_after_merge",),
            "traceability-gate-step20-missing",
            "Step 20 (Phase E post-merge close) must exist and mention gc_close_issue_after_merge (issue #1058).",
        ),
        (
            IMPLEMENT_SKILL_PATH,
            ("Phase E", "gc_close_issue_after_merge"),
            "traceability-gate-skill-missing",
            "SKILL.md must document Phase E and the gc_close_issue_after_merge close path (issue #1058).",
        ),
    )

    for rel_path, tokens, code, message in requirements:
        path = root / rel_path
        if not path.exists():
            violations.append(
                Violation(
                    code=code,
                    message=f"{rel_path} is missing — required by the issue #1058 traceability gate contract.",
                    details=[f"expected at {rel_path}", *(f"must mention: {t}" for t in tokens)],
                )
            )
            continue
        text = path.read_text(encoding="utf-8")
        missing_tokens = [t for t in tokens if t not in text]
        if missing_tokens:
            violations.append(
                Violation(
                    code=code,
                    message=message,
                    details=[f"in {rel_path}", *(f"missing: '{t}'" for t in missing_tokens)],
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
    parser.add_argument(
        "--pr-comments-json",
        help=(
            "Path to sanitized PR issue comments as a JSON array or JSONL "
            "objects with body and author fields. CI uses this so PR-head "
            "policy code does not receive a GitHub token."
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


def parse_routing_agents(text: str) -> dict[str, str]:
    """Map each ``routing.stages.<stage>`` to its resolved ``agent`` value.

    A stage with no explicit ``agent:`` key resolves to ``"subagent"`` — the
    ``gc_resolve_workflow_route`` default. Indentation-based parse (no YAML
    dependency) to match the stdlib-only policy framework; the routing block is
    machine-maintained and regularly two-space-indented. Returns an empty dict
    when there is no ``routing.stages`` block.
    """
    agents: dict[str, str] = {}
    in_routing = False
    in_stages = False
    current_stage: str | None = None
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if indent == 0:
            in_routing = stripped.startswith("routing:")
            in_stages = False
            current_stage = None
            continue
        if not in_routing:
            continue
        if indent == 2:
            in_stages = stripped.startswith("stages:")
            current_stage = None
            continue
        if not in_stages:
            continue
        if indent == 4 and stripped.endswith(":"):
            current_stage = stripped[:-1].strip()
            agents.setdefault(current_stage, "subagent")
            continue
        if indent == 6 and current_stage is not None and stripped.startswith("agent:"):
            value = stripped.split(":", 1)[1].strip()
            if " #" in value:  # strip any inline comment
                value = value.split(" #", 1)[0].strip()
            if value:
                agents[current_stage] = value
    return agents


def run_workflow_routing_contract(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert async-poll /implement routing stages resolve to ``agent: parent``.

    A static post-condition (independent of ``changed_files``) so any edit that
    routes a ``gc_codex_job`` poll-loop stage back to a subagent fails
    ``make policy`` (issue #1168). Emits:
      ``workflow-routing-config-missing``    — ``.ground-control.yaml`` is absent.
      ``workflow-routing-stage-missing``     — a poll-loop stage is not declared.
      ``workflow-routing-poll-loop-subagent`` — a poll-loop stage resolves to a
                                                subagent instead of the parent.
    """
    violations: list[Violation] = []
    config_path = root / GROUND_CONTROL_YAML_PATH
    if not config_path.exists():
        violations.append(
            Violation(
                code="workflow-routing-config-missing",
                message=".ground-control.yaml is missing — workflow routing cannot be verified.",
                details=[f"expected at {GROUND_CONTROL_YAML_PATH.as_posix()}"],
            )
        )
        return violations

    agents = parse_routing_agents(config_path.read_text(encoding="utf-8"))
    if not agents:
        # No routing.stages block (routing not configured); nothing to enforce.
        return violations

    for stage in sorted(POLL_LOOP_ROUTING_STAGES):
        if stage not in agents:
            violations.append(
                Violation(
                    code="workflow-routing-stage-missing",
                    message=f"Routing stage '{stage}' is missing from .ground-control.yaml routing.stages.",
                    details=[
                        "poll-loop stages must be declared and routed to agent: parent (issue #1168)",
                    ],
                )
            )
            continue
        if agents[stage] != "parent":
            violations.append(
                Violation(
                    code="workflow-routing-poll-loop-subagent",
                    message=f"Routing stage '{stage}' drives a gc_codex_job poll loop and must use agent: parent.",
                    details=[
                        f"resolved agent: {agents[stage]}",
                        "a dispatched subagent cannot resume on the background-sleep poll notification (issue #1168)",
                    ],
                )
            )
    return violations


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
    violations.extend(run_migration_policy(changed_files, base=args.base))
    violations.extend(run_changelog_fragment_check(changed_files))
    violations.extend(run_ci_strictness_contract())
    violations.extend(run_deploy_compose_credential_passthrough())
    violations.extend(run_ghcr_namespace_drift())
    violations.extend(run_repo_identity_drift())
    violations.extend(run_deploy_artifact_consistency())
    violations.extend(run_methodology_catalog_drift())
    violations.extend(run_enum_contract_check())
    violations.extend(run_ontology_binding_check())
    violations.extend(run_contract_surface_check())
    violations.extend(run_contract_invariant_enforcement_check())
    violations.extend(run_authz_matrix_sync_check())
    violations.extend(run_workflow_routing_contract())
    violations.extend(run_test_quality_decision_record_contract())
    violations.extend(run_traceability_reconciliation_gate_contract())

    base_ref, head_ref = _resolve_pr_refs(args)
    if args.skip_pr_body or _is_release_pr(base_ref, head_ref):
        # The dev -> main release PR aggregates feature PRs that each already
        # satisfied the body contract on the way into dev; re-imposing it (and
        # the ## Documentation outcome) on the aggregate is redundant ceremony
        # that fails every release. The changed-file checks above still run.
        violations.extend(run_documentation_coverage_check(changed_files, pr_body=None))
    else:
        body = _resolve_pr_body(args)
        if body is not None:
            # check_pr_body composes the no-deferral check (ADR-029) so all
            # PR-body validation routes share the same contract.
            violations.extend(check_pr_body(body))
            violations.extend(run_documentation_coverage_check(changed_files, pr_body=body))
        else:
            violations.extend(run_documentation_coverage_check(changed_files, pr_body=None))

    return render_and_exit(violations)


def _resolve_pr_body(args: argparse.Namespace) -> str | None:
    """Resolve the PR body string from CLI args / environment, in priority order.

    1. ``--pr-body-file`` — local pre-push hook driver.
    2. ``--event-path`` or ``GITHUB_EVENT_PATH`` — CI driver.
    3. ``--pr-number`` — fetched via ``gh pr view <n> --json body``.

    Returns ``None`` when no source is configured (the check is skipped).
    """
    if args.pr_body_file:
        return Path(args.pr_body_file).read_text(encoding="utf-8")
    event_path = args.event_path or os.getenv("GITHUB_EVENT_PATH")
    if event_path:
        event = json.loads(Path(event_path).read_text(encoding="utf-8"))
        pull_request = event.get("pull_request") or {}
        return pull_request.get("body") or ""
    if args.pr_number is not None:
        result = subprocess.run(
            ["gh", "pr", "view", str(args.pr_number), "--json", "body", "--jq", ".body"],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout
    return None


# The integration -> release branch pair whose PR aggregates already-merged
# feature PRs. Such a release PR carries no single requirement/traceability of
# its own, so the per-PR body contract does not apply to it.
RELEASE_PR_BASE = "main"
RELEASE_PR_HEAD = "dev"


def _resolve_pr_refs(args: argparse.Namespace) -> tuple[str | None, str | None]:
    """Best-effort ``(base_ref, head_ref)`` for the PR under check.

    Sourced from the GitHub event payload or ``--pr-number`` (``gh pr view``),
    mirroring ``_resolve_pr_body``. Returns ``(None, None)`` when the refs cannot
    be determined (e.g. the local pre-push driver), so the body contract applies
    by default — only a positively-identified release PR is exempted.
    """
    event_path = args.event_path or os.getenv("GITHUB_EVENT_PATH")
    if event_path:
        try:
            event = json.loads(Path(event_path).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None, None
        pull_request = event.get("pull_request") or {}
        base = (pull_request.get("base") or {}).get("ref")
        head = (pull_request.get("head") or {}).get("ref")
        return base, head
    if args.pr_number is not None:
        try:
            result = subprocess.run(
                ["gh", "pr", "view", str(args.pr_number), "--json", "baseRefName,headRefName"],
                check=True,
                capture_output=True,
                text=True,
            )
            data = json.loads(result.stdout)
            return data.get("baseRefName"), data.get("headRefName")
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            return None, None
    return None, None


def _is_release_pr(base_ref: str | None, head_ref: str | None) -> bool:
    """True for the ``dev`` -> ``main`` release PR (aggregate of merged feature PRs)."""
    return base_ref == RELEASE_PR_BASE and head_ref == RELEASE_PR_HEAD


if __name__ == "__main__":
    raise SystemExit(main())
