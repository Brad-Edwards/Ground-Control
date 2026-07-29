// Split from lib.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  aggregateWorkflowRuns,
  buildReviewAutoDispositionRecord,
  collectDispositionSignals,
  crossProjectAggregateWorkflowRuns,
  importWorkflowRunCost,
  listWorkflowRuns,
  parseReviewAutoDispositionMarkers,
  recordWorkflowRunEvent,
  scoreDisposition,
} from "./lib.js";

// ---------------------------------------------------------------------------
// Workflow-run telemetry lib helpers (issue #859)
// ---------------------------------------------------------------------------

const WORKFLOW_RUN_BASE_URL = "https://gc.test";

const WORKFLOW_RUN_ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

const WORKFLOW_RUN_ORIGINAL_FETCH = globalThis.fetch;

function withWorkflowRunEnv(fn) {
  return async () => {
    process.env.GC_BASE_URL = WORKFLOW_RUN_BASE_URL;
    delete process.env.GROUND_CONTROL_API_TOKEN;
    try {
      await fn();
    } finally {
      if (WORKFLOW_RUN_ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
      else process.env.GC_BASE_URL = WORKFLOW_RUN_ORIGINAL_BASE_URL;
      globalThis.fetch = WORKFLOW_RUN_ORIGINAL_FETCH;
    }
  };
}

function makeWorkflowRunFetchSpy({ status = 201, body = {} } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    const parsedBody = opts && opts.body ? JSON.parse(opts.body) : null;
    calls.push({ url: url.toString(), method: opts?.method ?? "GET", body: parsedBody });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  };
  return calls;
}

describe("recordWorkflowRunEvent", () => {
  it(
    "POSTs to /api/v1/workflow-runs/{runId}/events",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 201, body: { id: "evt-1" } });
      await recordWorkflowRunEvent(
        "run-abc",
        {
          phase: "plan",
          event_type: "COMPLETED",
          occurred_at: "2026-01-01T12:00:00Z",
          provenance: "ISSUE_THREAD",
        },
        "proj-a",
      );
      assert.equal(calls[0].method, "POST");
      const url = new URL(calls[0].url);
      assert.equal(url.pathname, "/api/v1/workflow-runs/run-abc/events");
      // project scopes the run lookup (issue #859 security review).
      assert.equal(url.searchParams.get("project"), "proj-a");
      assert.equal(calls[0].body.phase, "plan");
      assert.equal(calls[0].body.eventType, "COMPLETED");
      assert.equal(calls[0].body.occurredAt, "2026-01-01T12:00:00Z");
    }),
  );

});

describe("importWorkflowRunCost", () => {
  it(
    "POSTs to /api/v1/workflow-runs/{runId}/cost",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 200, body: { id: "run-1", costProxy: 1.5 } });
      await importWorkflowRunCost("run-xyz", { cost_proxy: 1.5, cost_currency: "USD" }, "proj-a");
      assert.equal(calls[0].method, "POST");
      const url = new URL(calls[0].url);
      assert.equal(url.pathname, "/api/v1/workflow-runs/run-xyz/cost");
      assert.equal(url.searchParams.get("project"), "proj-a");
      assert.equal(calls[0].body.costProxy, 1.5);
      assert.equal(calls[0].body.costCurrency, "USD");
    }),
  );
});

describe("listWorkflowRuns", () => {
  it(
    "GETs /api/v1/workflow-runs with project and limit params",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 200, body: [] });
      await listWorkflowRuns({ project: "p1", limit: 20 });
      assert.equal(calls[0].method, "GET");
      const url = new URL(calls[0].url);
      assert.equal(url.pathname, "/api/v1/workflow-runs");
      assert.equal(url.searchParams.get("project"), "p1");
      assert.equal(url.searchParams.get("limit"), "20");
    }),
  );

  it(
    "omits undefined params",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 200, body: [] });
      await listWorkflowRuns({});
      const url = new URL(calls[0].url);
      assert.equal(url.searchParams.get("project"), null);
      assert.equal(url.searchParams.get("limit"), null);
    }),
  );
});

