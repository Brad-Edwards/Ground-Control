"""Structural invariants for the CI verification topology (ADR-091, issue #1461).

These tests are the executable gate behind the issue's acceptance criteria. They
run inside `make policy` (via `policy-tests`) and inside the CI `policy` job,
so a regression in the job graph fails a required check rather than being caught
by review.
"""

import json
import pathlib
import unittest

import yaml

from tools.policy.checks import CI_STRICTNESS_REQUIRED_CONTEXTS

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"
BRANCH_PROTECTION_BASELINE = REPO_ROOT / ".github" / "branch-protection-baseline.json"

# Required contexts reported by apps other than GitHub Actions. They have no job
# in ci.yml by construction, so job-coverage assertions exclude them.
EXTERNAL_REQUIRED_CONTEXTS = frozenset(
    {
        "GitGuardian Security Checks",
        "SonarCloud Code Analysis",
    }
)

# Jobs that legitimately declare `needs`, mapped to the exact set each may
# declare. `docker` is excluded here and checked against the gate set below,
# because its dependency list is derived rather than fixed.
EXPECTED_JOB_DEPENDENCIES = {
    # Sequences the only secret-bearing job behind the repo policy gate.
    "policy-live": {"policy"},
    # Exercises the image that `docker` publishes.
    "smoke": {"docker"},
}

# Jobs that do not gate the image publish, each with the reason it is safe to
# exclude. Every other job in the workflow must appear in `docker.needs`, so a
# new verification lane has to be either gated or explicitly excused here.
DOCKER_GATE_EXCLUSIONS = {
    # The publish job itself, and the smoke test that consumes its output.
    "docker",
    "smoke",
    # Skipped on every PR and on dev: it is main-only and needs GC_BASE_URL.
    # GitHub skips a job whose `needs` entry was skipped, so gating the image
    # publish on this one would stop it publishing at all.
    "policy-live",
    # Advisory lane whose checks are a strict subset of `build` and `test`.
    "fast-feedback",
}


def load_workflow() -> dict:
    return yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))


def job_needs(job: dict) -> set:
    needs = job.get("needs") or []
    if isinstance(needs, str):
        return {needs}
    return set(needs)


class CiTopologyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = load_workflow()
        self.jobs = self.workflow["jobs"]

    def test_every_required_actions_context_has_a_ci_job(self) -> None:
        """A required context with no job behind it blocks every PR forever.

        Branch protection waits for a check that never reports, so `main` and
        `dev` deadlock. This fires if a job is renamed or deleted without
        updating the baseline, and it is what caught the `mutation` context left
        behind by commit bf766bfe.
        """
        expected_jobs = set(CI_STRICTNESS_REQUIRED_CONTEXTS) - EXTERNAL_REQUIRED_CONTEXTS
        missing = sorted(expected_jobs - set(self.jobs))
        self.assertEqual(
            missing,
            [],
            msg=f"required contexts with no job in ci.yml: {missing}",
        )

    def test_branch_protection_baseline_matches_the_policy_contract(self) -> None:
        baseline = json.loads(BRANCH_PROTECTION_BASELINE.read_text(encoding="utf-8"))
        for branch, config in baseline["branches"].items():
            contexts = set(config["required_status_checks"]["contexts"])
            self.assertEqual(
                contexts,
                set(CI_STRICTNESS_REQUIRED_CONTEXTS),
                msg=f"{branch} baseline contexts drifted from CI_STRICTNESS_REQUIRED_CONTEXTS",
            )

    def test_verification_jobs_declare_no_serial_dependencies(self) -> None:
        """Independent checks must not wait on unrelated stages (issue #1461).

        Every verification job rebuilds from a fresh checkout and consumes no
        predecessor's artifact, so any `needs` edge outside
        EXPECTED_JOB_DEPENDENCIES is pure serialization.
        """
        offenders = {
            name: sorted(job_needs(job))
            for name, job in self.jobs.items()
            if name not in EXPECTED_JOB_DEPENDENCIES and name != "docker" and job_needs(job)
        }
        self.assertEqual(
            offenders,
            {},
            msg=f"jobs serialized behind unrelated stages: {offenders}",
        )

    def test_side_effect_jobs_keep_exactly_their_declared_dependencies(self) -> None:
        """The three surviving edges each guard a side effect, not ordering."""
        for name, expected in EXPECTED_JOB_DEPENDENCIES.items():
            self.assertEqual(
                job_needs(self.jobs[name]),
                expected,
                msg=f"{name} dependencies drifted from the documented set",
            )

    def test_docker_publishes_only_behind_every_verification_gate(self) -> None:
        """Flattening removes transitive gating, so docker names every gate.

        The expected set is derived from the workflow's own job list rather
        than from the required-context set, because a verification job can gate
        the image publish without being a required branch-protection context.
        `mcp-contract` is exactly that case: dropping it from `docker.needs`
        would let an image publish past a red MCP write-contract check.
        """
        gate_jobs = set(self.jobs) - DOCKER_GATE_EXCLUSIONS
        docker_needs = job_needs(self.jobs["docker"])
        missing = sorted(gate_jobs - docker_needs)
        self.assertEqual(
            missing,
            [],
            msg=f"docker can publish while these gates are unreported: {missing}",
        )
        self.assertIn("mcp-contract", docker_needs)

    def test_fast_feedback_lane_exists_and_is_not_a_second_merge_gate(self) -> None:
        """The fast lane is additive signal, never a shadow policy.

        Making it required would create a second merge authority covering a
        subset of the full suite.
        """
        self.assertIn("fast-feedback", self.jobs)
        self.assertEqual(job_needs(self.jobs["fast-feedback"]), set())
        self.assertNotIn("fast-feedback", CI_STRICTNESS_REQUIRED_CONTEXTS)

    def test_frontend_lane_verifies_lint_tests_and_build(self) -> None:
        """The frontend lane is the only thing checking `frontend/` (issue #1468).

        The generic assertions above cannot catch its removal: deleting the job,
        the policy constant, and the baseline entry together leaves them all
        internally consistent and silent. `frontend/` is otherwise compiled only
        inside the Docker image build, which runs after merge and runs neither
        lint nor tests.
        """
        self.assertIn("frontend", self.jobs)
        job = self.jobs["frontend"]

        self.assertEqual(job_needs(job), set(), "frontend must start at t=0")
        self.assertNotIn("if", job, "a conditional lane cannot be a merge gate")

        commands = " ".join(str(step.get("run", "")) for step in job["steps"])
        self.assertIn("npm ci", commands)
        self.assertIn("run lint", commands)
        self.assertIn("test", commands)
        self.assertIn("run build", commands)

    def test_frontend_lane_is_a_required_merge_gate(self) -> None:
        self.assertIn("frontend", CI_STRICTNESS_REQUIRED_CONTEXTS)

    def test_frontend_lane_runs_without_write_permissions(self) -> None:
        """It installs PR-controlled dependencies and runs npm lifecycle scripts.

        The workflow-level grant includes `packages: write`,
        `pull-requests: write`, and `id-token: write`; none of that may reach
        this job.
        """
        self.assertEqual(self.jobs["frontend"].get("permissions"), {"contents": "read"})

    def test_sonar_job_keeps_its_coverage_and_quality_gate_inputs(self) -> None:
        """Trimming Sonar's redundant build must not drop its actual inputs."""
        sonar_steps = " ".join(
            str(step.get("run", "")) for step in self.jobs["sonar"]["steps"]
        )
        self.assertIn("jacocoTestReport", sonar_steps)
        self.assertIn("-Dsonar.qualitygate.wait=true", sonar_steps)
        self.assertIn("tools/sonar/assert_no_new_issues.py", sonar_steps)


if __name__ == "__main__":
    unittest.main()
