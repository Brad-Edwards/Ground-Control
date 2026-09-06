// Extracted from grc-legacy-compat-4.js to keep it under the 500-line limit (docs/CODING_STANDARDS.md, Sonar S104).
// Review-diff + single-codex-review helpers; re-exported through grc-legacy-compat-4.js so the import surface is unchanged.

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve as resolvePath } from "node:path";
import { codexEngineEnv } from "./codex-engine-env.js";
import { buildCodexReviewExecArgs } from "./grc-legacy-compat.js";
import { execFile, execFileWithInput, getDefaultCodexTimeoutMs } from "./runtime-primitives.js";
import { readGeneratedCodexSummary } from "./codex-workflow.js";

// Issue #1557: a numstat row cannot state a deletion — `0\t297\tfoo.mjs` is
// byte-identical to an emptied-but-retained file, and #650 shows a reviewer
// reading it the wrong way round. `--name-status` states the kind. It is an
// ADDITIVE block: the numstat rows above it stay byte-compatible because
// parseNumstatManifest and the review-cap disposition scorer consume them, and
// both manifest parsers skip these rows rather than treating them as a second
// schema (a status column is never an integer).
const CHANGE_KIND_HEADER =
  "# change kinds — `git diff --name-status` (A added, C copied, D deleted, M modified, R renamed, T type changed)";

