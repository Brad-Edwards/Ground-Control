// Extracted from lib.js (issue #1355).
//
// lib.js had reached 20,634 lines against the repo's 500-LOC limit
// (docs/CODING_STANDARDS.md, Sonar S104). It contained no mutual recursion, so it was
// split along its own dependency layering. lib.js remains the barrel every caller imports.

import { mkdirSync, realpathSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { sonarGateFindings } from "../gate-finding-adapters.js";
import { _sleepMs } from "./doc-coverage.js";
import { ensureGitRepo } from "./grc-legacy-compat-4.js";
import { assertRealpathInRepo } from "./repo-context-2.js";
import { resolveRepoRelativePath } from "./repo-context.js";
import { SONAR_BASE_URL, SONAR_EXPORT_RETENTION, SONAR_RETRY_DELAYS_MS, _pruneSonarExports, _readSonarCloudConfigFromRepo, _sonarAuthHeader, shouldRetrySonarStatus, summarizeSonarHotspots, summarizeSonarIssues } from "./repo-vocabulary.js";

export const VERIFICATION_STATUSES = ["PROVEN", "REFUTED", "TIMEOUT", "UNKNOWN", "ERROR"];
export const ASSURANCE_LEVELS = ["L0", "L1", "L2", "L3"];
async function _sonarFetchWithRetry(url, init) {
  let lastErr = null;
  for (let attempt = 0; attempt <= SONAR_RETRY_DELAYS_MS.length; attempt++) {
    let resp;
    try {
      resp = await fetch(url, init);
    } catch (err) {
      // Network failure (DNS, connection reset, timeout). Treated as
      // transient at the same retry tier as 5xx.
      lastErr = err;
      if (attempt < SONAR_RETRY_DELAYS_MS.length) {
        await _sleepMs(SONAR_RETRY_DELAYS_MS[attempt]);
        continue;
      }
      throw err;
    }
    if (!shouldRetrySonarStatus(resp.status)) return resp;
    if (attempt >= SONAR_RETRY_DELAYS_MS.length) return resp;
    await _sleepMs(SONAR_RETRY_DELAYS_MS[attempt]);
  }
  // Unreachable — loop above always returns or throws. Keep the throw
  // as a sentinel so a future refactor that breaks the loop semantics
  // surfaces cleanly.
  throw lastErr ?? new Error("sonar fetch retry exhausted");
}
async function _fetchSonarQualityGate({ projectKey, prNumber, token }) {
  const url = `${SONAR_BASE_URL}/api/qualitygates/project_status?projectKey=${encodeURIComponent(projectKey)}&pullRequest=${encodeURIComponent(String(prNumber))}`;
  const resp = await _sonarFetchWithRetry(url, {
    headers: { Authorization: _sonarAuthHeader(token), Accept: "application/json" },
  });
  if (resp.status === 404) return { available: false };
  if (!resp.ok) {
    throw new Error(`sonar quality gate fetch failed: HTTP ${resp.status}`);
  }
  const data = await resp.json();
  const status = data?.projectStatus?.status;
  return {
    available: typeof status === "string" && status.length > 0,
    status: typeof status === "string" ? status : "UNKNOWN",
  };
}
async function _fetchSonarIssues({ projectKey, prNumber, token, maxPages = 20 }) {
  const out = [];
  for (let page = 1; page <= maxPages; page++) {
    const url = `${SONAR_BASE_URL}/api/issues/search?componentKeys=${encodeURIComponent(projectKey)}&pullRequest=${encodeURIComponent(String(prNumber))}&resolved=false&ps=500&p=${page}`;
    const resp = await _sonarFetchWithRetry(url, {
      headers: { Authorization: _sonarAuthHeader(token), Accept: "application/json" },
    });
    if (!resp.ok) {
      throw new Error(`sonar issues fetch failed (page ${page}): HTTP ${resp.status}`);
    }
    const data = await resp.json();
    const issues = Array.isArray(data?.issues) ? data.issues : [];
    out.push(...issues);
    const total = typeof data?.total === "number" ? data.total : out.length;
    if (out.length >= total) break;
    if (issues.length === 0) break;
  }
  return out;
}
async function _fetchSonarHotspots({ projectKey, prNumber, token, maxPages = 20 }) {
  const out = [];
  for (let page = 1; page <= maxPages; page++) {
    const url = `${SONAR_BASE_URL}/api/hotspots/search?projectKey=${encodeURIComponent(projectKey)}&pullRequest=${encodeURIComponent(String(prNumber))}&status=TO_REVIEW&ps=500&p=${page}`;
    const resp = await _sonarFetchWithRetry(url, {
      headers: { Authorization: _sonarAuthHeader(token), Accept: "application/json" },
    });
    if (!resp.ok) {
      throw new Error(`sonar hotspots fetch failed (page ${page}): HTTP ${resp.status}`);
    }
    const data = await resp.json();
    const hotspots = Array.isArray(data?.hotspots) ? data.hotspots : [];
    out.push(...hotspots);
    const paging = data?.paging;
    const total = typeof paging?.total === "number" ? paging.total : out.length;
    if (out.length >= total) break;
    if (hotspots.length === 0) break;
  }
  return out;
}
function _writeSonarExport(repoRoot, prNumber, payload) {
  // Best-effort, repo-relative, containment-checked write under
  // .gc/sonar/. Returns the rel path on success, null on any failure
  // (the export is a convenience for drilldown — never a correctness
  // requirement, so failures are non-fatal).
  try {
    const relDir = ".gc/sonar";
    const fileName = `${prNumber}-${Date.now()}.json`;
    const rel = `${relDir}/${fileName}`;
    const resolved = resolveRepoRelativePath(repoRoot, rel, "sonar_export_path");
    if (!resolved.ok) return null;
    const abs = resolved.abs;
    mkdirSync(dirname(abs), { recursive: true });
    const realRepo = realpathSync(repoRoot);
    const contain = assertRealpathInRepo(realRepo, abs, "sonar_export_path");
    if (!contain.ok) return null;
    // Prune older exports before writing to cap directory size. Runs
    // BEFORE the write so a transient OOM (unlikely) doesn't leave
    // both the new file and the now-deleted old files in an
    // intermediate state.
    _pruneSonarExports(dirname(abs), SONAR_EXPORT_RETENTION);
    writeFileSync(abs, JSON.stringify(payload, null, 2));
    return rel;
  } catch {
    return null;
  }
}
function validateWatchSonarAnalysisInput({ repoPath, prNumber, initialWaitSeconds, totalTimeoutSeconds, pollIntervalSeconds }) {
  if (typeof repoPath !== "string" || repoPath.length === 0) {
    return { ok: false, error: "sonar_watch_input_invalid", message: "repo_path is required" };
  }
  if (typeof prNumber !== "number" || !Number.isInteger(prNumber) || prNumber <= 0) {
    return { ok: false, error: "sonar_watch_input_invalid", message: "pr_number must be a positive integer" };
  }
  for (const [name, value] of [
    ["initial_wait_seconds", initialWaitSeconds],
    ["total_timeout_seconds", totalTimeoutSeconds],
    ["poll_interval_seconds", pollIntervalSeconds],
  ]) {
    if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
      return { ok: false, error: "sonar_watch_input_invalid", message: `${name} must be a non-negative integer` };
    }
  }
  return null;
}

