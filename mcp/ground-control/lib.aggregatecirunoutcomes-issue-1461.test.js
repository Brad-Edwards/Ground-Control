// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";

describe("aggregateCiRunOutcomes (issue #1461)", () => {
  it("is successful only when every run for the SHA succeeded", async () => {
    const { aggregateCiRunOutcomes } = await import("./lib.js");

    const r = aggregateCiRunOutcomes([
      { databaseId: 1, status: "completed", conclusion: "success" },
      { databaseId: 2, status: "completed", conclusion: "success" },
    ]);

    assert.equal(r.conclusion, "success");
    assert.equal(r.failing, null);
  });

  it("reports the failing run when any run for the SHA failed", async () => {
    const { aggregateCiRunOutcomes } = await import("./lib.js");

    const r = aggregateCiRunOutcomes([
      { databaseId: 1, status: "completed", conclusion: "success" },
      { databaseId: 2, status: "completed", conclusion: "failure" },
    ]);

    assert.equal(r.conclusion, "failure");
    assert.equal(r.failing.databaseId, 2);
  });

  it("prefers a real failure over a cancellation when both are present", async () => {
    const { aggregateCiRunOutcomes } = await import("./lib.js");

    const r = aggregateCiRunOutcomes([
      { databaseId: 1, status: "completed", conclusion: "cancelled" },
      { databaseId: 2, status: "completed", conclusion: "failure" },
    ]);

    assert.equal(r.conclusion, "failure");
    assert.equal(r.failing.databaseId, 2);
  });

  it("treats an empty set as unknown rather than successful", async () => {
    const { aggregateCiRunOutcomes } = await import("./lib.js");

    assert.notEqual(aggregateCiRunOutcomes([]).conclusion, "success");
  });
});