describe("aggregateWorkflowRuns", () => {
  it(
    "GETs /api/v1/workflow-runs/aggregate with filter params",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 200, body: { totalRuns: 3 } });
      await aggregateWorkflowRuns({
        project: "p2",
        workflowType: "IMPLEMENT",
        from: "2026-01-01",
        to: "2026-06-01",
      });
      assert.equal(calls[0].method, "GET");
      const url = new URL(calls[0].url);
      assert.equal(url.pathname, "/api/v1/workflow-runs/aggregate");
      assert.equal(url.searchParams.get("project"), "p2");
      assert.equal(url.searchParams.get("workflowType"), "IMPLEMENT");
      assert.equal(url.searchParams.get("from"), "2026-01-01");
      assert.equal(url.searchParams.get("to"), "2026-06-01");
    }),
  );
});

describe("crossProjectAggregateWorkflowRuns", () => {
  it(
    "GETs /api/v1/workflow-runs/cross-project-aggregate without project param",
    withWorkflowRunEnv(async () => {
      const calls = makeWorkflowRunFetchSpy({ status: 200, body: { totalRuns: 999 } });
      await crossProjectAggregateWorkflowRuns({ workflowType: "QUICKFIX" });
      assert.equal(calls[0].method, "GET");
      const url = new URL(calls[0].url);
      assert.equal(url.pathname, "/api/v1/workflow-runs/cross-project-aggregate");
      assert.equal(url.searchParams.get("project"), null);
      assert.equal(url.searchParams.get("workflowType"), "QUICKFIX");
    }),
  );
});

// ---------------------------------------------------------------------------
// gc_review_cap_disposition (issue #1245)
// ---------------------------------------------------------------------------

describe("scoreDisposition", () => {
  const cfg = { enabled: true, mode: "authoritative", max_auto_overrides: 1, judge: { enabled: false, model: null } };

  it("hard ceiling never yields a 2nd one_more_cycle (low risk → proceed)", () => {
    const r = scoreDisposition(
      {
        reviewer: "codex",
        prior_auto_overrides: 1,
        diff: { files_changed: 2, lines_added: 10, lines_deleted: 5 },
        surfaces: [],
        findings: { one_off_count: 0, class_count: 0, has_security_finding: false },
      },
      cfg,
    );
    assert.notEqual(r.disposition, "one_more_cycle");
    assert.equal(r.disposition, "proceed");
    assert.equal(r.decided_by, "ceiling");
    assert.equal(r.next_action, "proceed_to_phase_c");
  });

  it("hard ceiling on a high-risk change escalates, never one_more_cycle", () => {
    const r = scoreDisposition(
      {
        reviewer: "codex",
        prior_auto_overrides: 1,
        diff: { files_changed: 5, lines_added: 200, lines_deleted: 30 },
        surfaces: ["mcp_tool"],
        findings: { one_off_count: 3, class_count: 1, has_security_finding: true },
      },
      cfg,
    );
    assert.notEqual(r.disposition, "one_more_cycle");
    assert.equal(r.disposition, "escalate_to_human");
    assert.equal(r.decided_by, "ceiling");
  });

  it("codex + security_relevant fast-paths to one_more_cycle", () => {
    const r = scoreDisposition(
      {
        reviewer: "codex",
        prior_auto_overrides: 0,
        diff: { files_changed: 4, lines_added: 120, lines_deleted: 10 },
        surfaces: ["config_parser"],
        findings: { one_off_count: 2, class_count: 0, has_security_finding: false },
      },
      cfg,
    );
    assert.equal(r.disposition, "one_more_cycle");
    assert.equal(r.decided_by, "fast_path");
    assert.equal(r.next_action, "reinvoke_cycle_with_auto_override");
  });

  it("tiny test-quality nit fast-paths to proceed", () => {
    const r = scoreDisposition(
      {
        reviewer: "test-quality",
        prior_auto_overrides: 0,
        diff: { files_changed: 1, lines_added: 8, lines_deleted: 2 },
        surfaces: ["doc"],
        findings: { one_off_count: 1, class_count: 0, has_security_finding: false },
      },
      cfg,
    );
    assert.equal(r.disposition, "proceed");
    assert.equal(r.decided_by, "fast_path");
  });

  it("gray zone (medium diff, non-security) is judge_needed (provisional escalate)", () => {
    const r = scoreDisposition(
      {
        reviewer: "test-quality",
        prior_auto_overrides: 0,
        diff: { files_changed: 6, lines_added: 140, lines_deleted: 60 },
        surfaces: ["user_visible"],
        findings: { one_off_count: 4, class_count: 1, has_security_finding: false },
      },
      cfg,
    );
    assert.equal(r.decided_by, "judge_needed");
    assert.equal(r.disposition, "escalate_to_human");
  });

  it("a tiny low-risk diff with UNKNOWN findings shape never fast-paths to proceed", () => {
    // Same shape as the "tiny test-quality nit → proceed" case, but findings
    // are flagged unknown (the MCP path with no findings_summary). The proceed
    // fast-path must be foreclosed so a dropped signal can't launder a class
    // finding into an automatic proceed.
    const r = scoreDisposition(
      {
        reviewer: "test-quality",
        prior_auto_overrides: 0,
        diff: { files_changed: 1, lines_added: 8, lines_deleted: 2 },
        surfaces: ["doc"],
        findings: { one_off_count: 0, class_count: 0, has_security_finding: false, known: false },
      },
      cfg,
    );
    assert.notEqual(r.disposition, "proceed");
    assert.equal(r.decided_by, "judge_needed");
  });
});

