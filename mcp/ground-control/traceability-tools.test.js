// Tests for the MCP traceability tool surface (GC-L003).
//
// The lib.js wrappers below back the following tools registered in index.js:
//   - gc_get_traceability                 → getTraceabilityLinks
//   - gc_get_traceability_by_artifact     → getTraceabilityByArtifact
//   - gc_create_traceability_link         → createTraceabilityLink
//   - gc_delete_traceability_link         → deleteTraceabilityLink
//   - gc_relation                         → getRelations, createRelation, deleteRelation
//   - gc_graph                            → getAncestors, getDescendants, findPaths,
//                                           getGraphVisualization, extractSubgraph,
//                                           traverseGraph, findGraphPaths
//
// Each test stubs globalThis.fetch and asserts the outbound HTTP shape
// (method + path + query params + body) plus pass-through of the parsed
// response and the RequestError envelope on failure. Regressing any of these
// fails the parity contract these tools rely on.

import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  RequestError,
  createRelation,
  createTraceabilityLink,
  deleteRelation,
  deleteTraceabilityLink,
  extractSubgraph,
  findGraphPaths,
  findPaths,
  getAncestors,
  getDescendants,
  getGraphVisualization,
  getRelations,
  getTraceabilityByArtifact,
  getTraceabilityLinks,
  traverseGraph,
} from "./lib.js";

const BASE_URL = "http://gc-test:8000";
const REQ_ID = "11111111-1111-1111-1111-111111111111";
const LINK_ID = "22222222-2222-2222-2222-222222222222";
const REL_ID = "33333333-3333-3333-3333-333333333333";
const TARGET_ID = "44444444-4444-4444-4444-444444444444";

let fetchCalls;
let originalFetch;
let originalBaseUrl;

function setNextResponse({ ok = true, status = 200, body = null } = {}) {
  globalThis.fetch = async (url, opts) => {
    fetchCalls.push({ url: typeof url === "string" ? url : url.toString(), opts });
    return {
      ok,
      status,
      text: async () => (body === null ? "" : typeof body === "string" ? body : JSON.stringify(body)),
    };
  };
}

beforeEach(() => {
  fetchCalls = [];
  originalFetch = globalThis.fetch;
  originalBaseUrl = process.env.GC_BASE_URL;
  process.env.GC_BASE_URL = BASE_URL;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  if (originalBaseUrl === undefined) {
    delete process.env.GC_BASE_URL;
  } else {
    process.env.GC_BASE_URL = originalBaseUrl;
  }
});

function parseUrl(call) {
  return new URL(call.url);
}

describe("getTraceabilityLinks (gc_get_traceability)", () => {
  it("GETs /api/v1/requirements/{id}/traceability and passes the response through", async () => {
    setNextResponse({
      body: [
        { id: "L1", requirementId: REQ_ID, linkType: "IMPLEMENTS", artifactType: "CODE_FILE" },
      ],
    });
    const result = await getTraceabilityLinks(REQ_ID);

    assert.equal(fetchCalls.length, 1);
    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "GET");
    assert.equal(parsed.pathname, `/api/v1/requirements/${REQ_ID}/traceability`);
    assert.equal(opts.body, undefined);

    // toSnakeCase rewrites the response payload.
    assert.deepEqual(result, [
      { id: "L1", requirement_id: REQ_ID, link_type: "IMPLEMENTS", artifact_type: "CODE_FILE" },
    ]);
  });

  it("URI-encodes the requirement id segment", async () => {
    setNextResponse({ body: [] });
    await getTraceabilityLinks("with space/and?punct");
    assert.equal(
      parseUrl(fetchCalls[0]).pathname,
      "/api/v1/requirements/with%20space%2Fand%3Fpunct/traceability",
    );
  });
});

describe("getTraceabilityByArtifact (gc_get_traceability_by_artifact)", () => {
  it("GETs the reverse-lookup endpoint with artifactType + artifactIdentifier query params", async () => {
    setNextResponse({ body: [] });
    await getTraceabilityByArtifact("CODE_FILE", "backend/src/Main.java");

    assert.equal(fetchCalls.length, 1);
    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "GET");
    assert.equal(parsed.pathname, "/api/v1/requirements/traceability/by-artifact");
    assert.equal(parsed.searchParams.get("artifactType"), "CODE_FILE");
    assert.equal(parsed.searchParams.get("artifactIdentifier"), "backend/src/Main.java");
  });

  it("propagates a RequestError envelope on non-2xx", async () => {
    setNextResponse({
      ok: false,
      status: 404,
      body: { error: { code: "not_found", message: "no link", detail: { artifact: "x" } } },
    });
    await assert.rejects(
      () => getTraceabilityByArtifact("CODE_FILE", "x"),
      (e) => e instanceof RequestError
        && e.status === 404
        && e.code === "not_found"
        && e.message.includes("no link")
        && e.detail?.artifact === "x",
    );
  });

  it("forwards project query param when provided", async () => {
    setNextResponse({ body: [] });
    await getTraceabilityByArtifact("GITHUB_ISSUE", "42", "my-project");

    const parsed = parseUrl(fetchCalls[0]);
    assert.equal(parsed.searchParams.get("artifactType"), "GITHUB_ISSUE");
    assert.equal(parsed.searchParams.get("artifactIdentifier"), "42");
    assert.equal(parsed.searchParams.get("project"), "my-project");
  });

  it("omits project param when not provided", async () => {
    setNextResponse({ body: [] });
    await getTraceabilityByArtifact("GITHUB_ISSUE", "42");

    const parsed = parseUrl(fetchCalls[0]);
    // undefined project should not appear as "undefined" in query string
    assert.equal(parsed.searchParams.has("project"), false);
  });
});

