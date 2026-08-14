"""The MCP ESLint gate must be reachable AND enforcing on the repo-native path (issue #255).

Post-#1500 CI ran the MCP node test suite but never `npm --prefix mcp/ground-control
run lint`, so an ESLint regression could not fail a required check. These tests lock
the wiring so it cannot silently disappear again: one canonical `mcp-lint` Make target
owns the invocation, `make policy` (the documented guardrail) depends on it, and CI's
required `policy` job runs the same target.

Presence of the command string is not enough — a gate is only real if it can still
fail. So each site is also checked against the common neutralizers that would leave the
exact text present while stopping it from ever blocking: a trailing no-op shell operator
(`|| true`, `; exit 0`), a Make recipe that ignores its own errors (a leading `-`), or a
CI `continue-on-error: true` / step-level `if:` that skips the step. The ESLint warning
count is deliberately NOT asserted — it is advisory (`eslint-plugin-security` recommends
warnings) and unrelated to this contract.
"""

import json
import unittest

import yaml

from tools.policy.core import REPO_ROOT

LINT_NPM_COMMAND = "npm --prefix mcp/ground-control run lint"
MAKE_LINT_TARGET = "mcp-lint"

# Trailing no-op operators that leave a command textually present while stopping it
# from ever failing the check it is supposed to be.
_SHELL_NEUTRALIZERS = ("|| true", "|| :", "|| exit 0", "; true", "; exit 0", "; :")


def _shell_neutralizer(command: str) -> str | None:
    """Return the no-op operator that would neutralize `command`, or None if clean."""
    for token in _SHELL_NEUTRALIZERS:
        if token in command:
            return token
    return None


def _make_recipe_ignores_errors(recipe_line: str) -> bool:
    """True when a Make recipe line suppresses its own failure via a leading `-`.

    Make's `@` silences the echo and `-` ignores the exit status; they can be combined
    in either order, so strip a leading `@` before checking for the error-ignoring `-`.
    """
    return recipe_line.lstrip("@").startswith("-")


def _recipe_for(makefile_text: str, target: str) -> list[str] | None:
    """Return a Make target's recipe lines (tab stripped), or None if absent."""
    lines = makefile_text.splitlines()
    for index, line in enumerate(lines):
        if line.startswith(f"{target}:"):
            recipe: list[str] = []
            for follow in lines[index + 1 :]:
                if follow.startswith("\t"):
                    recipe.append(follow[1:])
                else:
                    break
            return recipe
    return None


def _prerequisites_for(makefile_text: str, target: str) -> list[str] | None:
    """Return a Make target's prerequisite names, or None if the target is absent."""
    for line in makefile_text.splitlines():
        if line.startswith(f"{target}:"):
            after_colon = line[len(target) + 1 :].split("##", 1)[0]
            return after_colon.split()
    return None


class NeutralizerDetectionTest(unittest.TestCase):
    """The guards themselves must fire — otherwise the wiring tests give false assurance."""

    def test_shell_neutralizer_flags_trailing_noops(self) -> None:
        self.assertEqual(_shell_neutralizer("make mcp-lint || true"), "|| true")
        self.assertEqual(_shell_neutralizer("make mcp-lint ; exit 0"), "; exit 0")
        self.assertIsNone(_shell_neutralizer("make mcp-lint"))

    def test_make_recipe_ignore_errors_is_detected(self) -> None:
        self.assertTrue(_make_recipe_ignores_errors(f"-{LINT_NPM_COMMAND}"))
        self.assertTrue(_make_recipe_ignores_errors(f"@-{LINT_NPM_COMMAND}"))
        self.assertTrue(_make_recipe_ignores_errors(f"-@{LINT_NPM_COMMAND}"))
        self.assertFalse(_make_recipe_ignores_errors(LINT_NPM_COMMAND))
        self.assertFalse(_make_recipe_ignores_errors(f"@{LINT_NPM_COMMAND}"))


class McpLintGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.makefile = (REPO_ROOT / "Makefile").read_text(encoding="utf-8")
        self.workflow = yaml.safe_load(
            (REPO_ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        )

    def test_package_json_lint_script_enforces_eslint(self) -> None:
        package = json.loads(
            (REPO_ROOT / "mcp/ground-control/package.json").read_text(encoding="utf-8")
        )
        lint_script = package.get("scripts", {}).get("lint", "")
        self.assertIn("eslint", lint_script)
        self.assertIsNone(
            _shell_neutralizer(lint_script),
            "the package lint script must be able to fail",
        )

    def test_make_target_enforces_npm_lint(self) -> None:
        recipe = _recipe_for(self.makefile, MAKE_LINT_TARGET)
        self.assertIsNotNone(recipe, "Makefile is missing the `mcp-lint` target")
        self.assertIn(
            LINT_NPM_COMMAND,
            "\n".join(recipe),
            "`mcp-lint` must delegate to the package-owned npm lint script",
        )
        for line in recipe:
            self.assertFalse(
                _make_recipe_ignores_errors(line),
                f"`mcp-lint` recipe line ignores its own failure: {line!r}",
            )
            self.assertIsNone(
                _shell_neutralizer(line),
                f"`mcp-lint` recipe line neutralizes the lint: {line!r}",
            )

    def test_policy_target_depends_on_mcp_lint(self) -> None:
        prerequisites = _prerequisites_for(self.makefile, "policy")
        self.assertIsNotNone(prerequisites, "Makefile is missing the `policy` target")
        self.assertIn(
            MAKE_LINT_TARGET,
            prerequisites,
            "`make policy` (the documented guardrail) must reach the MCP lint",
        )

    def test_ci_policy_job_enforces_make_mcp_lint(self) -> None:
        policy_job = self.workflow["jobs"]["policy"]
        self.assertIsNot(
            policy_job.get("continue-on-error", False),
            True,
            "the policy job must not swallow step failures",
        )
        lint_steps = [
            step
            for step in policy_job["steps"]
            if "make mcp-lint" in step.get("run", "")
        ]
        self.assertEqual(
            len(lint_steps),
            1,
            "the required CI `policy` job must run `make mcp-lint` exactly once",
        )
        step = lint_steps[0]
        self.assertIsNone(
            _shell_neutralizer(step["run"]),
            "the CI MCP ESLint step must be able to fail",
        )
        self.assertIsNot(
            step.get("continue-on-error", False),
            True,
            "the CI MCP ESLint step must not set continue-on-error",
        )
        self.assertNotIn(
            "if",
            step,
            "the CI MCP ESLint step must run unconditionally, not behind a skip `if:`",
        )

    def test_ci_runs_on_pull_requests(self) -> None:
        # `on` round-trips through YAML as the boolean True key, so accept either form.
        triggers = self.workflow.get("on", self.workflow.get(True, {}))
        self.assertIn(
            "pull_request",
            triggers,
            "the gate must run on pull requests (issue #255 acceptance)",
        )


if __name__ == "__main__":
    unittest.main()
