// Tests for gc_workflow_run_ingest: bridge ingestion handler and pure helpers.
// All I/O is stubbed; no real GitHub or backend calls are made.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  gcWorkflowRunIngestZodShape,
  gcWorkflowRunIngestHandler,
  countMalformedMarkers,
  extractRequirementUids,
  deriveWorkflowType,
  deriveFinalState,
  deriveOutcome,
  parseFinalReportMarker,
  GC_WORKFLOW_RUN_INGEST_DESCRIPTION,
} from "./gc-workflow-run-ingest.js";

// ── Zod shape ────────────────────────────────────────────────────────────────

describe("gcWorkflowRunIngestZodShape", () => {
  const schema = z.object(gcWorkflowRunIngestZodShape);

  it("accepts a minimal valid payload", () => {
    const r = schema.safeParse({
      repo_path: "/home/user/repo",
      issue_number: 42,
    });
    assert.equal(r.success, true);
  });

  it("rejects a missing repo_path", () => {
    const r = schema.safeParse({ issue_number: 42 });
    assert.equal(r.success, false);
  });

  it("rejects a non-positive issue_number", () => {
    const r = schema.safeParse({ repo_path: "/repo", issue_number: 0 });
    assert.equal(r.success, false);
  });

  it("accepts an optional workflow_type override", () => {
    const r = schema.safeParse({
      repo_path: "/repo",
      issue_number: 1,
      workflow_type: "QUICKFIX",
    });
    assert.equal(r.success, true);
  });

  it("rejects an unknown workflow_type", () => {
    const r = schema.safeParse({
      repo_path: "/repo",
      issue_number: 1,
      workflow_type: "BOGUS",
    });
    assert.equal(r.success, false);
  });

  it("accepts dry_run=true", () => {
    const r = schema.safeParse({
      repo_path: "/repo",
      issue_number: 1,
      dry_run: true,
    });
    assert.equal(r.success, true);
  });
});

// ── countMalformedMarkers ────────────────────────────────────────────────────

describe("countMalformedMarkers", () => {
  it("returns 0 for well-formed gc:phase markers", () => {
    const bodies = [
      '<!-- gc:phase phase="preflight" issue="1" -->',
      '<!-- gc:phase phase="plan" issue="1" -->',
    ];
    assert.equal(countMalformedMarkers(bodies), 0);
  });

  it("returns 0 for well-formed gc:final-report markers", () => {
    assert.equal(
      countMalformedMarkers(['<!-- gc:final-report issue="1" pr="2" -->']),
      0,
    );
  });

  it("counts an HTML comment with gc:phase but a missing attribute as malformed", () => {
    // Missing the 'issue' attribute — won't match the canonical pattern
    const bodies = ['<!-- gc:phase phase="preflight" -->'];
    assert.equal(countMalformedMarkers(bodies), 1);
  });

  it("does NOT count plain-text (non-HTML-comment) gc: mentions as malformed", () => {
    // Plain text is not an HTML comment so the HTML-comment regex won't match.
    const bodies = ["gc:phase phase=preflight is mentioned here in prose"];
    assert.equal(countMalformedMarkers(bodies), 0);
  });

  it("returns 0 for non-string entries and empty input", () => {
    assert.equal(countMalformedMarkers([null, 42, undefined]), 0);
    assert.equal(countMalformedMarkers([]), 0);
    assert.equal(countMalformedMarkers(null), 0);
  });

  it("does NOT count gc:grc-screening-data (a different family) as a malformed gc:grc-screening", () => {
    const bodies = ['<!-- gc:grc-screening-data {"verdict":"none"} -->'];
    // The -data variant is a different marker family; countMalformedMarkers
    // uses a negative lookahead for -data, so this should NOT be flagged.
    assert.equal(countMalformedMarkers(bodies), 0);
  });

  it("counts a mix of valid and malformed markers correctly", () => {
    const bodies = [
      '<!-- gc:phase phase="preflight" issue="1" -->', // valid
      '<!-- gc:phase missing-attrs -->', // malformed (missing required attrs)
    ];
    assert.equal(countMalformedMarkers(bodies), 1);
  });
});

