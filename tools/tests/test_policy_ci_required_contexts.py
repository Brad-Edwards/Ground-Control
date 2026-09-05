"""Contract tests for the required-status-context gate (GC-P030, ADR-091).

A required status check with no job behind it never reports, so every pull
request stays blocked forever. That is what happened when the `mutation` job was
deleted while its context stayed declared (#1461), and again across the #1500
re-platform, when `build`, `frontend`, `integration`, `test`, and `verify` all
outlived the jobs that produced them. The gate that caught the first case went
with the CI tests it lived in, so the second case went unnoticed until #650.
"""

import json
import tempfile
import unittest
from pathlib import Path

from tools.policy.ci_strictness import (
    EXTERNALLY_POSTED_CONTEXTS,
    run_ci_required_context_contract,
)
from tools.policy.core import CI_STRICTNESS_BRANCHES, CI_STRICTNESS_REQUIRED_CONTEXTS


def _baseline(contexts, strict=True, branches=CI_STRICTNESS_BRANCHES):
    return {
        "branches": {
            branch: {
                "admin_bypass_allowed": True,
                "changes_land_via_pull_request": True,
                "required_status_checks": {"strict": strict, "contexts": sorted(contexts)},
            }
            for branch in branches
        }
    }


def _workflow(job_ids, on_pull_request=True, display_names=None, branches=("main", "dev")):
    """Render a workflow. `display_names` maps a job id to an explicit `name:`.

    `branches=None` omits the branch filter, which means the trigger matches every
    branch.
    """
    display_names = display_names or {}
    if on_pull_request:
        trigger = "  pull_request:\n"
        if branches is not None:
            trigger += f"    branches: [{', '.join(branches)}]\n"
    else:
        trigger = "  push:\n"
    jobs = ""
    for job in job_ids:
        jobs += f"  {job}:\n"
        if job in display_names:
            jobs += f"    name: {display_names[job]}\n"
        jobs += "    runs-on: ubuntu-latest\n    steps:\n      - run: true\n"
    return f"name: T\non:\n{trigger}\njobs:\n{jobs}"


