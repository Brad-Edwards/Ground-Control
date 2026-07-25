import json
import subprocess
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
HOOK = REPO_ROOT / ".claude" / "hooks" / "block-implement-worktree.py"


def run_hook(command: str) -> int:
    result = subprocess.run(
        ["python3", str(HOOK)],
        input=json.dumps({"tool_input": {"command": command}}),
        capture_output=True,
        text=True,
        timeout=10,
    )
    return result.returncode


class BlockImplementWorktreeTest(unittest.TestCase):
    def test_blocks_direct_worktree_creation_shapes(self):
        commands = [
            "git worktree add /tmp/x branch",
            "git -C /repo worktree add /tmp/x branch",
            "/usr/bin/git worktree add --detach /tmp/x",
            "env FOO=bar git -C '/repo here' worktree add /tmp/x branch",
            "echo ok && git worktree add /tmp/x branch",
        ]
        for command in commands:
            with self.subTest(command=command):
                self.assertEqual(run_hook(command), 2)

    def test_allows_reads_removal_and_unrelated_commands(self):
        for command in [
            "git worktree list --porcelain",
            "git worktree remove /tmp/x",
            "gh issue develop 1416 --checkout",
            "echo 'git worktree add is forbidden'",
        ]:
            with self.subTest(command=command):
                self.assertEqual(run_hook(command), 0)


if __name__ == "__main__":
    unittest.main()