// ── extractRequirementUids ───────────────────────────────────────────────────

describe("extractRequirementUids", () => {
  it("extracts UIDs from the Requirements section", () => {
    const body = `
## Description
Some feature.

## Requirements

- GC-O007: Observe something
- GC-S001: Another requirement

## Notes
Nothing here.
`;
    const uids = extractRequirementUids(body);
    assert.deepEqual(uids, ["GC-O007", "GC-S001"]);
  });

  it("extracts allocator-minted short UIDs (issue #1425)", () => {
    // `${prefix}-${n}` is unpadded, so a project's first nine requirements have
    // a single-digit suffix; dropping them here would silently downgrade a
    // requirement-backed run to requirement-free.
    const body = "## Requirements\n\n- APP-2: short uid\n- A-1\n- PLAT-10\n";
    assert.deepEqual(extractRequirementUids(body), ["APP-2", "A-1", "PLAT-10"]);
  });

  it("recognizes a UID adjacent to sentence punctuation", () => {
    // `.` is a legal identity-corpus character, so a tokenizer that splits only
    // on non-corpus characters leaves `GC-O007.` glued together and drops it.
    // Sentence-final UIDs ("Fixes GC-O007.") are the common case in real issue
    // prose, and dropping them is the silent requirement-free downgrade this
    // bridge must never produce.
    assert.deepEqual(extractRequirementUids("Fixes GC-O007."), ["GC-O007"]);
    assert.deepEqual(
      extractRequirementUids("This closes GC-O007, and also fixes GC-S001."),
      ["GC-O007", "GC-S001"],
    );
    assert.deepEqual(extractRequirementUids("Closes APP-2; see (GC-O007)."), ["APP-2", "GC-O007"]);
  });

  it("does not mistake ordinary prose for a UID", () => {
    // Prose words are valid bounded identifiers, so recognition keeps a
    // narrower shape than the structured identity corpus.
    const body = "## Requirements\n\n- prose and notes are not uids\n";
    assert.deepEqual(extractRequirementUids(body), []);
  });

  it("falls back to full body when no Requirements section exists", () => {
    const body = "Implement GC-O007 and also GC-S001 while fixing this.";
    const uids = extractRequirementUids(body);
    assert.ok(uids.includes("GC-O007"));
    assert.ok(uids.includes("GC-S001"));
  });

  it("deduplicates UIDs", () => {
    const body = "## Requirements\n- GC-O007\n- GC-O007\n- GC-S001";
    const uids = extractRequirementUids(body);
    assert.equal(uids.filter((u) => u === "GC-O007").length, 1);
  });

  it("rejects token-shaped strings that are not UIDs (e.g. 'GC-OOPS')", () => {
    const body = "## Requirements\n- GC-OOPS is not a valid UID";
    const uids = extractRequirementUids(body);
    assert.ok(!uids.includes("GC-OOPS"));
  });

  it("returns empty array for empty or non-string input", () => {
    assert.deepEqual(extractRequirementUids(""), []);
    assert.deepEqual(extractRequirementUids(null), []);
    assert.deepEqual(extractRequirementUids(undefined), []);
  });

  it("preserves first-seen order without duplicates", () => {
    const body = "GC-O007 then GC-S001 then GC-O007 again";
    const uids = extractRequirementUids(body);
    assert.deepEqual(uids, ["GC-O007", "GC-S001"]);
  });
});

// ── deriveWorkflowType ───────────────────────────────────────────────────────