class CiRequiredContextContractTest(unittest.TestCase):
    def _root(self, tmp_dir, *, contexts=None, produced=None, strict=True, branches=None):
        root = Path(tmp_dir)
        contexts = CI_STRICTNESS_REQUIRED_CONTEXTS if contexts is None else contexts
        produced = sorted(set(contexts) - EXTERNALLY_POSTED_CONTEXTS) if produced is None else produced
        (root / ".github" / "workflows").mkdir(parents=True)
        (root / ".github" / "branch-protection-baseline.json").write_text(
            json.dumps(_baseline(contexts, strict=strict, branches=branches or CI_STRICTNESS_BRANCHES)),
            encoding="utf-8",
        )
        (root / ".github" / "workflows" / "ci.yml").write_text(_workflow(produced), encoding="utf-8")
        return root

    def test_accepts_a_baseline_whose_every_context_has_a_producer(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            violations = run_ci_required_context_contract(root=self._root(tmp_dir))
            self.assertEqual(
                violations, [], msg=f"unexpected: {[v.render() for v in violations]}"
            )

    def test_rejects_a_required_context_no_job_produces(self):
        # The #1461 and #650 failure: the job was deleted, the context stayed.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir)
            (root / ".github" / "workflows" / "ci.yml").write_text(
                _workflow(sorted(set(CI_STRICTNESS_REQUIRED_CONTEXTS) - EXTERNALLY_POSTED_CONTEXTS - {"policy"})),
                encoding="utf-8",
            )
            violations = run_ci_required_context_contract(root=root)
            self.assertIn("ci-required-context-unproduced", {v.code for v in violations})
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("policy", details)

    def test_rejects_a_baseline_that_drifts_from_the_declared_contract(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir, contexts=set(CI_STRICTNESS_REQUIRED_CONTEXTS) | {"integration"})
            violations = run_ci_required_context_contract(root=root)
            self.assertIn("ci-required-context-baseline-drift", {v.code for v in violations})
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("integration", details)

    def test_rejects_a_non_strict_branch(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir, strict=False)
            violations = run_ci_required_context_contract(root=root)
            self.assertIn("ci-required-context-not-strict", {v.code for v in violations})

    def test_rejects_a_missing_protected_branch(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir, branches=("main",))
            violations = run_ci_required_context_contract(root=root)
            self.assertIn("ci-required-context-branch-missing", {v.code for v in violations})
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("dev", details)

    def test_rejects_an_externally_posted_entry_that_is_not_required(self):
        # The allowlist is shrink-only: it exempts a context from needing a local
        # job, so an entry that no longer appears in the required set would
        # silently widen the exemption.
        self.assertTrue(
            EXTERNALLY_POSTED_CONTEXTS <= CI_STRICTNESS_REQUIRED_CONTEXTS,
            "every externally-posted context must still be a required context",
        )

    def test_uses_the_display_name_when_a_job_sets_one(self):
        # GitHub reports `jobs.<id>.name` when present, so branch protection waits
        # on the display name, not the id. A gate that compared ids would pass here
        # while every pull request blocked forever on a context nothing reports.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir)
            produced = sorted(set(CI_STRICTNESS_REQUIRED_CONTEXTS) - EXTERNALLY_POSTED_CONTEXTS)
            (root / ".github" / "workflows" / "ci.yml").write_text(
                _workflow(produced, display_names={"policy": "Repo policy"}), encoding="utf-8"
            )
            violations = run_ci_required_context_contract(root=root)
            self.assertIn("ci-required-context-unproduced", {v.code for v in violations})
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("policy", details)

    def test_a_job_whose_display_name_is_the_required_context_satisfies_it(self):
        # The inverse of the previous test: the reported name is what counts, so a
        # job whose id differs but whose `name:` is the required context satisfies
        # it. Resolving ids alone would reject this valid arrangement.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir)
            others = sorted(set(CI_STRICTNESS_REQUIRED_CONTEXTS) - EXTERNALLY_POSTED_CONTEXTS - {"policy"})
            (root / ".github" / "workflows" / "ci.yml").write_text(
                _workflow(others + ["repo-policy"], display_names={"repo-policy": "policy"}),
                encoding="utf-8",
            )
            violations = [
                v for v in run_ci_required_context_contract(root=root)
                if v.code == "ci-required-context-unproduced"
            ]
            self.assertEqual(
                violations, [], msg=f"unexpected: {[v.render() for v in violations]}"
            )

    def test_a_matrix_templated_name_does_not_satisfy_a_required_context(self):
        # `name: policy (${{ matrix.os }})` expands per leg, so no single reported
        # context equals the requirement. Guessing the id would be a false pass.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir)
            produced = sorted(set(CI_STRICTNESS_REQUIRED_CONTEXTS) - EXTERNALLY_POSTED_CONTEXTS)
            (root / ".github" / "workflows" / "ci.yml").write_text(
                _workflow(produced, display_names={"policy": "policy (${{ matrix.os }})"}),
                encoding="utf-8",
            )
            violations = run_ci_required_context_contract(root=root)
            self.assertIn("ci-required-context-unproduced", {v.code for v in violations})

    def test_rejects_a_producer_that_excludes_one_protected_branch(self):
        # A pull_request trigger filtered to `dev` never runs for a `main` pull
        # request, so `main` requires a check nothing produces and its pull
        # requests hang. Pooling producers across workflows hides this.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir)
            produced = sorted(set(CI_STRICTNESS_REQUIRED_CONTEXTS) - EXTERNALLY_POSTED_CONTEXTS)
            (root / ".github" / "workflows" / "ci.yml").write_text(
                _workflow(produced, branches=("dev",)), encoding="utf-8"
            )
            violations = run_ci_required_context_contract(root=root)
            self.assertIn("ci-required-context-unproduced", {v.code for v in violations})
            details = " ".join(d for v in violations for d in v.details)
            self.assertIn("main", details)

    def test_accepts_producers_split_across_branch_filtered_workflows(self):
        # Splitting the same checks across a main-only and a dev-only workflow
        # satisfies both branches; the gate must not demand one workflow do both.
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir)
            produced = sorted(set(CI_STRICTNESS_REQUIRED_CONTEXTS) - EXTERNALLY_POSTED_CONTEXTS)
            (root / ".github" / "workflows" / "ci.yml").write_text(
                _workflow(produced, branches=("main",)), encoding="utf-8"
            )
            (root / ".github" / "workflows" / "ci-dev.yml").write_text(
                _workflow(produced, branches=("dev",)), encoding="utf-8"
            )
            violations = [
                v for v in run_ci_required_context_contract(root=root)
                if v.code == "ci-required-context-unproduced"
            ]
            self.assertEqual(
                violations, [], msg=f"unexpected: {[v.render() for v in violations]}"
            )

    def test_an_unfiltered_trigger_covers_every_protected_branch(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir)
            produced = sorted(set(CI_STRICTNESS_REQUIRED_CONTEXTS) - EXTERNALLY_POSTED_CONTEXTS)
            (root / ".github" / "workflows" / "ci.yml").write_text(
                _workflow(produced, branches=None), encoding="utf-8"
            )
            violations = [
                v for v in run_ci_required_context_contract(root=root)
                if v.code == "ci-required-context-unproduced"
            ]
            self.assertEqual(
                violations, [], msg=f"unexpected: {[v.render() for v in violations]}"
            )

    def test_fails_closed_when_no_workflow_resolves(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            root = self._root(tmp_dir)
            (root / ".github" / "workflows" / "ci.yml").unlink()
            violations = run_ci_required_context_contract(root=root)
            self.assertTrue(violations, "a scan that resolved no workflow must fail, not pass")

    def test_the_real_repository_satisfies_the_contract(self):
        violations = run_ci_required_context_contract()
        self.assertEqual(violations, [], msg=f"{[v.render() for v in violations]}")


if __name__ == "__main__":
    unittest.main()
