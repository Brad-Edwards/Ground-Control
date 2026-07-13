"""Tests for scripts/pack-sync.sh — checkout-derived workflow dispatch (GC-P026).

pack-sync.sh derives the default GitHub repository from the checkout's origin
remote rather than a hardcoded owner, and treats `--repo` as a validated
override (owner/name shape only). These tests drive the script against a real
(throwaway) git repo with a fake `gh` on PATH so no network, auth, or real
dispatch occurs:

  - the happy path dispatches `gh workflow run` with `--repo <derived-slug>`
  - a malformed `--repo` value is rejected by the shape guard before any gh call
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PACK_SYNC = REPO_ROOT / "scripts" / "pack-sync.sh"


class PackSyncTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="gc-pack-sync-"))
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        # Where the fake `gh` records a `gh workflow run` invocation's argv.
        self.gh_argv_log = self.tmp / "gh-argv.txt"

        # A fake `gh`: succeed for auth/api probes, record `workflow run` argv,
        # and succeed for everything else. One arg per line so the test can
        # assert token adjacency deterministically.
        gh = self.bin / "gh"
        gh.write_text(
            "#!/usr/bin/env bash\n"
            'if [ "$1" = "workflow" ] && [ "$2" = "run" ]; then\n'
            '  printf \'%s\\n\' "$@" > "$GH_ARGV_LOG"\n'
            "  exit 0\n"
            "fi\n"
            "exit 0\n"
        )
        gh.chmod(0o755)

        # A born branch so `git rev-parse --abbrev-ref HEAD` resolves cleanly
        # under the script's `set -euo pipefail`.
        self._git("init", "-q")
        self._git("remote", "add", "origin", "https://github.com/acme/widgets.git")
        self._git("commit", "--allow-empty", "-q", "-m", "init")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _env(self):
        env = dict(os.environ)
        env.update(
            PATH=f"{self.bin}:{env.get('PATH', '')}",
            GH_ARGV_LOG=str(self.gh_argv_log),
            GIT_AUTHOR_NAME="t",
            GIT_AUTHOR_EMAIL="t@t",
            GIT_COMMITTER_NAME="t",
            GIT_COMMITTER_EMAIL="t@t",
        )
        return env

    def _git(self, *args):
        subprocess.run(
            ["git", "-C", str(self.repo), *args],
            env=self._env(), check=True, capture_output=True, text=True,
        )

    def _run(self, *flags):
        return subprocess.run(
            ["bash", str(PACK_SYNC), *flags],
            cwd=str(self.repo), env=self._env(), capture_output=True, text=True,
        )

    def test_dispatches_with_checkout_derived_repo(self):
        self.assertTrue(PACK_SYNC.exists(), f"script not found at {PACK_SYNC}")
        r = self._run()
        self.assertEqual(r.returncode, 0, f"stdout={r.stdout}\nstderr={r.stderr}")
        self.assertTrue(self.gh_argv_log.exists(), "gh workflow run was never invoked")
        argv = self.gh_argv_log.read_text().splitlines()
        self.assertIn("--repo", argv)
        self.assertEqual(
            argv[argv.index("--repo") + 1],
            "acme/widgets",
            f"--repo must be the checkout-derived slug; argv={argv}",
        )

    def test_dispatches_with_ssh_url_origin(self):
        # The shared resolver (scripts/lib/gh-repo-slug.sh) must accept the
        # ssh:// remote form, not only scp-style SSH and HTTPS (codex, #1383):
        # otherwise a valid checkout dispatches differently per entry point.
        self._git("remote", "set-url", "origin", "ssh://git@github.com/acme/widgets.git")
        r = self._run()
        self.assertEqual(r.returncode, 0, f"stdout={r.stdout}\nstderr={r.stderr}")
        self.assertTrue(self.gh_argv_log.exists(), "gh workflow run was never invoked")
        argv = self.gh_argv_log.read_text().splitlines()
        self.assertEqual(
            argv[argv.index("--repo") + 1],
            "acme/widgets",
            f"ssh:// origin must resolve to the same slug; argv={argv}",
        )

    def test_rejects_malformed_repo_override(self):
        r = self._run("--repo", "not-a-slug")
        self.assertNotEqual(r.returncode, 0, "malformed --repo must be rejected")
        self.assertFalse(
            self.gh_argv_log.exists(),
            "gh workflow run must not run when --repo is malformed",
        )

    # ------------------------------------------------------------------
    # Direct coverage of the shared resolver scripts/lib/gh-repo-slug.sh,
    # sourced by pack-sync.sh, deploy.sh, and check-pr-body.sh. The resolver's
    # docstring commits to a specific set of github.com remote-URL forms; each
    # documented form is exercised so a future edit that breaks a case branch
    # (e.g. the scp-style default for SSH-configured checkouts) fails loudly
    # rather than silently reintroducing the identity-resolution drift this PR
    # closes (GC-P026 / #1383).
    # ------------------------------------------------------------------
    GH_REPO_SLUG_LIB = REPO_ROOT / "scripts" / "lib" / "gh-repo-slug.sh"

    def _resolve_slug(self, origin_url):
        self._git("remote", "set-url", "origin", origin_url)
        r = subprocess.run(
            [
                "bash",
                "-c",
                f'source "{self.GH_REPO_SLUG_LIB}"; resolve_repo_slug "{self.repo}"',
            ],
            env=self._env(), capture_output=True, text=True,
        )
        self.assertEqual(r.returncode, 0, f"stderr={r.stderr}")
        return r.stdout.strip()

    def test_resolve_repo_slug_supports_all_documented_github_forms(self):
        cases = {
            "git@github.com:acme/widgets.git": "acme/widgets",
            "git@github.com:acme/widgets": "acme/widgets",
            "ssh://git@github.com/acme/widgets.git": "acme/widgets",
            "ssh://github.com/acme/widgets.git": "acme/widgets",
            "https://github.com/acme/widgets.git": "acme/widgets",
            "https://github.com/acme/widgets": "acme/widgets",
            "https://x-access-token:ghs_tok@github.com/acme/widgets.git": "acme/widgets",
            "http://github.com/acme/widgets.git": "acme/widgets",
        }
        for url, expected in cases.items():
            with self.subTest(url=url):
                self.assertEqual(self._resolve_slug(url), expected)

    def test_resolve_repo_slug_empty_for_non_github_origin(self):
        for url in (
            "https://gitlab.com/acme/widgets.git",
            "https://example.com/acme/widgets",
        ):
            with self.subTest(url=url):
                self.assertEqual(self._resolve_slug(url), "")


if __name__ == "__main__":
    unittest.main()