describe("deriveWorkflowType", () => {
  it("returns QUICKFIX when quickfix label is present", () => {
    assert.equal(deriveWorkflowType(["quickfix"], ""), "QUICKFIX");
  });

  it("returns GRC_REVIEW when grc label is present", () => {
    assert.equal(deriveWorkflowType(["grc"], "GRC screening"), "GRC_REVIEW");
  });

  it("returns IMPLEMENT when implement label is present", () => {
    assert.equal(deriveWorkflowType(["implement"], ""), "IMPLEMENT");
  });

  it("returns IMPLEMENT as default when no signal", () => {
    assert.equal(deriveWorkflowType([], "Fix the bug"), "IMPLEMENT");
  });

  it("returns QUICKFIX from title heuristic", () => {
    assert.equal(deriveWorkflowType([], "quickfix: spelling error"), "QUICKFIX");
  });

  it("returns GRC_REVIEW from title heuristic", () => {
    assert.equal(deriveWorkflowType([], "GRC review pass for batch 3"), "GRC_REVIEW");
  });

  it("is case-insensitive for labels", () => {
    assert.equal(deriveWorkflowType(["QUICKFIX"], ""), "QUICKFIX");
  });

  it("handles null/undefined labels gracefully", () => {
    assert.equal(deriveWorkflowType(null, "fix"), "IMPLEMENT");
    assert.equal(deriveWorkflowType(undefined, "fix"), "IMPLEMENT");
  });
});

// ── deriveFinalState ─────────────────────────────────────────────────────────

describe("deriveFinalState", () => {
  it("returns MERGED when post_merge phase is present", () => {
    assert.equal(deriveFinalState(new Set(["plan", "post_merge"])), "MERGED");
  });

  it("returns READY_FOR_REVIEW when ready_for_review phase is present", () => {
    assert.equal(
      deriveFinalState(new Set(["plan", "ready_for_review"])),
      "READY_FOR_REVIEW",
    );
  });

  it("returns READY_FOR_REVIEW when traceability_reconciled is present", () => {
    assert.equal(
      deriveFinalState(new Set(["traceability_reconciled"])),
      "READY_FOR_REVIEW",
    );
  });

  it("returns READY_FOR_REVIEW when grc_reconciled is present", () => {
    assert.equal(deriveFinalState(new Set(["grc_reconciled"])), "READY_FOR_REVIEW");
  });

  it("returns RUNNING when only early phases are present", () => {
    assert.equal(deriveFinalState(new Set(["preflight", "plan"])), "RUNNING");
  });

  it("returns RUNNING for an empty set", () => {
    assert.equal(deriveFinalState(new Set()), "RUNNING");
  });

  it("returns RUNNING for a non-Set input", () => {
    assert.equal(deriveFinalState(null), "RUNNING");
    assert.equal(deriveFinalState(["plan"]), "RUNNING");
  });
});

// ── deriveOutcome ────────────────────────────────────────────────────────────

describe("deriveOutcome", () => {
  it("maps MERGED to MERGED", () => {
    assert.equal(deriveOutcome("MERGED"), "MERGED");
  });

  it("maps RUNNING to NONE", () => {
    assert.equal(deriveOutcome("RUNNING"), "NONE");
  });

  it("maps READY_FOR_REVIEW to NONE", () => {
    assert.equal(deriveOutcome("READY_FOR_REVIEW"), "NONE");
  });

  it("maps unknown states to NONE", () => {
    assert.equal(deriveOutcome("ABANDONED"), "NONE");
    assert.equal(deriveOutcome(null), "NONE");
  });
});

// ── parseFinalReportMarker ───────────────────────────────────────────────────

describe("parseFinalReportMarker", () => {
  it("finds a canonical final-report marker and extracts prNumber", () => {
    const bodies = [
      "some comment",
      '<!-- gc:final-report issue="42" pr="100" -->',
    ];
    const result = parseFinalReportMarker(bodies, 42);
    assert.equal(result.found, true);
    assert.equal(result.prNumber, 100);
  });

  it("does not match markers for a different issue", () => {
    const bodies = ['<!-- gc:final-report issue="99" pr="200" -->'];
    const result = parseFinalReportMarker(bodies, 42);
    assert.equal(result.found, false);
  });

  it("returns found=false when no marker exists", () => {
    const result = parseFinalReportMarker(["plain text"], 42);
    assert.equal(result.found, false);
  });

  it("returns found=false for non-array input", () => {
    assert.equal(parseFinalReportMarker(null, 1).found, false);
    assert.equal(parseFinalReportMarker("string", 1).found, false);
  });

  it("ignores plain-text that looks like a marker", () => {
    // Not an HTML comment — must not match
    const bodies = ["gc:final-report issue=42 pr=100 is just prose"];
    const result = parseFinalReportMarker(bodies, 42);
    assert.equal(result.found, false);
  });
});