describe("runWatchCiRun input validation (issue #934)", () => {
  it("refuses when repo_path is missing", async () => {
    const { runWatchCiRun } = await import("./lib.js");
    const r = await runWatchCiRun({ repoPath: "", branch: "main" });
    assert.equal(r.ok, false);
    assert.equal(r.error, "ci_watch_input_invalid");
  });

  it("refuses when branch is missing or empty", async () => {
    const { runWatchCiRun } = await import("./lib.js");
    const r1 = await runWatchCiRun({ repoPath: "/tmp", branch: "" });
    assert.equal(r1.ok, false);
    assert.equal(r1.error, "ci_watch_input_invalid");
    const r2 = await runWatchCiRun({ repoPath: "/tmp", branch: null });
    assert.equal(r2.ok, false);
    assert.equal(r2.error, "ci_watch_input_invalid");
  });

  it("refuses when run_id is provided but not a positive integer", async () => {
    const { runWatchCiRun } = await import("./lib.js");
    for (const bad of [0, -1, 1.5, "1"]) {
      const r = await runWatchCiRun({
        authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }),
        repoPath: "/tmp",
        branch: "main",
        runId: bad,
      });
      assert.equal(r.ok, false, `bad=${bad}`);
      assert.equal(r.error, "ci_watch_input_invalid");
    }
  });

  it("refuses when timeout fields are not positive integers", async () => {
    const { runWatchCiRun } = await import("./lib.js");
    const r1 = await runWatchCiRun({
        authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }),
      repoPath: "/tmp",
      branch: "main",
      queuedTimeoutSeconds: 0,
    });
    assert.equal(r1.ok, false);
    assert.equal(r1.error, "ci_watch_input_invalid");
    const r2 = await runWatchCiRun({
        authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }),
      repoPath: "/tmp",
      branch: "main",
      totalTimeoutSeconds: -5,
    });
    assert.equal(r2.ok, false);
    assert.equal(r2.error, "ci_watch_input_invalid");
    const r3 = await runWatchCiRun({
        authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }),
      repoPath: "/tmp",
      branch: "main",
      pollIntervalSeconds: 0,
    });
    assert.equal(r3.ok, false);
    assert.equal(r3.error, "ci_watch_input_invalid");
  });

  it("refuses when repo_path is not a git repository", async () => {
    const { runWatchCiRun } = await import("./lib.js");
    const dir = mkdtempSync(join(tmpdir(), "gc-ci-watch-not-git-"));
    try {
      const r = await runWatchCiRun({ repoPath: dir, branch: "main", authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }) });
      assert.equal(r.ok, false);
      assert.equal(r.error, "ci_watch_repo_not_found");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("parseOwnerRepoFromRemoteUrl — git-based owner/repo resolution (issue #934 fix-list)", () => {
  // getOwnerRepo previously used `gh repo view` which honors GH_REPO and
  // can be hijacked. The replacement reads the git remote URL directly.
  // These tests pin the URL parser so the parser stays robust across
  // every URL shape `git remote get-url origin` emits.

  it("parses HTTPS URL with .git suffix", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    assert.deepEqual(
      parseOwnerRepoFromRemoteUrl("https://github.com/Brad-Edwards/Ground-Control.git\n"),
      { owner: "Brad-Edwards", name: "Ground-Control" },
    );
  });

  it("parses HTTPS URL without .git suffix", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    assert.deepEqual(
      parseOwnerRepoFromRemoteUrl("https://github.com/Brad-Edwards/Ground-Control"),
      { owner: "Brad-Edwards", name: "Ground-Control" },
    );
  });

  it("parses HTTPS URL with trailing slash", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    assert.deepEqual(
      parseOwnerRepoFromRemoteUrl("https://github.com/Brad-Edwards/Ground-Control/"),
      { owner: "Brad-Edwards", name: "Ground-Control" },
    );
  });

  it("parses HTTPS URL with embedded credentials", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    // git clone with token-embedded URLs is common in CI; the parser
    // must strip the credentials and still return owner/name.
    assert.deepEqual(
      parseOwnerRepoFromRemoteUrl("https://x-access-token:ghs_xxx@github.com/Brad-Edwards/Ground-Control.git"),
      { owner: "Brad-Edwards", name: "Ground-Control" },
    );
  });

  it("parses SSH URL with .git suffix", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    assert.deepEqual(
      parseOwnerRepoFromRemoteUrl("git@github.com:Brad-Edwards/Ground-Control.git\n"),
      { owner: "Brad-Edwards", name: "Ground-Control" },
    );
  });

  it("parses SSH URL without .git suffix", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    assert.deepEqual(
      parseOwnerRepoFromRemoteUrl("git@github.com:Brad-Edwards/Ground-Control"),
      { owner: "Brad-Edwards", name: "Ground-Control" },
    );
  });

  it("returns null for non-github URLs", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    assert.equal(parseOwnerRepoFromRemoteUrl("https://gitlab.com/foo/bar.git"), null);
    assert.equal(parseOwnerRepoFromRemoteUrl("https://example.com/owner/name"), null);
  });

  it("returns null for empty / non-string input", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    assert.equal(parseOwnerRepoFromRemoteUrl(""), null);
    assert.equal(parseOwnerRepoFromRemoteUrl(null), null);
    assert.equal(parseOwnerRepoFromRemoteUrl(undefined), null);
    assert.equal(parseOwnerRepoFromRemoteUrl(123), null);
  });

  it("handles whitespace and newlines from git output", async () => {
    const { parseOwnerRepoFromRemoteUrl } = await import("./lib.js");
    assert.deepEqual(
      parseOwnerRepoFromRemoteUrl("  https://github.com/o/n.git\n\n"),
      { owner: "o", name: "n" },
    );
  });
});

describe("buildCiWatchGhArgs — GH_REPO hijack defense (issue #934)", () => {
  // A regression target for the bug surfaced by the gc-orchestrator-test
  // end-to-end run: an MCP server launched with `GH_REPO=other-owner/other`
  // env var would hijack every `gh run view` / `gh run list` call inside
  // the CI watcher and return HTTP 404. The fix is to always pass
  // `--repo owner/name` explicitly so the env var is ignored.

  it("prepends --repo owner/name to the run-specific argv", async () => {
    const { buildCiWatchGhArgs } = await import("./lib.js");
    const args = buildCiWatchGhArgs("Brad-Edwards/gc-orchestrator-test", [
      "run",
      "list",
      "--branch",
      "x",
    ]);
    assert.equal(args[0], "--repo");
    assert.equal(args[1], "Brad-Edwards/gc-orchestrator-test");
    assert.deepEqual(args.slice(2), ["run", "list", "--branch", "x"]);
  });

  it("throws when repoSlug is missing the owner/name shape", async () => {
    const { buildCiWatchGhArgs } = await import("./lib.js");
    assert.throws(
      () => buildCiWatchGhArgs("not-a-slug", ["run", "view", "1"]),
      /owner\/name slug/,
    );
    assert.throws(
      () => buildCiWatchGhArgs("", ["run", "view", "1"]),
      /owner\/name slug/,
    );
    assert.throws(
      () => buildCiWatchGhArgs(null, ["run", "view", "1"]),
      /owner\/name slug/,
    );
  });

  it("never produces argv that allows GH_REPO env override", async () => {
    // The contract: --repo must appear before the gh subcommand so
    // gh's argv parser sees it ahead of the implicit env resolution.
    const { buildCiWatchGhArgs } = await import("./lib.js");
    const args = buildCiWatchGhArgs("o/r", [
      "run",
      "view",
      "12345",
      "--log-failed",
    ]);
    const repoFlagIndex = args.indexOf("--repo");
    const runSubcommandIndex = args.indexOf("run");
    assert.ok(repoFlagIndex >= 0, "--repo must be in the argv");
    assert.ok(
      repoFlagIndex < runSubcommandIndex,
      "--repo must precede the gh subcommand",
    );
  });
});