describe("scoreDisposition diff-mode signal (#1414)", () => {
  const cfg = { enabled: true, mode: "authoritative", max_auto_overrides: 1, judge: { enabled: false, model: null } };
  const base = {
    reviewer: "test-quality",
    prior_auto_overrides: 0,
    diff: { files_changed: 8, lines_added: 300, lines_deleted: 40 },
    surfaces: [],
    findings: { one_off_count: 1, class_count: 0, has_security_finding: false, known: true },
  };

  it("scores a sliced (manifest) diff as riskier than a fully inlined one", () => {
    const inline = scoreDisposition({ ...base, diff_mode: "inline" }, cfg);
    const manifest = scoreDisposition({ ...base, diff_mode: "manifest" }, cfg);
    assert.ok(
      manifest.risk_score > inline.risk_score,
      `manifest ${manifest.risk_score} should exceed inline ${inline.risk_score}`,
    );
  });

  it("treats unknown coverage the same as manifest, never as fully-covered inline", () => {
    const inline = scoreDisposition({ ...base, diff_mode: "inline" }, cfg);
    const unknown = scoreDisposition({ ...base, diff_mode: "unknown" }, cfg);
    assert.ok(unknown.risk_score > inline.risk_score);
  });

  it("leaves the score unchanged for callers that supply no diff mode", () => {
    const absent = scoreDisposition(base, cfg);
    const inline = scoreDisposition({ ...base, diff_mode: "inline" }, cfg);
    assert.equal(absent.risk_score, inline.risk_score);
  });

  it("keeps the risk score clamped at 1", () => {
    const saturated = scoreDisposition(
      {
        ...base,
        diff_mode: "manifest",
        surfaces: ["mcp_tool"],
        diff: { files_changed: 50, lines_added: 5000, lines_deleted: 5000 },
        findings: { one_off_count: 9, class_count: 9, has_security_finding: true, known: true },
      },
      cfg,
    );
    assert.equal(saturated.risk_score, 1);
  });
});

