"""Policy checks: workflow routing.

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
    read_changed_files,
    run_adr_guard,
    run_controller_contracts,
)
from .authz_matrix import (
    RELEASE_PLEASE_PR_HEAD_PREFIX,
    RELEASE_PR_BASE,
    RELEASE_PR_HEAD,
    SYNC_PR_BASE,
    SYNC_PR_HEAD,
    _resolve_pr_body,
    run_implement_execution_contract,
)
from .ci_contract import (
    run_repo_identity_drift,
)
from .contract_surface import (
    run_test_quality_decision_record_contract,
)
from .controllers import (
    run_migration_policy,
)
from .deploy_artifacts import (
    run_deploy_artifact_consistency,
    run_methodology_catalog_drift,
)
from .enum_contract import (
    run_ontology_binding_check,
)
from .env_templates import (
    run_enum_contract_check,
)
from .measurement import (
    parse_args,
    render_and_exit,
    run_traceability_reconciliation_gate_contract,
    run_workflow_routing_contract,
    write_violations_json,
)
from .migrations import (
    run_documentation_coverage_check,
    run_version_mirror_consistency_check,
)
from .ontology_binding import (
    run_measurement_catalogue_check,
)
from .ontology_crosswalk import (
    check_pr_body,
    run_authz_matrix_sync_check,
    run_contract_invariant_enforcement_check,
)
from .ontology_families import (
    run_contract_surface_check,
    run_ontology_crosswalk_check,
)
from .version_mirror import (
    run_ci_strictness_contract,
    run_deploy_compose_credential_passthrough,
    run_ghcr_namespace_drift,
)


def main(argv: list[str] | None = None) -> int:
    started = time.monotonic()
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
    violations.extend(run_adr_guard(changed_files, base=args.base))
    violations.extend(run_controller_contracts(changed_files))
    violations.extend(run_migration_policy(changed_files, base=args.base))
    violations.extend(run_version_mirror_consistency_check())
    violations.extend(run_ci_strictness_contract())
    violations.extend(run_deploy_compose_credential_passthrough())
    violations.extend(run_ghcr_namespace_drift())
    violations.extend(run_repo_identity_drift())
    violations.extend(run_deploy_artifact_consistency())
    violations.extend(run_methodology_catalog_drift())
    violations.extend(run_enum_contract_check())
    violations.extend(run_ontology_binding_check())
    violations.extend(run_ontology_crosswalk_check())
    violations.extend(run_contract_surface_check())
    violations.extend(run_contract_invariant_enforcement_check())
    violations.extend(run_measurement_catalogue_check())
    violations.extend(run_authz_matrix_sync_check())
    violations.extend(run_workflow_routing_contract())
    violations.extend(run_implement_execution_contract())
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

    if args.json_out:
        write_violations_json(
            args.json_out, violations, int((time.monotonic() - started) * 1000)
        )
    return render_and_exit(violations)


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
    """True for automation PRs exempt from the per-PR body contract (GC-P027).

    Covers the ``dev`` -> ``main`` promotion, the Release Please release PR, and the
    ``main`` -> ``dev`` back-merge PR.
    """
    if base_ref == RELEASE_PR_BASE and head_ref == RELEASE_PR_HEAD:
        return True
    if (
        base_ref == RELEASE_PR_BASE
        and head_ref is not None
        and head_ref.startswith(RELEASE_PLEASE_PR_HEAD_PREFIX)
    ):
        return True
    return base_ref == SYNC_PR_BASE and head_ref == SYNC_PR_HEAD