// =============================================================================
// gc_watch_sonar_analysis (issue #934)
// =============================================================================
//
// Server-side SonarCloud poller. Skips entirely when the repo has no
// sonarcloud block in .ground-control.yaml (mirrors Step 11). Pure
// helpers carry the summarization logic; HTTP calls are end-to-end only.

describe("summarizeSonarIssues (issue #934)", () => {
  it("returns zero counts for empty input", async () => {
    const { summarizeSonarIssues } = await import("./lib.js");
    const r = summarizeSonarIssues([]);
    assert.equal(r.open_count, 0);
    assert.deepEqual(r.top_issues, []);
  });

  it("counts by severity and type", async () => {
    const { summarizeSonarIssues } = await import("./lib.js");
    const issues = [
      { key: "a", severity: "BLOCKER", type: "BUG", message: "x", component: "f.java", line: 1 },
      { key: "b", severity: "BLOCKER", type: "VULNERABILITY", message: "y", component: "g.java", line: 2 },
      { key: "c", severity: "MINOR", type: "CODE_SMELL", message: "z", component: "h.java", line: 3 },
    ];
    const r = summarizeSonarIssues(issues);
    assert.equal(r.open_count, 3);
    assert.equal(r.by_severity.BLOCKER, 2);
    assert.equal(r.by_severity.MINOR, 1);
    assert.equal(r.by_type.BUG, 1);
    assert.equal(r.by_type.VULNERABILITY, 1);
    assert.equal(r.by_type.CODE_SMELL, 1);
  });

  it("caps top_issues to the requested limit, prioritizing higher severity", async () => {
    const { summarizeSonarIssues } = await import("./lib.js");
    const issues = [
      { key: "minor1", severity: "MINOR", type: "CODE_SMELL", message: "m", component: "x", line: 1 },
      { key: "blocker1", severity: "BLOCKER", type: "BUG", message: "b", component: "y", line: 2 },
      { key: "critical1", severity: "CRITICAL", type: "BUG", message: "c", component: "z", line: 3 },
      { key: "info1", severity: "INFO", type: "CODE_SMELL", message: "i", component: "w", line: 4 },
    ];
    const r = summarizeSonarIssues(issues, 2);
    assert.equal(r.top_issues.length, 2);
    // Highest severity first.
    assert.equal(r.top_issues[0].severity, "BLOCKER");
    assert.equal(r.top_issues[1].severity, "CRITICAL");
  });

  it("tolerates issues missing optional fields", async () => {
    const { summarizeSonarIssues } = await import("./lib.js");
    const issues = [
      { key: "a", severity: "MINOR" }, // no type, message, component, line
      { key: "b" }, // no severity either
    ];
    const r = summarizeSonarIssues(issues);
    assert.equal(r.open_count, 2);
    // Unknown severity should not crash.
    assert.equal(typeof r.by_severity, "object");
  });
});

describe("summarizeSonarHotspots (issue #934)", () => {
  it("returns zero counts for empty input", async () => {
    const { summarizeSonarHotspots } = await import("./lib.js");
    const r = summarizeSonarHotspots([]);
    assert.equal(r.open_count, 0);
    assert.deepEqual(r.top_hotspots, []);
  });

  it("captures probability + component + line per hotspot", async () => {
    const { summarizeSonarHotspots } = await import("./lib.js");
    const hotspots = [
      { key: "h1", vulnerabilityProbability: "HIGH", message: "x", component: "f.java", line: 10 },
      { key: "h2", vulnerabilityProbability: "LOW", message: "y", component: "g.java", line: 20 },
    ];
    const r = summarizeSonarHotspots(hotspots);
    assert.equal(r.open_count, 2);
    assert.equal(r.top_hotspots.length, 2);
    assert.equal(r.top_hotspots[0].key, "h1");
    assert.equal(r.top_hotspots[0].vulnerability_probability, "HIGH");
  });

  it("caps top_hotspots to the requested limit", async () => {
    const { summarizeSonarHotspots } = await import("./lib.js");
    const hotspots = Array.from({ length: 20 }, (_, i) => ({
      key: `h${i}`,
      vulnerabilityProbability: "MEDIUM",
      message: "m",
      component: "c",
      line: i,
    }));
    const r = summarizeSonarHotspots(hotspots, 5);
    assert.equal(r.top_hotspots.length, 5);
    assert.equal(r.open_count, 20);
  });
});

