#!/usr/bin/env bash
#
# test-cursor-skill-symlink.sh
#
# Verifies Cursor CLI skill-discovery constraints for symlink vs copy installs.
# Cursor walks each skills root (for example ~/.cursor/skills or
# <repo>/.cursor/skills), follows directory symlinks, then rejects any SKILL.md
# whose realpath falls outside that root. Symlinks to another checkout path
# therefore fail discovery; hard copies pass.
#
# Usage: scripts/test-cursor-skill-symlink.sh

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
skills_root="${repo_root}/skills/implement"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/gc-cursor-skill-test.XXXXXX")"

cleanup() {
  rm -rf "${tmp_root}"
}
trap cleanup EXIT

node <<NODE
const fs = require('fs/promises');
const path = require('path');

async function safeRealpath(p) {
  try {
    return await fs.realpath(p);
  } catch {
    return null;
  }
}

function isPathWithin(root, target) {
  const rel = path.relative(root, target);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

async function cursorWouldDiscover(skillsRoot, skillDirName) {
  const root = await safeRealpath(skillsRoot);
  const skillDir = path.join(skillsRoot, skillDirName);
  const skillMd = path.join(skillDir, 'SKILL.md');
  const resolvedSkillMd = await safeRealpath(skillMd);
  if (!root || !resolvedSkillMd) {
    return false;
  }
  return isPathWithin(root, resolvedSkillMd);
}

(async () => {
  const skillsRoot = ${tmp_root@Q};
  const canonical = ${skills_root@Q};

  await fs.mkdir(path.join(skillsRoot, 'copy'), { recursive: true });
  await fs.cp(canonical, path.join(skillsRoot, 'copy', 'implement'), { recursive: true });

  await fs.mkdir(path.join(skillsRoot, 'symlink'), { recursive: true });
  await fs.symlink(canonical, path.join(skillsRoot, 'symlink', 'implement'));

  const copyOk = await cursorWouldDiscover(path.join(skillsRoot, 'copy'), 'implement');
  const symlinkOk = await cursorWouldDiscover(path.join(skillsRoot, 'symlink'), 'implement');

  if (!copyOk) {
    console.error('FAIL: expected hard copy to pass Cursor root check');
    process.exit(1);
  }
  if (symlinkOk) {
    console.error('FAIL: expected symlink to fail Cursor root check');
    process.exit(1);
  }

  console.log('PASS: Cursor skill discovery accepts hard copies and rejects out-of-root symlinks');
})();
NODE
