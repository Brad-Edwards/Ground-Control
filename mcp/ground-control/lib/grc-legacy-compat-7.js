// Extracted from grc-legacy-compat-4.js to keep it under the 500-line limit (docs/CODING_STANDARDS.md, Sonar S104).
// Review-diff + single-codex-review helpers; re-exported through grc-legacy-compat-4.js so the import surface is unchanged.

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { codexEngineEnv } from "./codex-engine-env.js";
import { buildCodexReviewExecArgs } from "./grc-legacy-compat.js";
import { execFile, execFileWithInput, getDefaultCodexTimeoutMs } from "./runtime-primitives.js";
import { readGeneratedCodexSummary } from "./codex-workflow.js";

async function collectUnreviewedUntrackedPaths(repoRoot) {
  const { stdout } = await execFile(
    "git",
    ["-C", repoRoot, "ls-files", "--others", "--exclude-standard", "-z"],
    { maxBuffer: 10 * 1024 * 1024 },
  );
  return stdout.split("\0").filter((p) => p !== "");
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
    const unreviewedUntrackedPaths = await collectUnreviewedUntrackedPaths(repoRoot);
    return {
      diffText: `${staged.stdout}\n${unstaged.stdout}`.trim(),
      manifest: [
        "# staged",
        stagedManifest.stdout.trim() || "(none)",
        "",
        "# unstaged",
        unstagedManifest.stdout.trim() || "(none)",
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
      return {
        diffText: stdout,
        manifest: manifest.stdout.trim() || "(no files changed)",
        baseRefDescriptor: ref,
        unreviewedUntrackedPaths: [],
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