describe("collectDispositionSignals", () => {
  const REPO = "/fake/repo";

  it("carries a server-derived diff mode, defaulting to unknown rather than inline (#1414)", () => {
    const derived = collectDispositionSignals({
      reviewer: "codex",
      findingsSummary: {},
      diffManifest: "1\t0\tsrc/a.js",
      changedPaths: [],
      priorAutoOverrides: 0,
      repoRoot: REPO,
      diffMode: "manifest",
    });
    assert.equal(derived.diff_mode, "manifest");

    // A caller that supplies no mode must not be scored as a fully covered
    // inline review — that would silently launder unknown coverage.
    const absent = collectDispositionSignals({
      reviewer: "codex",
      findingsSummary: {},
      diffManifest: "1\t0\tsrc/a.js",
      changedPaths: [],
      priorAutoOverrides: 0,
      repoRoot: REPO,
    });
    assert.equal(absent.diff_mode, "unknown");
  });

  it("parses numstat including binary '-' rows", () => {
    const manifest = [
      "# staged",
      "10\t4\tsrc/a.js",
      "-\t-\tassets/logo.png",
      "",
      "# unstaged",
      "3\t1\tsrc/b.js",
    ].join("\n");
    const s = collectDispositionSignals({
      reviewer: "codex",
      findingsSummary: { one_off_count: 0, class_count: 0, top_categories: [] },
      diffManifest: manifest,
      changedPaths: [],
      priorAutoOverrides: 0,
      repoRoot: REPO,
    });
    assert.equal(s.diff.lines_added, 13);
    assert.equal(s.diff.lines_deleted, 5);
    assert.equal(s.diff.files_changed, 3);
  });

  it("classifies mcp paths as a high-risk surface", () => {
    const s = collectDispositionSignals({
      reviewer: "codex",
      findingsSummary: {},
      diffManifest: "1\t0\tmcp/ground-control/lib.js",
      changedPaths: ["mcp/ground-control/lib.js", "mcp/ground-control/index.js"],
      priorAutoOverrides: 0,
      repoRoot: REPO,
    });
    assert.ok(s.surfaces.includes("config_parser"), JSON.stringify(s.surfaces));
    assert.ok(s.surfaces.includes("mcp_tool"), JSON.stringify(s.surfaces));
  });

  it("derives has_security_finding from a security-shaped category", () => {
    const s = collectDispositionSignals({
      reviewer: "codex",
      findingsSummary: { one_off_count: 0, class_count: 1, top_categories: [{ shape: "missing authz check" }] },
      diffManifest: "",
      changedPaths: [],
      priorAutoOverrides: 0,
      repoRoot: REPO,
    });
    assert.equal(s.findings.has_security_finding, true);
  });

  it("flags findings as unknown when no summary is supplied, known when one is", () => {
    const missing = collectDispositionSignals({
      reviewer: "codex",
      findingsSummary: null,
      diffManifest: "1\t0\tsrc/a.js",
      changedPaths: [],
      priorAutoOverrides: 0,
      repoRoot: REPO,
    });
    assert.equal(missing.findings.known, false);
    const present = collectDispositionSignals({
      reviewer: "codex",
      findingsSummary: { one_off_count: 0, class_count: 0, top_categories: [] },
      diffManifest: "1\t0\tsrc/a.js",
      changedPaths: [],
      priorAutoOverrides: 0,
      repoRoot: REPO,
    });
    assert.equal(present.findings.known, true);
  });
});

describe("parseReviewAutoDispositionMarkers", () => {
  function record(opts) {
    return buildReviewAutoDispositionRecord({
      issueNumber: opts.issue,
      reviewer: opts.reviewer,
      cycle: opts.cycle ?? 1,
      cap: opts.cap ?? 1,
      disposition: opts.disposition,
      rationale: opts.rationale ?? "ok",
      signalsSnapshot: opts.snapshot ?? { diff: {} },
      grantNumber: opts.grant ?? null,
    });
  }

  it("counts one_more_cycle grants for the matching issue + reviewer", () => {
    const bodies = [
      record({ issue: 42, reviewer: "codex", disposition: "one_more_cycle", grant: 1 }),
      record({ issue: 42, reviewer: "codex", disposition: "proceed" }),
    ];
    const r = parseReviewAutoDispositionMarkers(bodies, 42, "codex");
    assert.equal(r.auto_override_grants, 1);
    assert.equal(r.markers.length, 2);
  });

  it("ignores other issues and other reviewers", () => {
    const bodies = [
      record({ issue: 42, reviewer: "codex", disposition: "one_more_cycle", grant: 1 }),
      record({ issue: 99, reviewer: "codex", disposition: "one_more_cycle", grant: 1 }),
      record({ issue: 42, reviewer: "test-quality", disposition: "one_more_cycle", grant: 1 }),
    ];
    const r = parseReviewAutoDispositionMarkers(bodies, 42, "codex");
    assert.equal(r.auto_override_grants, 1);
    assert.equal(r.markers.length, 1);
  });

  it("tolerates a malformed data block (uses attrs, no throw)", () => {
    const broken =
      '<!-- gc:review-auto-disposition issue="42" reviewer="codex" schema="gc.implement.review-auto-disposition/v1" disposition="one_more_cycle" grant="true" -->\n' +
      "\nbody\n\n<!-- gc:review-auto-disposition-data {not valid json -->";
    const r = parseReviewAutoDispositionMarkers([broken], 42, "codex");
    assert.equal(r.auto_override_grants, 1);
    assert.equal(r.markers[0].disposition, "one_more_cycle");
  });
});
