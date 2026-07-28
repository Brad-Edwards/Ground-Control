// Split from gc-workflow-run-ingest.test.js under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Test bodies are unchanged.

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { GC_WORKFLOW_RUN_INGEST_DESCRIPTION, gcWorkflowRunIngestHandler } from "./gc-workflow-run-ingest.js";

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