// ── gcWorkflowRunIngestHandler (dry_run=true) ────────────────────────────────

describe("gcWorkflowRunIngestHandler — dry_run=true", () => {
  function makeThreadStub({ phases = [], finalReportPr = null, labels = [], title = "", body = "" } = {}) {
    const commentBodies = [];
    for (const phase of phases) {
      commentBodies.push(`<!-- gc:phase phase="${phase}" issue="42" -->`);
    }
    if (finalReportPr) {
      commentBodies.push(`<!-- gc:final-report issue="42" pr="${finalReportPr}" -->`);
    }
    return {
      ok: true,
      issue_number: 42,
      body,
      title,
      labels,
      state: "closed",
      url: "https://github.com/org/repo/issues/42",
      comments: commentBodies.map((b, i) => ({ id: i, author: "bot", created_at: "2026-01-01T00:00:00Z", body: b })),
    };
  }

  it("returns derived facts without posting when dry_run=true", async () => {
    const thread = makeThreadStub({
      phases: ["preflight", "plan"],
      labels: ["implement"],
      body: "## Requirements\n- GC-O007\n",
    });
    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 42, dry_run: true },
      {
        threadFetch: async () => thread,
        runCreate: async () => { throw new Error("should not be called"); },
        eventCreate: async () => { throw new Error("should not be called"); },
      },
    );
    assert.equal(result.ok, true);
    assert.equal(result.dry_run, true);
    assert.deepEqual([...result.phases_recorded].sort(), ["plan", "preflight"]);
    assert.ok(result.requirement_uids.includes("GC-O007"));
    assert.equal(result.final_state, "RUNNING");
    assert.equal(result.outcome, "NONE");
    assert.equal(result.workflow_type, "IMPLEMENT");
    assert.equal(result.has_final_report, false);
  });

  it("detects MERGED when post_merge phase is present", async () => {
    const thread = makeThreadStub({
      phases: ["preflight", "plan", "post_merge"],
      finalReportPr: 55,
    });
    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 42, dry_run: true },
      { threadFetch: async () => thread },
    );
    assert.equal(result.final_state, "MERGED");
    assert.equal(result.outcome, "MERGED");
    assert.equal(result.has_final_report, true);
  });

  it("respects workflow_type override", async () => {
    const thread = makeThreadStub({ labels: ["implement"] });
    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 42, workflow_type: "QUICKFIX", dry_run: true },
      { threadFetch: async () => thread },
    );
    assert.equal(result.workflow_type, "QUICKFIX");
  });

  it("returns ok=false when thread fetch fails", async () => {
    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 42 },
      {
        threadFetch: async () => ({ ok: false, error: "thread_fetch_failed", message: "gh not found" }),
      },
    );
    assert.equal(result.ok, false);
    assert.equal(result.error, "thread_fetch_failed");
  });
});

// ── gcWorkflowRunIngestHandler — live POST (no dry_run) ─────────────────────

