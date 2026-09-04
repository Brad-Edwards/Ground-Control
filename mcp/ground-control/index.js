#!/usr/bin/env node
// Ground Control MCP Server
//
// The server requires no environment configuration: it starts, and every
// registered tool works, with none of the variables below set. They are read
// from the consumer repo's `.env` file at startup (a shell-exported value wins),
// and each one only switches on an optional behavior. See README.md.
//
//   GC_BASE_URL                              Sink for workflow-run lifecycle
//                                             measurement emission. Unset disables
//                                             the emitter; nothing is dispatched.
//   GROUND_CONTROL_API_TOKEN                 Bearer token for that emission, when
//                                             the sink requires one.
//   GROUND_CONTROL_PACK_REGISTRY_ADMIN_TOKEN Legacy token, preferred over the above
//                                             for the two cross-project rollups.
//   GC_CODEX_TIMEOUT_MS                      Per-invocation timeout for the
//                                             Codex-backed tools.
//   GC_CODEX_REVIEW_PARALLEL                 Run the core and security reviewers
//                                             concurrently.
//   GC_CODEX_REVIEW_MAX_DIFF_BYTES           Diff-slice budget for a review cycle.
//   SONAR_TOKEN                              Lets gc_watch_sonar_analysis read the
//                                             SonarCloud quality gate.
//
// ============================================================================
// TOOL SURFACE (issue #1500 re-platform)
// ============================================================================
//
// The MCP server over repo-local files (issue #1500) exposes 30 tools that
// back the /implement, /quickfix, and /review workflow mechanics plus the
// coding-agent<->reviewer separation. There is no backend, database, or
// generic entity CRUD surface — requirements and ADRs are read/edited as
// repo files directly. The /integrate lane's gc_integration_manager was removed
// as dead code in #1506 and is not registered here (issue #1553).
// Registration lives in ./tools/*.js:
//   tools/query.js               — gc_get_repo_ground_control_context,
//                                   gc_create_github_issue, gc_remember,
//                                   gc_codex_architecture_preflight,
//                                   gc_post_implementation_plan,
//                                   gc_close_issue_after_merge, gc_codex_review,
//                                   gc_test_quality_review
//   tools/post-decision-record.js — gc_post_decision_record, gc_post_final_report,
//                                   gc_assert_completion, gc_render_pr_body,
//                                   gc_get_issue_thread, gc_watch_ci_run,
//                                   gc_codex_review_cycle, gc_test_quality_review_cycle
//   tools/review-cap-disposition.js — gc_review_cap_disposition, gc_codex_job,
//                                   gc_watch_sonar_analysis, gc_prepare_implement_branch,
//                                   gc_implement_mechanical, gc_synchronize_implement_branch,
//                                   gc_create_synchronized_implement_pr,
//                                   gc_record_execution_obligation,
//                                   gc_mark_implement_issue_picked_up,
//                                   gc_authorize_execution_obligation_wontfix,
//                                   gc_resolve_workflow_route, gc_codex_verify_finding
//   tools/pr-review.js           — gc_get_pr_review_context,
//                                   gc_remediate_pull_request (maintainer /review lane, #1535)

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { installToolTelemetry } from "./telemetry.js";
import { registerQuery } from "./tools/query.js";
import { registerPostDecisionRecord } from "./tools/post-decision-record.js";
import { registerReviewCapDisposition } from "./tools/review-cap-disposition.js";
import { registerPrReview } from "./tools/pr-review.js";
import pkg from "./package.json" with { type: "json" };


// Load .env from cwd before any auth header is composed.
function loadDotenvFromCwd() {
  let body;
  try {
    body = readFileSync(join(process.cwd(), ".env"), "utf-8");
  } catch (err) {
    if (err.code === "ENOENT") return;
    throw err;
  }
  for (const rawLine of body.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined || process.env[key] === "") {
      process.env[key] = value;
    }
  }
}
loadDotenvFromCwd();

// The version advertised to clients in the initialize handshake is sourced from
// package.json so it cannot drift from the package it ships in (issue #633). See
// README.md "Server version and client compatibility" for the bump policy.
const server = new McpServer({ name: "ground-control", version: pkg.version });

// Install per-tool telemetry capture (ADR-059, issue #1104).
// Must run BEFORE any server.tool / server.registerTool registration so all
// tools are wrapped. Fail-open: a telemetry write failure never affects the
// original tool result.
installToolTelemetry(server);

// Tool registrations live in ./tools/*.
registerQuery(server);
registerPostDecisionRecord(server);
registerReviewCapDisposition(server);
registerPrReview(server);

// ============================================================================
// Startup
// ============================================================================

try {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error(
    "[ground-control] MCP surface over repo-local files (issue #1500): requirements and ADRs live " +
      "in the repo, no backend and no database. The surviving surface is the /implement workflow " +
      "mechanics plus the coding-agent↔reviewer separation tools.",
  );
} catch (e) {
  console.error(e);
  process.exit(1);
}