describe("createTraceabilityLink (gc_create_traceability_link)", () => {
  it("POSTs the link body to /api/v1/requirements/{id}/traceability in camelCase", async () => {
    setNextResponse({
      body: {
        id: "L9",
        requirementId: REQ_ID,
        artifactType: "TEST",
        artifactIdentifier: "path/to/Test.java",
        artifactTitle: "Test",
        linkType: "TESTS",
      },
    });

    const result = await createTraceabilityLink(REQ_ID, {
      artifact_type: "TEST",
      artifact_identifier: "path/to/Test.java",
      artifact_title: "Test",
      link_type: "TESTS",
    });

    assert.equal(fetchCalls.length, 1);
    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "POST");
    assert.equal(parsed.pathname, `/api/v1/requirements/${REQ_ID}/traceability`);
    assert.equal(opts.headers["Content-Type"], "application/json");
    assert.equal(opts.headers["X-Actor"], "mcp-server");

    // Body must be JSON and converted to camelCase before being sent.
    assert.deepEqual(JSON.parse(opts.body), {
      artifactType: "TEST",
      artifactIdentifier: "path/to/Test.java",
      artifactTitle: "Test",
      linkType: "TESTS",
    });

    // Response is converted back to snake_case.
    assert.deepEqual(result, {
      id: "L9",
      requirement_id: REQ_ID,
      artifact_type: "TEST",
      artifact_identifier: "path/to/Test.java",
      artifact_title: "Test",
      link_type: "TESTS",
    });
  });
});

describe("deleteTraceabilityLink (gc_delete_traceability_link)", () => {
  it("DELETEs /api/v1/requirements/{reqId}/traceability/{linkId} and returns null on 204", async () => {
    setNextResponse({ ok: true, status: 204, body: null });
    const result = await deleteTraceabilityLink(REQ_ID, LINK_ID);

    assert.equal(result, undefined); // lib swallows the null return
    assert.equal(fetchCalls.length, 1);
    const { url, opts } = fetchCalls[0];
    const parsed = new URL(url);
    assert.equal(opts.method, "DELETE");
    assert.equal(parsed.pathname, `/api/v1/requirements/${REQ_ID}/traceability/${LINK_ID}`);
    assert.equal(opts.body, undefined);
  });

  it("propagates 404 when the link does not belong to the requirement", async () => {
    setNextResponse({
      ok: false,
      status: 404,
      body: { error: { code: "not_found", message: "link not under requirement" } },
    });
    await assert.rejects(
      () => deleteTraceabilityLink(REQ_ID, LINK_ID),
      (e) => e instanceof RequestError && e.status === 404,
    );
  });
});

describe("getRelations / createRelation / deleteRelation (gc_relation)", () => {
  it("getRelations GETs /api/v1/requirements/{id}/relations", async () => {
    setNextResponse({ body: [{ id: REL_ID, relationType: "DEPENDS_ON" }] });
    const out = await getRelations(REQ_ID);

    assert.equal(fetchCalls[0].opts.method, "GET");
    assert.equal(parseUrl(fetchCalls[0]).pathname, `/api/v1/requirements/${REQ_ID}/relations`);
    assert.deepEqual(out, [{ id: REL_ID, relation_type: "DEPENDS_ON" }]);
  });

  it("createRelation POSTs body with camelCase target_id / relation_type", async () => {
    setNextResponse({
      body: { id: REL_ID, sourceId: REQ_ID, targetId: TARGET_ID, relationType: "DEPENDS_ON" },
    });
    const out = await createRelation(REQ_ID, TARGET_ID, "DEPENDS_ON");

    assert.equal(fetchCalls[0].opts.method, "POST");
    assert.equal(parseUrl(fetchCalls[0]).pathname, `/api/v1/requirements/${REQ_ID}/relations`);
    assert.deepEqual(JSON.parse(fetchCalls[0].opts.body), {
      targetId: TARGET_ID,
      relationType: "DEPENDS_ON",
    });
    assert.deepEqual(out, {
      id: REL_ID,
      source_id: REQ_ID,
      target_id: TARGET_ID,
      relation_type: "DEPENDS_ON",
    });
  });

  it("deleteRelation DELETEs /api/v1/requirements/{reqId}/relations/{relId}", async () => {
    setNextResponse({ ok: true, status: 204, body: null });
    await deleteRelation(REQ_ID, REL_ID);

    assert.equal(fetchCalls[0].opts.method, "DELETE");
    assert.equal(
      parseUrl(fetchCalls[0]).pathname,
      `/api/v1/requirements/${REQ_ID}/relations/${REL_ID}`,
    );
  });
});