// Poll for the quality gate; PRs not yet analyzed return 404. Returns
// `{ qg }` once available, or `{ earlyReturn }` carrying the exact envelope
// the caller should return immediately (fetch error or overall timeout).
async function pollSonarQualityGateUntilReady({ projectKey, prNumber, token, totalTimeoutSeconds, pollIntervalSeconds }) {
  const startMs = Date.now();
  while (true) {
    let qg;
    try {
      qg = await _fetchSonarQualityGate({ projectKey, prNumber, token });
    } catch (e) {
      return {
        earlyReturn: {
          ok: false,
          error: "sonar_watch_quality_gate_failed",
          message: e?.message ?? "sonar quality gate fetch failed",
          pr_number: prNumber,
        },
      };
    }
    if (qg.available) return { qg };
    const elapsedSeconds = Math.floor((Date.now() - startMs) / 1000);
    if (elapsedSeconds > totalTimeoutSeconds) {
      return {
        earlyReturn: {
          ok: true,
          skipped: false,
          pr_number: prNumber,
          quality_gate: "NONE",
          issues_summary: { open_count: 0, by_severity: {}, by_type: {}, top_issues: [] },
          hotspots_summary: { open_count: 0, top_hotspots: [] },
          full_issue_export_path: null,
          timed_out: true,
        },
      };
    }
    if (pollIntervalSeconds > 0) {
      await _sleepMs(pollIntervalSeconds * 1000);
    }
  }
}
// Fetch the PR's open issues then hotspots. Returns `{ issues, hotspots }`, or
// `{ earlyReturn }` carrying the exact failure envelope the caller returns.
async function _fetchSonarIssuesAndHotspots({ projectKey, prNumber, token, qgStatus }) {
  let issues = [];
  let hotspots = [];
  try {
    issues = await _fetchSonarIssues({ projectKey, prNumber, token });
  } catch (e) {
    return {
      earlyReturn: {
        ok: false,
        error: "sonar_watch_issues_fetch_failed",
        message: e?.message ?? "sonar issues fetch failed",
        pr_number: prNumber,
        quality_gate: qgStatus,
      },
    };
  }
  try {
    hotspots = await _fetchSonarHotspots({ projectKey, prNumber, token });
  } catch (e) {
    return {
      earlyReturn: {
        ok: false,
        error: "sonar_watch_hotspots_fetch_failed",
        message: e?.message ?? "sonar hotspots fetch failed",
        pr_number: prNumber,
        quality_gate: qgStatus,
      },
    };
  }
  return { issues, hotspots };
}
export async function runWatchSonarAnalysis({
  repoPath,
  prNumber,
  initialWaitSeconds = 60,
  totalTimeoutSeconds = 1800,
  pollIntervalSeconds = 30,
}) {
  const inputError = validateWatchSonarAnalysisInput({
    repoPath, prNumber, initialWaitSeconds, totalTimeoutSeconds, pollIntervalSeconds,
  });
  if (inputError) return inputError;

  let repoRoot;
  try {
    repoRoot = await ensureGitRepo(repoPath);
  } catch (e) {
    return {
      ok: false,
      error: "sonar_watch_repo_not_found",
      message: e?.message ?? "ensureGitRepo failed",
    };
  }

  const sonarConfig = _readSonarCloudConfigFromRepo(repoRoot);
  if (sonarConfig === null) {
    // No sonarcloud block — skip entirely. Mirrors current /implement Step 11.
    return {
      ok: true,
      skipped: true,
      pr_number: prNumber,
      quality_gate: "NONE",
      issues_summary: { open_count: 0, by_severity: {}, by_type: {}, top_issues: [] },
      hotspots_summary: { open_count: 0, top_hotspots: [] },
      full_issue_export_path: null,
    };
  }

  // Read at call time and passed only in the Authorization header - never argv,
  // telemetry, an export, or a returned envelope (ADR-036). The value reaches
  // process.env from the server's declared startup sources (lib/host-env.js),
  // so the message names them: an operator, not the agent, repairs this state,
  // and both files are read at startup (issue #946).
  const token = process.env.SONAR_TOKEN;
  if (typeof token !== "string" || token.length === 0) {
    return {
      ok: false,
      error: "sonar_watch_token_missing",
      message: "SONAR_TOKEN is not set on the MCP host. Set it in the launch root's .env or in "
        + "~/.config/ground-control/env, then restart the MCP server; both are read at startup.",
      pr_number: prNumber,
    };
  }

  // Initial wait for analysis propagation (Step 11's existing 60s pause).
  if (initialWaitSeconds > 0) {
    await _sleepMs(initialWaitSeconds * 1000);
  }

  const pollResult = await pollSonarQualityGateUntilReady({
    projectKey: sonarConfig.projectKey,
    prNumber,
    token,
    totalTimeoutSeconds,
    pollIntervalSeconds,
  });
  if (pollResult.earlyReturn) return pollResult.earlyReturn;
  const qg = pollResult.qg;

  const fetched = await _fetchSonarIssuesAndHotspots({
    projectKey: sonarConfig.projectKey,
    prNumber,
    token,
    qgStatus: qg.status,
  });
  if (fetched.earlyReturn) return fetched.earlyReturn;
  const { issues, hotspots } = fetched;

  const exportPath = _writeSonarExport(repoRoot, prNumber, {
    pr_number: prNumber,
    quality_gate: qg.status,
    issues,
    hotspots,
    fetched_at: new Date().toISOString(),
  });

  // The measurement projection is built here, at the boundary that owns the full issue and
  // hotspot lists (issue #1355). Building it from `issues_summary.top_issues` instead would cap
  // the record at ten and report a truncated count as a complete one; the raw arrays never leave
  // this function.
  const measurement = sonarGateFindings(issues, hotspots);

  return {
    ok: true,
    skipped: false,
    pr_number: prNumber,
    quality_gate: qg.status,
    issues_summary: summarizeSonarIssues(issues),
    hotspots_summary: summarizeSonarHotspots(hotspots),
    full_issue_export_path: exportPath,
    measurement_findings: measurement.findings,
    measurement_findings_dropped: measurement.dropped,
  };
}
export const GOVERNANCE_STATUS_ENUMS = {
  verification_result: VERIFICATION_STATUSES,
};
export const GOVERNANCE_FIELDS = {
  verification_result: {
    // Mirrors VerificationResultRequest: targetId (optional UUID), requirementId
    // (optional UUID), prover (@NotBlank), property (optional), result (@NotNull
    // VerificationStatus), assuranceLevel (@NotNull), evidence (Map, opaque),
    // verifiedAt (@NotNull Instant), expiresAt (optional Instant).
    // uid/title/description/outcome/status/metadata were not in the DTO (#1106).
    create: [
      "target_id", "requirement_id", "prover", "property",
      "result", "assurance_level", "evidence", "verified_at", "expires_at",
    ],
    // Mirrors UpdateVerificationResultRequest: identical shape to create (all
    // optional on update, same fields — no create-only keys to drop).
    update: [
      "target_id", "requirement_id", "prover", "property",
      "result", "assurance_level", "evidence", "verified_at", "expires_at",
    ],
  },
};
export function validateGovernanceStatus(entity, status) {
  if (status === undefined || status === null || status === "") return;
  const allowed = GOVERNANCE_STATUS_ENUMS[entity];
  if (!allowed) {
    throw new Error(`'status' is not valid for entity='${entity}'`);
  }
  if (!allowed.includes(status)) {
    throw new Error(
      `'status'='${status}' is not valid for entity='${entity}'. ` +
        `Valid values: ${allowed.join(", ")}`,
    );
  }
}
