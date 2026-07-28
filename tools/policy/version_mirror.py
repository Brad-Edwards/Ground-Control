"""Policy checks: version mirror.

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
from .controllers import (
    _extract_compose_backend_env_entries,
)
from .core import (
    require_scanned,
    BRANCH_PROTECTION_BASELINE_PATH,
    CI_PRE_COMMIT_HOOKS,
    CI_STRICTNESS_BRANCHES,
    CI_STRICTNESS_REQUIRED_CONTEXTS,
    CI_WORKFLOW_PATH,
    DEPLOY_COMPOSE_PROD_PATH,
    PRE_COMMIT_CONFIG_PATH,
    REPO_ROOT,
    REQUIRED_ADR026_BACKEND_ENV_KEYS,
    REQUIRED_ADR026_INHERIT_ONLY_KEYS,
    SONAR_NEW_ISSUE_GATE_PATH,
    Violation,
)


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


def run_ghcr_namespace_drift(root: Path = REPO_ROOT) -> list[Violation]:
    """Assert every inventoried artifact names the canonical GHCR namespace.

    A non-canonical `ghcr.io/<ns>/ground-control` reference in any inventoried
    deploy/CI/doc file is the drift that froze red-dragon's deploy silently
    (#953). Absent inventory files are skipped — the gate catches drift in the
    files that exist, it does not assert their presence (other checks own
    file-existence post-conditions).
    """
    offenders: list[str] = []
    scanned = 0
    for rel_path in GHCR_NAMESPACE_INVENTORY:
        file_path = root / rel_path
        if not file_path.exists():
            continue
        scanned += 1
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
    guard = require_scanned("GHCR namespace inventory", scanned)
    if guard:
        return guard
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
