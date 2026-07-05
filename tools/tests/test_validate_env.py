"""Tests for deploy/docker/validate-env.sh (GC-P023).

The validator is the deploy-time half of the single env-schema contract: it
checks /opt/gc/.env against deploy/docker/env.schema before a rollout. These
tests drive the committed script as a subprocess against fixture env files and
assert (a) it accepts a complete env, (b) it fails loudly on each defect class,
and (c) it never echoes a secret VALUE — only variable names (GC-TM-003).
"""

import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPO_ROOT / "deploy/docker/validate-env.sh"
SCHEMA = REPO_ROOT / "deploy/docker/env.schema"

# A complete, valid /opt/gc/.env: every REQUIRED var present, GC_IMAGE pinned to
# an immutable versioned release tag (ADR-063), and one fully populated ADR-026
# credential slot so security-enabled passes.
VALID_ENV = {
    "GC_DATABASE_URL": "jdbc:postgresql://db:5432/ground_control",
    "GC_DATABASE_USER": "gc",
    "GC_DATABASE_PASSWORD": "p@ss",
    "JAVA_TOOL_OPTIONS": "-Xmx512m",
    "POSTGRES_DB": "ground_control",
    "POSTGRES_USER": "gc",
    "POSTGRES_PASSWORD": "p@ss",
    "TEMPORAL_POSTGRES_DB": "temporal",
    "TEMPORAL_POSTGRES_USER": "temporal",
    "TEMPORAL_POSTGRES_PASSWORD": "p@ss",  # ggignore
    "TEMPORAL_VISIBILITY_DB": "temporal_visibility",
    "GC_BIND_IP": "100.98.28.66",
    "GC_IMAGE": "ghcr.io/autarchy-ai/ground-control:1.0.1",
    "GC_SECURITY_ENABLED": "true",
    "GROUNDCONTROL_SECURITY_CREDENTIALS_0_PRINCIPAL_NAME": "mcp",
    "GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN": "tok",
    "GROUNDCONTROL_SECURITY_CREDENTIALS_0_ROLE": "USER",
}


def _write_env(directory: Path, values: dict) -> Path:
    env_path = directory / "fixture.env"
    env_path.write_text(
        "".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8"
    )
    return env_path


def _run(env_path: Path):
    return subprocess.run(
        ["bash", str(VALIDATOR), str(env_path), str(SCHEMA)],
        capture_output=True,
        text=True,
    )


class ValidateEnvTest(unittest.TestCase):
    def test_accepts_complete_valid_env(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = _run(_write_env(Path(tmp), dict(VALID_ENV)))
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            self.assertIn("env validation passed", result.stdout)

    def test_fails_on_missing_required_var(self):
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            del values["GC_DATABASE_PASSWORD"]
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 1)
            self.assertIn("GC_DATABASE_PASSWORD", result.stderr)

    def test_fails_on_digest_pinned_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            values["GC_IMAGE"] = "ghcr.io/autarchy-ai/ground-control@sha256:" + "a" * 64
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 1)
            self.assertIn("digest-pinned", result.stderr)

    def test_allows_digest_pin_with_explicit_override(self):
        # A deliberate digest pin for a controlled cutover/rollback passes when
        # GC_ALLOW_IMAGE_PIN=1 is set (loud, never silent).
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            values["GC_IMAGE"] = "ghcr.io/autarchy-ai/ground-control@sha256:" + "a" * 64
            values["GC_ALLOW_IMAGE_PIN"] = "1"
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            self.assertIn("GC_ALLOW_IMAGE_PIN=1", result.stderr)

    def test_fails_on_floating_branch_tag(self):
        # ADR-063: production must run an immutable versioned release, not the
        # floating :main branch tag the old model required.
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            values["GC_IMAGE"] = "ghcr.io/autarchy-ai/ground-control:main"
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 1)
            self.assertIn("floating", result.stderr)

    def test_fails_on_untagged_image(self):
        # No tag resolves to the mutable :latest; that is not a release pin.
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            values["GC_IMAGE"] = "ghcr.io/autarchy-ai/ground-control"
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 1)
            self.assertIn("no image tag", result.stderr)

    def test_accepts_major_minor_release_tag(self):
        # docker/metadata-action also emits the X.Y coordinate; it is a valid
        # immutable release pin.
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            values["GC_IMAGE"] = "ghcr.io/autarchy-ai/ground-control:1.4"
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            self.assertIn("env validation passed", result.stdout)

    def test_accepts_registry_port_in_versioned_ref(self):
        # A registry host:port must not be mistaken for the image tag; the tag is
        # the segment after the last '/' then last ':'.
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            values["GC_IMAGE"] = "localhost:5959/ground-control:2.3.4"
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 0, msg=result.stderr)
            self.assertIn("env validation passed", result.stdout)

    def test_fails_on_partial_credential_slot(self):
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            del values["GROUNDCONTROL_SECURITY_CREDENTIALS_0_ROLE"]
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 1)
            self.assertIn("partially populated", result.stderr)

    def test_fails_when_security_enabled_without_any_credential_slot(self):
        with tempfile.TemporaryDirectory() as tmp:
            values = {
                k: v
                for k, v in VALID_ENV.items()
                if not k.startswith("GROUNDCONTROL_SECURITY_CREDENTIALS_")
            }
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 1)
            self.assertIn("401", result.stderr)

    def test_never_prints_secret_values(self):
        # The validator must report variable NAMES only. Plant a recognizable
        # secret in a failing env (blank a required var to force the failure
        # path) and assert the secret value never reaches stdout or stderr.
        secret = "S3CR3T-DO-NOT-LEAK-7f3a"
        with tempfile.TemporaryDirectory() as tmp:
            values = dict(VALID_ENV)
            values["GROUNDCONTROL_SECURITY_CREDENTIALS_0_TOKEN"] = secret
            values["GC_DATABASE_PASSWORD"] = ""  # force a validation failure
            result = _run(_write_env(Path(tmp), values))
            self.assertEqual(result.returncode, 1)
            self.assertNotIn(secret, result.stdout)
            self.assertNotIn(secret, result.stderr)


if __name__ == "__main__":
    unittest.main()