describe("gc_graph wrappers", () => {
  it("getAncestors GETs /requirements/graph/ancestors/{uid} with depth + project params", async () => {
    setNextResponse({ body: [] });
    await getAncestors("GC-O007", 2, "ground-control");

    const parsed = parseUrl(fetchCalls[0]);
    assert.equal(fetchCalls[0].opts.method, "GET");
    assert.equal(parsed.pathname, "/api/v1/requirements/graph/ancestors/GC-O007");
    assert.equal(parsed.searchParams.get("depth"), "2");
    assert.equal(parsed.searchParams.get("project"), "ground-control");
  });

  it("getDescendants GETs /requirements/graph/descendants/{uid} with depth + project params", async () => {
    setNextResponse({ body: [] });
    await getDescendants("GC-O007", 3, "ground-control");

    const parsed = parseUrl(fetchCalls[0]);
    assert.equal(parsed.pathname, "/api/v1/requirements/graph/descendants/GC-O007");
    assert.equal(parsed.searchParams.get("depth"), "3");
    assert.equal(parsed.searchParams.get("project"), "ground-control");
  });

  it("findPaths GETs /requirements/graph/paths with source/target/project", async () => {
    setNextResponse({ body: [] });
    await findPaths("GC-A", "GC-B", "ground-control");

    const parsed = parseUrl(fetchCalls[0]);
    assert.equal(parsed.pathname, "/api/v1/requirements/graph/paths");
    assert.equal(parsed.searchParams.get("source"), "GC-A");
    assert.equal(parsed.searchParams.get("target"), "GC-B");
    assert.equal(parsed.searchParams.get("project"), "ground-control");
  });

  it("getGraphVisualization joins entityTypes with commas", async () => {
    setNextResponse({ body: { nodes: [], edges: [] } });
    await getGraphVisualization("ground-control", ["REQUIREMENT", "ADR"]);

    const parsed = parseUrl(fetchCalls[0]);
    assert.equal(parsed.pathname, "/api/v1/graph/visualization");
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    assert.equal(parsed.searchParams.get("entityTypes"), "REQUIREMENT,ADR");
  });

  it("getGraphVisualization omits entityTypes when not supplied", async () => {
    setNextResponse({ body: { nodes: [], edges: [] } });
    await getGraphVisualization("ground-control");

    const parsed = parseUrl(fetchCalls[0]);
    assert.equal(parsed.searchParams.has("entityTypes"), false);
  });

  it("extractSubgraph POSTs /graph/subgraph/query with camelCase body", async () => {
    setNextResponse({ body: { nodes: [], edges: [] } });
    await extractSubgraph(["n1", "n2"], "ground-control", ["REQUIREMENT"], 2);

    const parsed = parseUrl(fetchCalls[0]);
    assert.equal(fetchCalls[0].opts.method, "POST");
    assert.equal(parsed.pathname, "/api/v1/graph/subgraph/query");
    assert.equal(parsed.searchParams.get("project"), "ground-control");
    assert.deepEqual(JSON.parse(fetchCalls[0].opts.body), {
      rootNodeIds: ["n1", "n2"],
      entityTypes: ["REQUIREMENT"],
      maxDepth: 2,
    });
  });

  it("traverseGraph POSTs /graph/traversal/query with camelCase body", async () => {
    setNextResponse({ body: { nodes: [] } });
    await traverseGraph(["n1"], "ground-control", null, 1);

    assert.equal(fetchCalls[0].opts.method, "POST");
    assert.equal(parseUrl(fetchCalls[0]).pathname, "/api/v1/graph/traversal/query");
    assert.deepEqual(JSON.parse(fetchCalls[0].opts.body), {
      rootNodeIds: ["n1"],
      entityTypes: null,
      maxDepth: 1,
    });
  });

  it("findGraphPaths POSTs /graph/paths/query with camelCase source/target/depth body", async () => {
    setNextResponse({ body: { paths: [] } });
    await findGraphPaths("src-node", "dst-node", "ground-control", ["REQUIREMENT"], 4);

    assert.equal(fetchCalls[0].opts.method, "POST");
    assert.equal(parseUrl(fetchCalls[0]).pathname, "/api/v1/graph/paths/query");
    assert.deepEqual(JSON.parse(fetchCalls[0].opts.body), {
      sourceNodeId: "src-node",
      targetNodeId: "dst-node",
      entityTypes: ["REQUIREMENT"],
      maxDepth: 4,
    });
  });
});