describe("gcWorkflowRunIngestHandler — full ingest", () => {
  function makeThread(phases = ["preflight", "plan"]) {
    const comments = phases.map((p, i) => ({
      id: i,
      author: "mcp-bot",
      created_at: "2026-01-01T00:00:00Z",
      body: `<!-- gc:phase phase="${p}" issue="7" -->`,
    }));
    return {
      ok: true,
      issue_number: 7,
      body: "## Requirements\n- GC-O001\n",
      title: "Implement feature",
      labels: ["implement"],
      state: "closed",
      url: "https://github.com/org/repo/issues/7",
      comments,
    };
  }

  it("creates a run and posts phase events", async () => {
    const createdRuns = [];
    const postedEvents = [];
    const thread = makeThread(["preflight", "plan"]);

    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 7, project: "my-proj" },
      {
        threadFetch: async ({ issueNumber }) => {
          assert.equal(issueNumber, 7);
          return thread;
        },
        runCreate: async (body, project) => {
          createdRuns.push({ body, project });
          return { id: "new-run-uuid" };
        },
        eventCreate: async (runId, evt, project) => {
          postedEvents.push({ runId, evt, project });
          return { id: "evt-uuid" };
        },
      },
    );

    assert.equal(result.ok, true);
    assert.equal(result.run_id, "new-run-uuid");
    assert.equal(createdRuns.length, 1);
    assert.equal(createdRuns[0].project, "my-proj");
    assert.equal(createdRuns[0].body.provenance, "ISSUE_THREAD");
    assert.equal(createdRuns[0].body.issue_number, 7);
    assert.equal(postedEvents.length, 2);
    for (const { runId, evt, project } of postedEvents) {
      assert.equal(runId, "new-run-uuid");
      assert.equal(evt.event_type, "COMPLETED");
      assert.equal(evt.provenance, "ISSUE_THREAD");
      // Phase events must carry the run's project so the backend project-scoped lookup accepts
      // them; without it a multi-project backend rejects every event (issue #859 security review).
      assert.equal(project, "my-proj");
    }
    assert.deepEqual(result.phases_recorded.sort(), ["plan", "preflight"]);
    assert.equal(result.events_posted, 2);
    assert.equal(result.events_failed, 0);
    assert.equal(result.skipped_malformed_markers, 0);
  });

  it("returns ok=false when backend create fails", async () => {
    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 7 },
      {
        threadFetch: async () => makeThread([]),
        runCreate: async () => { throw Object.assign(new Error("backend error"), { code: "create_conflict" }); },
      },
    );
    assert.equal(result.ok, false);
    assert.equal(result.error, "create_conflict");
  });

  it("fails open on event-post errors and reports partial events_posted", async () => {
    let callCount = 0;
    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 7 },
      {
        threadFetch: async () => makeThread(["preflight", "plan"]),
        runCreate: async () => ({ id: "run-99" }),
        eventCreate: async () => {
          callCount += 1;
          if (callCount === 1) throw new Error("transient error");
          return { id: "ok" };
        },
      },
    );
    assert.equal(result.ok, true);
    // Second event should succeed even though first failed
    assert.equal(result.events_posted, 1);
  });

  it("returns ok=false when backend returns no run id", async () => {
    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 7 },
      {
        threadFetch: async () => makeThread([]),
        runCreate: async () => ({}), // no id field
      },
    );
    assert.equal(result.ok, false);
    assert.equal(result.error, "create_run_no_id");
  });
});

// ── Marker security: forged / malformed markers are counted, never forwarded ─

describe("marker security — forged/malformed handling", () => {
  it("skipped_malformed_markers counts HTML-comment markers with wrong attribute structure", async () => {
    const bodies = [
      // valid
      '<!-- gc:phase phase="preflight" issue="5" -->',
      // malformed: wrong attribute order / missing issue
      '<!-- gc:phase missing-issue-attr -->',
    ];
    const comments = bodies.map((body, i) => ({
      id: i, author: "x", created_at: "2026-01-01T00:00:00Z", body,
    }));
    const thread = {
      ok: true,
      issue_number: 5,
      body: "",
      title: "",
      labels: [],
      state: "open",
      url: "",
      comments,
    };

    const result = await gcWorkflowRunIngestHandler(
      { repo_path: "/repo", issue_number: 5, dry_run: true },
      { threadFetch: async () => thread },
    );
    assert.equal(result.ok, true);
    assert.equal(result.skipped_malformed_markers, 1);
    // The valid phase is still counted
    assert.ok(result.phases_recorded.includes("preflight"));
  });
});

// ── description constant ─────────────────────────────────────────────────────

describe("GC_WORKFLOW_RUN_INGEST_DESCRIPTION", () => {
  it("is a non-empty string", () => {
    assert.equal(typeof GC_WORKFLOW_RUN_INGEST_DESCRIPTION, "string");
    assert.ok(GC_WORKFLOW_RUN_INGEST_DESCRIPTION.length > 10);
  });
});