async function collectUnreviewedUntrackedPaths(repoRoot) {
  const { stdout } = await execFile(
    "git",
    ["-C", repoRoot, "ls-files", "--others", "--exclude-standard", "-z"],
    { maxBuffer: 10 * 1024 * 1024 },
  );
  return stdout.split("\0").filter((p) => p !== "");
}
// Issue #1557 security cycle 1: the reviewer may now read the working tree to
// verify a repository fact, and a tracked symlink's target is attacker-supplied
// content that need not stay inside the checkout — `--sandbox read-only` blocks
// writes but does not confine reads. Git stores a symlink as a blob holding its
// target path, so the recorded target IS the link's entire content: handing it
// to the reviewer removes any reason to dereference one. The list is normally
// empty and is bounded so it can never dominate the prompt.
const TRACKED_SYMLINK_LIMIT = 50;
export async function collectTrackedSymlinks(repoRoot) {
  const { stdout } = await execFile(
    "git",
    ["-C", repoRoot, "ls-files", "-s", "-z"],
    { maxBuffer: 10 * 1024 * 1024 },
  );
  const symlinks = [];
  for (const record of stdout.split("\0")) {
    if (record === "" || !record.startsWith("120000 ")) continue;
    const tab = record.indexOf("\t");
    if (tab === -1) continue;
    const path = record.slice(tab + 1);
    const sha = record.slice(0, tab).split(" ")[1];
    if (!/^[0-9a-f]{40,64}$/.test(sha)) continue;
    const blob = await execFile("git", ["-C", repoRoot, "cat-file", "blob", sha], {
      maxBuffer: 1024 * 1024,
    });
    const target = blob.stdout.trim();
    const resolved = isAbsolute(target)
      ? target
      : resolvePath(repoRoot, dirname(path), target);
    const rel = relative(repoRoot, resolved);
    symlinks.push({
      path,
      target,
      escapes_repo: rel === "" || rel.startsWith("..") || isAbsolute(rel),
    });
    if (symlinks.length >= TRACKED_SYMLINK_LIMIT) break;
  }
  return symlinks;
}
export async function computeReviewDiff(repoRoot, baseBranch, uncommitted) {
  if (uncommitted) {
    const staged = await execFile("git", ["-C", repoRoot, "diff", "--staged"], { maxBuffer: 50 * 1024 * 1024 });
    const unstaged = await execFile("git", ["-C", repoRoot, "diff"], { maxBuffer: 50 * 1024 * 1024 });
    const stagedManifest = await execFile(
      "git",
      ["-C", repoRoot, "diff", "--staged", "--numstat"],
      { maxBuffer: 10 * 1024 * 1024 },
    );
    const unstagedManifest = await execFile(
      "git",
      ["-C", repoRoot, "diff", "--numstat"],
      { maxBuffer: 10 * 1024 * 1024 },
    );
    const stagedKinds = await execFile(
      "git",
      ["-C", repoRoot, "diff", "--staged", "--name-status"],
      { maxBuffer: 10 * 1024 * 1024 },
    );
    const unstagedKinds = await execFile(
      "git",
      ["-C", repoRoot, "diff", "--name-status"],
      { maxBuffer: 10 * 1024 * 1024 },
    );
    const unreviewedUntrackedPaths = await collectUnreviewedUntrackedPaths(repoRoot);
    const trackedSymlinks = await collectTrackedSymlinks(repoRoot);
    return {
      diffText: `${staged.stdout}\n${unstaged.stdout}`.trim(),
      manifest: [
        "# staged",
        stagedManifest.stdout.trim() || "(none)",
        "",
        "# unstaged",
        unstagedManifest.stdout.trim() || "(none)",
        "",
        CHANGE_KIND_HEADER,
        "# staged",
        stagedKinds.stdout.trim() || "(none)",
        "",
        "# unstaged",
        unstagedKinds.stdout.trim() || "(none)",
        // Count only: the manifest goes into the reviewer prompt, and a path
        // can itself be revealing. The caller gets the full list off-prompt.
        ...(unreviewedUntrackedPaths.length > 0
          ? [
              "",
              `# untracked: ${unreviewedUntrackedPaths.length} path(s) present but NOT staged and NOT included in this review`,
            ]
          : []),
      ].join("\n"),
      baseRefDescriptor: null,
      unreviewedUntrackedPaths,
      trackedSymlinks,
    };
  }
  const candidates = [`origin/${baseBranch}`, baseBranch, "origin/main", "main"];
  for (const ref of candidates) {
    try {
      await execFile("git", ["-C", repoRoot, "rev-parse", "--verify", ref]);
      const { stdout } = await execFile(
        "git",
        ["-C", repoRoot, "diff", `${ref}...HEAD`],
        { maxBuffer: 50 * 1024 * 1024 },
      );
      const manifest = await execFile(
        "git",
        ["-C", repoRoot, "diff", `${ref}...HEAD`, "--numstat"],
        { maxBuffer: 10 * 1024 * 1024 },
      );
      const kinds = await execFile(
        "git",
        ["-C", repoRoot, "diff", `${ref}...HEAD`, "--name-status"],
        { maxBuffer: 10 * 1024 * 1024 },
      );
      return {
        diffText: stdout,
        manifest: [
          manifest.stdout.trim() || "(no files changed)",
          "",
          CHANGE_KIND_HEADER,
          kinds.stdout.trim() || "(no files changed)",
        ].join("\n"),
        baseRefDescriptor: ref,
        unreviewedUntrackedPaths: [],
        trackedSymlinks: await collectTrackedSymlinks(repoRoot),
      };
    } catch {
      continue;
    }
  }
  throw new Error(`Unable to compute review diff: none of ${candidates.join(", ")} exist in ${repoRoot}`);
}
export async function runSingleCodexReview({ repoRoot, prompt, signal = undefined }) {
  const tempDir = mkdtempSync(join(tmpdir(), "gc-codex-review-"));
  const outputPath = join(tempDir, "codex-last-message.txt");
  try {
    await execFileWithInput(
      "codex",
      buildCodexReviewExecArgs({ repoPath: repoRoot, outputPath }),
      {
        input: prompt,
        cwd: repoRoot,
        maxBuffer: 10 * 1024 * 1024,
        env: codexEngineEnv(),
        timeoutMs: getDefaultCodexTimeoutMs(),
        signal,
      },
    );
    return readGeneratedCodexSummary(outputPath);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}
