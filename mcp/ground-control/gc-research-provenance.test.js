// Adapter-level tests for the gc_research_provenance MCP handler (GC-RSCH-R004 /
// GC-RSCH-N002 / GC-RSCH-N004, ADR-069). Exercise Zod-parsed args → handler
// dispatch → backend HTTP call (mocked fetch) → wire body shape. Lock in the
// snake_case → camelCase remapping at the adapter boundary, the run-scoped
// routing, and the required-field contract per action.

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_RESEARCH_PROVENANCE_ACTIONS,
  gcResearchProvenanceZodShape,
  gcResearchProvenanceToolHandler,
} from "./gc-research-provenance.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;
const ORIGINAL_API_TOKEN = process.env.GROUND_CONTROL_API_TOKEN;
const RUN_ID = "00000000-0000-0000-0000-000000000010";
const NODE_ID = "00000000-0000-0000-0000-000000000020";

function makeFetchSpy({ status = 201, body = { id: "node-uuid" } } = {}) {
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

beforeEach(() => {
  process.env.GC_BASE_URL = "https://gc.test";
  delete process.env.GROUND_CONTROL_API_TOKEN;
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
  else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
  if (ORIGINAL_API_TOKEN === undefined) delete process.env.GROUND_CONTROL_API_TOKEN;
  else process.env.GROUND_CONTROL_API_TOKEN = ORIGINAL_API_TOKEN;
});

describe("gcResearchProvenanceZodShape", () => {
  it("rejects an unknown action", () => {
    const schema = z.object(gcResearchProvenanceZodShape);
    assert.equal(schema.safeParse({ action: "delete", run_id: RUN_ID }).success, false);
  });

  it("exposes exactly the five provenance actions", () => {
    assert.deepEqual(
      [...GC_RESEARCH_PROVENANCE_ACTIONS].sort(),
      ["chain", "list_edges", "list_nodes", "record_edge", "record_node"],
    );
  });
});

describe("gcResearchProvenanceToolHandler", () => {
  it("record_node POSTs a camelCase body to the run-scoped nodes route", async () => {
    const calls = makeFetchSpy();
    await gcResearchProvenanceToolHandler({
      action: "record_node",
      run_id: RUN_ID,
      kind: "SYNTHESIS_CLAIM",
      subject_key: "claim-7",
      external_identifier: "10.1000/xyz",
      source_action_id: "act-1",
      summary: "bounded",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/research-runs\/.*\/provenance\/nodes/);
    assert.equal(calls[0].body.subjectKey, "claim-7");
    assert.equal(calls[0].body.externalIdentifier, "10.1000/xyz");
    assert.equal(calls[0].body.sourceActionId, "act-1");
    assert.equal(calls[0].body.kind, "SYNTHESIS_CLAIM");
  });

  it("record_node requires kind and subject_key", async () => {
    makeFetchSpy();
    await assert.rejects(
      () => gcResearchProvenanceToolHandler({ action: "record_node", run_id: RUN_ID, kind: "QUERY" }),
      /subject_key/,
    );
  });

  it("record_edge POSTs a camelCase body to the run-scoped edges route", async () => {
    const calls = makeFetchSpy();
    await gcResearchProvenanceToolHandler({
      action: "record_edge",
      run_id: RUN_ID,
      from_node_id: NODE_ID,
      to_node_id: "00000000-0000-0000-0000-000000000021",
      relation: "SUPPORTS",
    });
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/provenance\/edges/);
    assert.equal(calls[0].body.fromNodeId, NODE_ID);
    assert.equal(calls[0].body.relation, "SUPPORTS");
  });

  it("chain GETs the backward-traversal route for the node", async () => {
    const calls = makeFetchSpy({ status: 200, body: { rootNodeId: NODE_ID, nodes: [], edges: [] } });
    await gcResearchProvenanceToolHandler({ action: "chain", run_id: RUN_ID, node_id: NODE_ID, depth: 3 });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/provenance\/nodes\/.*\/chain/);
    assert.match(calls[0].url, /depth=3/);
  });

  it("list_nodes GETs the run-scoped nodes route", async () => {
    const calls = makeFetchSpy({ status: 200, body: [] });
    await gcResearchProvenanceToolHandler({ action: "list_nodes", run_id: RUN_ID });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/provenance\/nodes/);
  });

  it("list_edges GETs the run-scoped edges route", async () => {
    const calls = makeFetchSpy({ status: 200, body: [] });
    await gcResearchProvenanceToolHandler({ action: "list_edges", run_id: RUN_ID });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/provenance\/edges/);
  });
});
