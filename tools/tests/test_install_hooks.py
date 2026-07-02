"""Tests for scripts/install-hooks.sh — commit-time pre-commit hook activation.

These exercise the installer against real (throwaway) git repositories with a
fully isolated HOME and git config, so the host's global hooks are never
touched. They cover the four activation strategies plus the symlink-safety
invariant (ADR-079): never write through a symlink, never clobber the
host-owned global hooks dir, fail closed on an unsupported dispatcher.
"""

import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
INSTALL_HOOKS = REPO_ROOT / "scripts" / "install-hooks.sh"

MARKER = "ground-control-managed-hook"


class InstallHooksTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="gc-install-hooks-"))
        # Fully isolated HOME + git config: the real ~/.git-hooks is unreachable.
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        # Stub `pre-commit` so `pre-commit run --all-files` and any hook-impl
        # dispatch succeed without a real install or network.
        stub = self.bin / "pre-commit"
        stub.write_text("#!/usr/bin/env bash\nexit 0\n")
        stub.chmod(0o755)
        self.global_cfg = self.home / ".gitconfig"
        self.global_cfg.write_text("")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    # -- helpers ---------------------------------------------------------

    def _env(self):
        env = dict(os.environ)
        env.update(
            HOME=str(self.home),
            GIT_CONFIG_GLOBAL=str(self.global_cfg),
            GIT_CONFIG_SYSTEM="/dev/null",
            GIT_AUTHOR_NAME="t",
            GIT_AUTHOR_EMAIL="t@t",
            GIT_COMMITTER_NAME="t",
            GIT_COMMITTER_EMAIL="t@t",
            PATH=f"{self.bin}:{env.get('PATH', '')}",
        )
        return env

    def _git(self, repo, *args):
        subprocess.run(["git", "-C", str(repo), *args], env=self._env(),
                       check=True, capture_output=True, text=True)

    def _set_global_hookspath(self, path):
        # Write directly to the isolated global config; never the real one.
        subprocess.run(
            ["git", "config", "--global", "core.hooksPath", str(path)],
            env=self._env(), check=True, capture_output=True, text=True)

    def _make_repo(self, name):
        repo = self.tmp / name
        repo.mkdir()
        self._git(repo, "init", "-q")
        (repo / ".pre-commit-config.yaml").write_text("fail_fast: true\nrepos: []\n")
        scripts = repo / "scripts"
        scripts.mkdir()
        dest = scripts / "install-hooks.sh"
        shutil.copy2(INSTALL_HOOKS, dest)
        dest.chmod(0o755)
        return repo

    def _run(self, repo, *flags):
        return subprocess.run(
            ["./scripts/install-hooks.sh", *flags],
            cwd=str(repo), env=self._env(), capture_output=True, text=True)

    def _make_chain_dispatcher(self, name, delegating=True):
        """A global core.hooksPath dir. delegating=True mimics the host's
        `_chain` (delegates to the clone-local hook); False ignores it."""
        d = self.tmp / name
        d.mkdir()
        if delegating:
            chain = d / "_chain"
            chain.write_text(textwrap.dedent("""\
                #!/usr/bin/env bash
                set -e
                hook="$(basename "$0")"
                gd="$(git rev-parse --git-dir 2>/dev/null || true)"
                [ -n "$gd" ] && [ -x "${gd}/hooks/${hook}" ] && exec "${gd}/hooks/${hook}" "$@"
                exit 0
                """))
            chain.chmod(0o755)
            for h in ("pre-commit", "pre-push"):
                (d / h).symlink_to("_chain")
        else:
            for h in ("pre-commit", "pre-push"):
                p = d / h
                p.write_text("#!/usr/bin/env bash\nexit 0\n")
                p.chmod(0o755)
        return d

    def _hook(self, repo, name):
        return repo / ".git" / "hooks" / name

    # -- tests -----------------------------------------------------------

    def test_normal_no_hookspath_writes_managed_hooks(self):
        repo = self._make_repo("r_normal")
        r = self._run(repo)
        self.assertEqual(r.returncode, 0, r.stderr)
        for name in ("pre-commit", "pre-push"):
            self.assertIn(MARKER, self._hook(repo, name).read_text())

    def test_supported_dispatcher_writes_local_and_leaves_global_untouched(self):
        chain = self._make_chain_dispatcher("chain", delegating=True)
        chain_before = (chain / "_chain").read_text()
        self._set_global_hookspath(chain)
        repo = self._make_repo("r_dispatch")
        r = self._run(repo)
        self.assertEqual(r.returncode, 0, r.stderr)
        # Both managed hooks land in the clone-local hook dir, not the dispatcher dir.
        self.assertIn(MARKER, self._hook(repo, "pre-commit").read_text())
        self.assertIn(MARKER, self._hook(repo, "pre-push").read_text())
        # The host-owned global dispatcher is untouched — this is the whole point.
        # Assert it for BOTH managed slots so a pre-push write-through would fail here.
        self.assertEqual((chain / "_chain").read_text(), chain_before)
        for slot in ("pre-commit", "pre-push"):
            self.assertTrue((chain / slot).is_symlink())
            self.assertNotIn(MARKER, (chain / slot).read_text())

    def test_unsupported_dispatcher_fails_closed(self):
        bad = self._make_chain_dispatcher("bad", delegating=False)
        bad_before = {slot: (bad / slot).read_text() for slot in ("pre-commit", "pre-push")}
        self._set_global_hookspath(bad)
        repo = self._make_repo("r_bad")
        r = self._run(repo)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("NOT reached through git's effective dispatch", r.stderr)
        # Global config left untouched; neither slot of the dispatcher dir is modified.
        for slot in ("pre-commit", "pre-push"):
            self.assertEqual((bad / slot).read_text(), bad_before[slot])

    def test_unmanaged_existing_hook_is_preserved_without_force(self):
        repo = self._make_repo("r_unmanaged")
        target = self._hook(repo, "pre-commit")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("#!/usr/bin/env bash\necho custom\n")
        target.chmod(0o755)
        r = self._run(repo)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("echo custom", target.read_text())
        # --force replaces it with a managed hook — both slots end up managed.
        r2 = self._run(repo, "--force")
        self.assertEqual(r2.returncode, 0, r2.stderr)
        self.assertIn(MARKER, target.read_text())
        self.assertIn(MARKER, self._hook(repo, "pre-push").read_text())

    def test_symlinked_hook_is_never_written_through(self):
        """Regression: a shell redirect to a symlinked hook must NOT overwrite the
        symlink's target (that once clobbered a shared dispatcher)."""
        repo = self._make_repo("r_symlink")
        link_target = repo / "linktarget"
        link_target.write_text("SENTINEL-ORIGINAL\n")
        link_target.chmod(0o755)
        target = self._hook(repo, "pre-commit")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.symlink_to(link_target)
        r = self._run(repo, "--force")
        self.assertEqual(r.returncode, 0, r.stderr)
        # The link's target is untouched; the hook is now a managed regular file.
        self.assertEqual(link_target.read_text(), "SENTINEL-ORIGINAL\n")
        self.assertFalse(target.is_symlink())
        self.assertIn(MARKER, target.read_text())
        # The parallel pre-push slot is written as a managed regular file too.
        self.assertFalse(self._hook(repo, "pre-push").is_symlink())
        self.assertIn(MARKER, self._hook(repo, "pre-push").read_text())

    def test_symlinked_hook_fails_closed_without_force(self):
        repo = self._make_repo("r_symlink_noforce")
        link_target = repo / "linktarget"
        link_target.write_text("SENTINEL\n")
        target = self._hook(repo, "pre-commit")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.symlink_to(link_target)
        r = self._run(repo)
        self.assertNotEqual(r.returncode, 0)
        self.assertTrue(target.is_symlink())

    def test_dry_run_writes_nothing(self):
        repo = self._make_repo("r_dry")
        r = self._run(repo, "--dry-run")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertFalse(self._hook(repo, "pre-commit").exists())
        self.assertFalse(self._hook(repo, "pre-push").exists())


if __name__ == "__main__":
    unittest.main()
