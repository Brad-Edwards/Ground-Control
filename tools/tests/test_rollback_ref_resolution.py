"""Tests for scripts/rollback.sh ref resolution and validation (issue #1223).

The rollback wrapper resolves a target image ref, validates it against the same
semver-release-pin rules as validate-env.sh (ADR-063), and writes two
machine-parseable lines to stdout in --dry-run mode:

  GC_IMAGE=<resolved>
  GC_ALLOW_IMAGE_PIN=<1|unset>

These tests drive the committed script as a subprocess in --dry-run mode with
GC_ROLLBACK_LOCAL=1 and a fixture .env so no Docker / SSH / sudo is needed.

Acceptance criteria (issue #1223 part 1):
  - bare semver derives repo from current GC_IMAGE
  - full versioned ref is used as-is
  - digest ref sets GC_ALLOW_IMAGE_PIN=1
  - floating/non-version tags (:main, :latest) are rejected
  - missing arg exits with usage error
  - registry-with-port repo derivation is not confused by the port colon
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ROLLBACK_SCRIPT = REPO_ROOT / "scripts/rollback.sh"

# Standard fixture .env: versioned release pin (ADR-063).
FIXTURE_ENV_DEFAULT = "GC_IMAGE=ghcr.io/autarchy-ai/ground-control:1.0.1\n"
FIXTURE_ENV_REGISTRY_PORT = "GC_IMAGE=localhost:5959/gctest:1.0.2\n"

SHA256_DIGEST = "a" * 64

# Production is currently DIGEST-pinned (the rollback/cutover form): a bare
# version rollback must still derive repo:<version>, never repo@sha256:<version>.
FIXTURE_ENV_DIGEST_PINNED = (
    f"GC_IMAGE=ghcr.io/autarchy-ai/ground-control@sha256:{SHA256_DIGEST}\n"
)

MAKEFILE = REPO_ROOT / "Makefile"


def _run(args, gc_dir, env_content=None):
    """Invoke scripts/rollback.sh with test seam env knobs.

    GC_ROLLBACK_LOCAL=1 bypasses sudo/SSH and patches .env directly.
    A fixture .env is written to gc_dir before the subprocess is launched.
    """
    env_file = Path(gc_dir) / ".env"
    if env_content is not None:
        env_file.write_text(env_content, encoding="utf-8")

    env = dict(os.environ)
    env["GC_ROLLBACK_LOCAL"] = "1"
    env["GC_DIR"] = str(gc_dir)

    return subprocess.run(
        ["bash", str(ROLLBACK_SCRIPT), *args],
        capture_output=True,
        text=True,
        env=env,
    )


class RollbackRefResolutionTest(unittest.TestCase):
    """Ref resolution and validation tests for scripts/rollback.sh."""

    def setUp(self):
        self._td = tempfile.TemporaryDirectory()
        self.gcdir = Path(self._td.name)
        # Write default fixture .env; individual tests may override via env_content.
        (self.gcdir / ".env").write_text(FIXTURE_ENV_DEFAULT, encoding="utf-8")

    def tearDown(self):
        self._td.cleanup()

    # ------------------------------------------------------------------
    # Passing cases (--dry-run; assert resolved ref + pin flag in stdout)
    # ------------------------------------------------------------------

    def test_bare_semver_derives_repo_from_current_gc_image(self):
        """bare 1.0.0 → repo from fixture .env, pin unset."""
        result = _run(["--dry-run", "1.0.0"], self.gcdir)
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn(
            "GC_IMAGE=ghcr.io/autarchy-ai/ground-control:1.0.0", result.stdout
        )
        self.assertIn("GC_ALLOW_IMAGE_PIN=unset", result.stdout)

    def test_full_versioned_ref_is_used_as_is(self):
        """Full ref ghcr.io/…:2.0.0 is passed through unchanged; pin unset."""
        result = _run(
            ["--dry-run", "ghcr.io/autarchy-ai/ground-control:2.0.0"], self.gcdir
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn(
            "GC_IMAGE=ghcr.io/autarchy-ai/ground-control:2.0.0", result.stdout
        )
        self.assertIn("GC_ALLOW_IMAGE_PIN=unset", result.stdout)

    def test_digest_ref_sets_gc_allow_image_pin(self):
        """digest @sha256:<hex> → used as-is, GC_ALLOW_IMAGE_PIN=1."""
        digest_ref = f"ghcr.io/autarchy-ai/ground-control@sha256:{SHA256_DIGEST}"
        result = _run(["--dry-run", digest_ref], self.gcdir)
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn(f"GC_IMAGE={digest_ref}", result.stdout)
        self.assertIn("GC_ALLOW_IMAGE_PIN=1", result.stdout)

    def test_registry_port_ref_derives_repo_without_port_confusion(self):
        """bare 1.0.0 with localhost:5959/gctest:1.0.2 fixture → localhost:5959/gctest:1.0.0."""
        result = _run(["--dry-run", "1.0.0"], self.gcdir, env_content=FIXTURE_ENV_REGISTRY_PORT)
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("GC_IMAGE=localhost:5959/gctest:1.0.0", result.stdout)
        self.assertIn("GC_ALLOW_IMAGE_PIN=unset", result.stdout)

    # ------------------------------------------------------------------
    # Rejection cases (non-zero exit, stderr explains rejection)
    # ------------------------------------------------------------------

    def test_floating_tag_main_is_rejected(self):
        """:main is a floating tag and must be rejected (ADR-063)."""
        result = _run(["--dry-run", "main"], self.gcdir)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(
            result.stderr,
            msg="expected rejection message on stderr for 'main'",
        )

    def test_full_ref_with_latest_tag_is_rejected(self):
        """ghcr.io/…:latest is a floating tag and must be rejected."""
        result = _run(
            ["--dry-run", "ghcr.io/autarchy-ai/ground-control:latest"], self.gcdir
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(
            result.stderr,
            msg="expected rejection message on stderr for ':latest'",
        )

    def test_no_argument_exits_with_usage_error(self):
        """Invocation with no version-or-ref argument exits non-zero (usage)."""
        result = _run([], self.gcdir)
        self.assertNotEqual(result.returncode, 0)

    # ------------------------------------------------------------------
    # Digest-pinned current image (codex core review #1223): a bare version
    # must derive repo:<version>, not <repo>@sha256:<version>.
    # ------------------------------------------------------------------

    def test_bare_version_against_digest_pinned_current_image(self):
        """bare 1.0.0 with a digest-pinned current GC_IMAGE → repo:1.0.0 (no @sha256)."""
        result = _run(
            ["--dry-run", "1.0.0"], self.gcdir, env_content=FIXTURE_ENV_DIGEST_PINNED
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn(
            "GC_IMAGE=ghcr.io/autarchy-ai/ground-control:1.0.0", result.stdout
        )
        self.assertNotIn("@sha256", result.stdout)
        self.assertIn("GC_ALLOW_IMAGE_PIN=unset", result.stdout)

    # ------------------------------------------------------------------
    # Injection rejection (codex security review #1223): a ref carrying a
    # shell-significant character is refused before it reaches any .env / ssh
    # sink, even when it keeps a valid-looking version tag suffix.
    # ------------------------------------------------------------------

    def test_injection_refs_are_rejected(self):
        """Refs with shell metacharacters are rejected before any sink (exit != 0)."""
        malicious = [
            "ghcr.io/autarchy-ai/g;reboot:1.0.0",
            "ghcr.io/$(reboot)/g:1.0.0",
            "ghcr.io/`reboot`/g:1.0.0",
            "ghcr.io/a'b/g:1.0.0",
            'ghcr.io/a"b/g:1.0.0',
            "ghcr.io/a b/g:1.0.0",
            "ghcr.io/a|b/g:1.0.0",
            "ghcr.io/a&b/g:1.0.0",
        ]
        for ref in malicious:
            with self.subTest(ref=ref):
                result = _run(["--dry-run", ref], self.gcdir)
                self.assertNotEqual(
                    result.returncode,
                    0,
                    msg=f"injection ref was NOT rejected: {ref!r}\nstdout={result.stdout!r}",
                )

    # ------------------------------------------------------------------
    # ADR-063 immutability (codex core review #1223, cycle 2): a rollback
    # must require a three-component X.Y.Z tag, never a mutable major.minor
    # alias that can retarget on the next patch release.
    # ------------------------------------------------------------------

    def test_two_component_tag_is_rejected(self):
        """Bare 1.0 and a full ref :1.0 are mutable aliases → rejected."""
        for ref in ("1.0", "ghcr.io/autarchy-ai/ground-control:1.0"):
            with self.subTest(ref=ref):
                result = _run(["--dry-run", ref], self.gcdir)
                self.assertNotEqual(
                    result.returncode, 0, msg=f"two-component tag accepted: {ref!r}"
                )

    # ------------------------------------------------------------------
    # Supply-chain provenance (codex security review #1223, cycle 2): a
    # rollback may only re-pin a version/digest of the SAME canonical image,
    # never repoint production at a different registry/repository.
    # ------------------------------------------------------------------

    def test_foreign_repository_refs_are_rejected(self):
        """Full/digest refs whose repo differs from the current pin are refused."""
        foreign = [
            "ghcr.io/attacker/ground-control:1.0.0",
            "evil.example.com/autarchy-ai/ground-control:1.0.0",
            f"ghcr.io/attacker/ground-control@sha256:{SHA256_DIGEST}",
        ]
        for ref in foreign:
            with self.subTest(ref=ref):
                result = _run(["--dry-run", ref], self.gcdir)
                self.assertNotEqual(
                    result.returncode,
                    0,
                    msg=f"foreign-repository target accepted: {ref!r}\nstdout={result.stdout!r}",
                )

    def test_same_repository_full_ref_is_accepted(self):
        """A full ref matching the current repo with an X.Y.Z tag is accepted."""
        result = _run(
            ["--dry-run", "ghcr.io/autarchy-ai/ground-control:2.0.0"], self.gcdir
        )
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn(
            "GC_IMAGE=ghcr.io/autarchy-ai/ground-control:2.0.0", result.stdout
        )

    def test_makefile_rollback_recipe_does_not_interpolate_version(self):
        """`make rollback` must pass VERSION as a quoted env value, never as
        interpolated shell text — so `make rollback VERSION='x; cmd'` cannot
        execute `cmd` at the make/recipe layer (codex security review #1223)."""
        result = subprocess.run(
            [
                "make",
                "-n",
                "-f",
                str(MAKEFILE),
                "rollback",
                "VERSION=1.0.0; echo INJECTED_TOKEN",
            ],
            capture_output=True,
            text=True,
            cwd=str(REPO_ROOT),
        )
        # The dry-run recipe text must reference the env var, not the expanded
        # value, and must NOT contain the injected literal.
        self.assertIn("$VERSION", result.stdout, msg=result.stdout)
        self.assertNotIn("INJECTED_TOKEN", result.stdout, msg=result.stdout)


if __name__ == "__main__":
    unittest.main()