describe("runWatchSonarAnalysis input validation + skip path (issue #934)", () => {
  function makeRepoWithYaml(yamlBody) {
    const dir = mkdtempSync(join(tmpdir(), "gc-sonar-watch-"));
    execFileSync("git", ["-C", dir, "init", "-q"]);
    execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
    execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
    writeFileSync(join(dir, ".ground-control.yaml"), yamlBody);
    execFileSync("git", ["-C", dir, "add", ".ground-control.yaml"]);
    execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
    // Real origin so owner/repo resolves from the git remote, as production does. git ignores
    // GH_REPO; the `gh repo view` fallback honours it.
    execFileSync("git", ["-C", dir, "remote", "add", "origin", "https://github.com/fake/repo.git"]);
    return dir;
  }

  it("refuses when repo_path is missing", async () => {
    const { runWatchSonarAnalysis } = await import("./lib.js");
    const r = await runWatchSonarAnalysis({ repoPath: "", prNumber: 1 });
    assert.equal(r.ok, false);
    assert.equal(r.error, "sonar_watch_input_invalid");
  });

  it("refuses when pr_number is not a positive integer", async () => {
    const { runWatchSonarAnalysis } = await import("./lib.js");
    for (const bad of [0, -1, 1.5, "1", null, undefined]) {
      const r = await runWatchSonarAnalysis({
        authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }),
        fetchProducerEvidence: async () => null,
        repoPath: "/tmp",
        prNumber: bad,
      });
      assert.equal(r.ok, false, `bad=${bad}`);
      assert.equal(r.error, "sonar_watch_input_invalid");
    }
  });

  it("refuses when repo_path is not a git repository", async () => {
    const { runWatchSonarAnalysis } = await import("./lib.js");
    const dir = mkdtempSync(join(tmpdir(), "gc-sonar-not-git-"));
    try {
      const r = await runWatchSonarAnalysis({ repoPath: dir, prNumber: 1, fetchProducerEvidence: async () => null, authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }) });
      assert.equal(r.ok, false);
      assert.equal(r.error, "sonar_watch_repo_not_found");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("returns ok=true with quality_gate='NONE' when the repo has no sonarcloud block", async () => {
    const { runWatchSonarAnalysis } = await import("./lib.js");
    const dir = makeRepoWithYaml(
      "schema_version: 1\nproject: test-proj\n",
    );
    try {
      const r = await runWatchSonarAnalysis({ repoPath: dir, prNumber: 1, fetchProducerEvidence: async () => null, authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }) });
      assert.equal(r.ok, true);
      assert.equal(r.quality_gate, "NONE");
      assert.equal(r.skipped, true);
      assert.equal(r.issues_summary.open_count, 0);
      assert.equal(r.hotspots_summary.open_count, 0);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("returns ok=true skipped=true when the repo's .ground-control.yaml is missing", async () => {
    const { runWatchSonarAnalysis } = await import("./lib.js");
    const dir = mkdtempSync(join(tmpdir(), "gc-sonar-no-yaml-"));
    try {
      execFileSync("git", ["-C", dir, "init", "-q"]);
      execFileSync("git", ["-C", dir, "config", "user.email", "t@example.com"]);
      execFileSync("git", ["-C", dir, "config", "user.name", "t"]);
      writeFileSync(join(dir, "README"), "x\n");
      execFileSync("git", ["-C", dir, "add", "README"]);
      execFileSync("git", ["-C", dir, "commit", "-q", "-m", "init"]);
      // Real origin so owner/repo resolves from the git remote, as production does. git ignores
      // GH_REPO; the `gh repo view` fallback honours it.
      execFileSync("git", ["-C", dir, "remote", "add", "origin", "https://github.com/fake/repo.git"]);
      const r = await runWatchSonarAnalysis({ repoPath: dir, prNumber: 1, fetchProducerEvidence: async () => null, authorizeRepoRead: async () => ({ ok: true, repoSlug: "fake/repo" }) });
      // Missing yaml is the same effective state as no sonarcloud block.
      assert.equal(r.ok, true);
      assert.equal(r.quality_gate, "NONE");
      assert.equal(r.skipped, true);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
