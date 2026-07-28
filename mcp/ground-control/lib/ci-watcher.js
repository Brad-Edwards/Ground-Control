// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { _fetchCiRunFailedLog, _fetchCiRunSnapshot, _resolveLatestCiRunForBranch, _sleepMs, evaluateCiPollState, extractFailedStepsFromJobsJson, summarizeCiLogFailedOutput } from "./doc-coverage.js";
import { getOwnerRepo } from "./grc-legacy-compat-3.js";
import { ensureGitRepo } from "./grc-legacy-compat-4.js";
import { FINDING_CLASSIFICATIONS, FINDING_SWEEP_EVIDENCE_MAX, truncateReviewProse } from "./grc-legacy-compat.js";

export const TEST_QUALITY_REVIEW_DEFAULT_MODEL = "claude-sonnet-5";
export const TEST_QUALITY_REVIEW_TIMEOUT_MS = 600_000;
export const TEST_QUALITY_FINDING_FIELDS_DESCRIPTION = [
  '    `severity`        — exactly "critical" or "warning".',
  "    `location`        — `<file>::<TestClass>::<test_method>` OR `<file>:<line>`.",
  "    `problem`         — what's wrong (non-empty).",
  "    `why_it_matters`  — what regression this test would miss (optional but recommended).",
  "    `fix`             — specific fix, not vague advice (non-empty).",
  '    `classification`  — exactly "one-off" or "class". Same rules as the codex reviewer.',
  '    `sweep_evidence`  — REQUIRED when classification is "one-off". One-line statement of what you swept and what you did NOT find. Forbidden when classification is "class".',
  '    `category`        — REQUIRED when classification is "class"; forbidden when "one-off". Object: `shape` and `instances` (non-empty array).',
  "    `structural_blocker` — optional boolean. Set on a one-off that warrants verdict=don't-ship.",
].join("\n");
export const TEST_QUALITY_FINDING_EXAMPLE = '{"severity":"critical","location":"backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FooServiceTest.java::FooServiceTest::createFoo_returns_the_new_foo","problem":"Test calls fooService.create(...) but only verifies that the mock fooRepository.save was called. No assertion on the returned Foo.","why_it_matters":"Refactoring FooService.create to return null would still pass this test.","fix":"Assert on the returned Foo (id, name, status) after calling create().","classification":"class","category":{"shape":"@Test method that only verifies a mock interaction without asserting on the SUT\'s return value or state change","instances":["backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FooServiceTest.java:42","backend/src/test/java/com/keplerops/groundcontrol/unit/domain/BarServiceTest.java:55"]}}';
export function validateTestQualityFinding(raw, i) {
  if (raw == null || typeof raw !== "object") {
    throw new Error(`test-quality review blocking[${i}] is not an object`);
  }
  const { severity, location, problem, why_it_matters, fix, classification } = raw;
  if (severity !== "critical" && severity !== "warning") {
    throw new Error(
      `test-quality review blocking[${i}].severity must be 'critical' or 'warning', got ${JSON.stringify(severity)}`,
    );
  }
  if (typeof location !== "string" || location.trim() === "") {
    throw new Error(`test-quality review blocking[${i}].location must be a non-empty string`);
  }
  if (typeof problem !== "string" || problem.trim() === "") {
    throw new Error(`test-quality review blocking[${i}].problem must be a non-empty string`);
  }
  if (typeof fix !== "string" || fix.trim() === "") {
    throw new Error(`test-quality review blocking[${i}].fix must be a non-empty string`);
  }
  if (why_it_matters != null && typeof why_it_matters !== "string") {
    throw new Error(
      `test-quality review blocking[${i}].why_it_matters must be a string when set`,
    );
  }
  if (!FINDING_CLASSIFICATIONS.has(classification)) {
    throw new Error(
      `test-quality review blocking[${i}].classification must be 'one-off' or 'class', got ${JSON.stringify(classification)}`,
    );
  }

  // Class: require category{shape, instances>=1}; reject sweep_evidence.
  let category = null;
  if (classification === "class") {
    if (raw.category == null || typeof raw.category !== "object" || Array.isArray(raw.category)) {
      throw new Error(
        `test-quality review blocking[${i}] has classification 'class' but is missing required object field 'category' ({shape, instances})`,
      );
    }
    if (typeof raw.category.shape !== "string" || raw.category.shape.trim() === "") {
      throw new Error(`test-quality review blocking[${i}].category.shape must be a non-empty string`);
    }
    if (!Array.isArray(raw.category.instances) || raw.category.instances.length === 0) {
      throw new Error(
        `test-quality review blocking[${i}].category.instances must be a non-empty array`,
      );
    }
    raw.category.instances.forEach((inst, j) => {
      if (typeof inst !== "string" || inst.trim() === "") {
        throw new Error(`test-quality review blocking[${i}].category.instances[${j}] must be a non-empty string`);
      }
    });
    if (raw.sweep_evidence !== undefined && raw.sweep_evidence !== null) {
      throw new Error(
        `test-quality review blocking[${i}] has classification 'class' but also carries 'sweep_evidence' — class findings use category.instances instead`,
      );
    }
    category = { shape: raw.category.shape.trim(), instances: raw.category.instances.map((s) => s.trim()) };
  } else {
    // one-off: require sweep_evidence; reject category.
    if (raw.category !== undefined && raw.category !== null) {
      throw new Error(
        `test-quality review blocking[${i}] has classification 'one-off' but also carries 'category' — omit it for one-off findings`,
      );
    }
    if (typeof raw.sweep_evidence !== "string" || raw.sweep_evidence.trim() === "") {
      throw new Error(
        `test-quality review blocking[${i}] has classification 'one-off' but is missing required 'sweep_evidence' (one-line statement of what you swept)`,
      );
    }
  }

  let structuralBlocker = false;
  if (raw.structural_blocker !== undefined && raw.structural_blocker !== null) {
    if (typeof raw.structural_blocker !== "boolean") {
      throw new Error(`test-quality review blocking[${i}].structural_blocker must be a boolean when set`);
    }
    if (raw.structural_blocker === true && classification === "class") {
      throw new Error(
        `test-quality review blocking[${i}] has classification 'class' so structural_blocker is implicit — set it only on one-off`,
      );
    }
    structuralBlocker = raw.structural_blocker === true;
  }

  const finding = {
    severity,
    location: location.trim(),
    problem: problem.trim(),
    why_it_matters: typeof why_it_matters === "string" ? why_it_matters.trim() : "",
    fix: fix.trim(),
    classification,
  };
  if (category !== null) finding.category = category;
  if (raw.sweep_evidence != null && classification === "one-off") {
    finding.sweep_evidence = truncateReviewProse(raw.sweep_evidence.trim(), FINDING_SWEEP_EVIDENCE_MAX);
  }
  if (structuralBlocker) finding.structural_blocker = true;
  return finding;
}
export async function runWatchCiRun({
  repoPath,
  branch,
  runId = null,
  queuedTimeoutSeconds = 300,
  totalTimeoutSeconds = 2700,
  pollIntervalSeconds = 15,
}) {
  if (typeof repoPath !== "string" || repoPath.length === 0) {
    return {
      ok: false,
      error: "ci_watch_input_invalid",
      message: "repo_path is required",
    };
  }
  if (typeof branch !== "string" || branch.length === 0) {
    return {
      ok: false,
      error: "ci_watch_input_invalid",
      message: "branch is required",
    };
  }
  if (runId !== null && runId !== undefined) {
    if (
      typeof runId !== "number" ||
      !Number.isInteger(runId) ||
      runId <= 0
    ) {
      return {
        ok: false,
        error: "ci_watch_input_invalid",
        message: "run_id must be a positive integer when provided",
      };
    }
  }
  for (const [name, value] of [
    ["queued_timeout_seconds", queuedTimeoutSeconds],
    ["total_timeout_seconds", totalTimeoutSeconds],
    ["poll_interval_seconds", pollIntervalSeconds],
  ]) {
    if (
      typeof value !== "number" ||
      !Number.isInteger(value) ||
      value <= 0
    ) {
      return {
        ok: false,
        error: "ci_watch_input_invalid",
        message: `${name} must be a positive integer`,
      };
    }
  }

  let repoRoot;
  try {
    repoRoot = await ensureGitRepo(repoPath);
  } catch (e) {
    return {
      ok: false,
      error: "ci_watch_repo_not_found",
      message: e?.message ?? "ensureGitRepo failed",
    };
  }

  // Resolve owner/name from the repo's git remote up-front so every
  // subsequent `gh` call can pass `--repo <slug>` and ignore any rogue
  // `GH_REPO` env var on the MCP host.
  let repoSlug;
  try {
    const { owner, name } = await getOwnerRepo(repoRoot);
    repoSlug = `${owner}/${name}`;
  } catch (e) {
    return {
      ok: false,
      error: "ci_watch_repo_lookup_failed",
      message: e?.message ?? "getOwnerRepo failed",
    };
  }

  // Resolve the run id if the caller didn't supply one.
  let effectiveRunId = runId ?? null;
  if (effectiveRunId === null) {
    let latest;
    try {
      latest = await _resolveLatestCiRunForBranch(repoRoot, repoSlug, branch);
    } catch (e) {
      return {
        ok: false,
        error: "ci_watch_run_lookup_failed",
        message: e?.message ?? "gh run list failed",
        branch,
      };
    }
    if (latest === null) {
      return {
        ok: false,
        error: "ci_watch_no_run_for_branch",
        message: `no CI runs found for branch '${branch}'`,
        branch,
      };
    }
    effectiveRunId =
      typeof latest.databaseId === "number" ? latest.databaseId : null;
    if (effectiveRunId === null) {
      return {
        ok: false,
        error: "ci_watch_run_lookup_failed",
        message: "gh run list returned no databaseId",
        branch,
      };
    }
  }

  const startMs = Date.now();
  let snapshot = null;
  while (true) {
    try {
      snapshot = await _fetchCiRunSnapshot(repoRoot, repoSlug, effectiveRunId);
    } catch (e) {
      return {
        ok: false,
        error: "ci_watch_snapshot_failed",
        message: e?.message ?? "gh run view failed",
        run_id: effectiveRunId,
      };
    }
    const elapsedSeconds = Math.floor((Date.now() - startMs) / 1000);
    const decision = evaluateCiPollState({
      status: snapshot.status,
      elapsedSeconds,
      queuedTimeoutSeconds,
      totalTimeoutSeconds,
    });
    if (decision.action === "complete") {
      break;
    }
    if (decision.action === "queued_too_long") {
      return {
        ok: true,
        run_id: effectiveRunId,
        conclusion: "queued_too_long",
        status: snapshot.status ?? "queued",
        url: snapshot.url ?? "",
        duration_seconds: elapsedSeconds,
        failed_steps: [],
        log_summary: null,
      };
    }
    if (decision.action === "timed_out") {
      return {
        ok: true,
        run_id: effectiveRunId,
        conclusion: "timed_out",
        status: snapshot.status ?? "in_progress",
        url: snapshot.url ?? "",
        duration_seconds: elapsedSeconds,
        failed_steps: [],
        log_summary: null,
      };
    }
    await _sleepMs(pollIntervalSeconds * 1000);
  }

  // Terminal state reached. Compute return envelope.
  const elapsedSeconds = Math.floor((Date.now() - startMs) / 1000);
  const ghConclusion = typeof snapshot.conclusion === "string" ? snapshot.conclusion : "";
  const isFailure =
    ghConclusion === "failure" ||
    ghConclusion === "cancelled" ||
    ghConclusion === "timed_out" ||
    ghConclusion === "action_required" ||
    ghConclusion === "startup_failure";

  let failedSteps = [];
  let logSummary = null;
  if (isFailure) {
    failedSteps = extractFailedStepsFromJobsJson(snapshot);
    const rawLog = await _fetchCiRunFailedLog(repoRoot, repoSlug, effectiveRunId);
    logSummary = summarizeCiLogFailedOutput(rawLog, 4096);
  }

  return {
    ok: true,
    run_id: effectiveRunId,
    conclusion: ghConclusion || (isFailure ? "failure" : "success"),
    status: typeof snapshot.status === "string" ? snapshot.status : "completed",
    url: typeof snapshot.url === "string" ? snapshot.url : "",
    duration_seconds: elapsedSeconds,
    failed_steps: failedSteps,
    log_summary: logSummary,
  };
}
