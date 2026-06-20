import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_DERIVATION_ACTIONS,
  gcDerivationToolHandler,
  gcDerivationZodShape,
} from "./gc-derivation.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

function makeFetchSpy({ status = 200, body = { ok: true } } = {}) {
  const calls = [];
  globalThis.fetch = async (url, opts) => {
    calls.push({
      url: url.toString(),
      method: opts?.method ?? "GET",
      body: opts?.body ? JSON.parse(opts.body) : null,
    });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  };
  return calls;
}

beforeEach(() => {
  process.env.GC_BASE_URL = "https://gc.test";
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
  else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
});

describe("gcDerivationZodShape", () => {
  it("exposes the expected action vocabulary", () => {
    assert.deepEqual([...GC_DERIVATION_ACTIONS].sort(), [
      "get_run",
      "list_capture_limits",
      "list_facts",
      "list_runs",
      "run",
    ]);
  });

  it("rejects unknown actions", () => {
    const result = z.object(gcDerivationZodShape).safeParse({ action: "delete" });
    assert.equal(result.success, false);
  });
});

describe("gcDerivationToolHandler", () => {
  it("run POSTs camelCase scope body and project param", async () => {
    const calls = makeFetchSpy({ status: 201, body: { run: { id: "run-1" } } });
    const result = await gcDerivationToolHandler({
      action: "run",
      project: "ground-control",
      scope_mode: "PATH_SET",
      commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
      paths: ["backend/src/main/java/App.java"],
      languages: ["java"],
      surfaces: ["application"],
    });

    assert.equal(result.run.id, "run-1");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "POST");
    assert.match(calls[0].url, /\/api\/v1\/derivations\/runs(\?|$)/);
    assert.match(calls[0].url, /project=ground-control/);
    assert.deepEqual(calls[0].body, {
      scopeMode: "PATH_SET",
      commitSha: "25c991231cf2a1464792846b083d1bd885299b3c",
      paths: ["backend/src/main/java/App.java"],
      languages: ["java"],
      surfaces: ["application"],
    });
  });

  it("list_runs GETs all runs for project", async () => {
    const calls = makeFetchSpy({ body: [{ id: "run-1" }] });
    const result = await gcDerivationToolHandler({
      action: "list_runs",
      project: "ground-control",
    });

    assert.equal(Array.isArray(result), true);
    assert.equal(result[0].id, "run-1");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/derivations\/runs\?/);
    assert.match(calls[0].url, /project=ground-control/);
  });

  it("get_run GETs one run by id", async () => {
    const calls = makeFetchSpy({ body: { id: "11111111-1111-1111-1111-111111111111" } });
    const result = await gcDerivationToolHandler({
      action: "get_run",
      project: "ground-control",
      id: "11111111-1111-1111-1111-111111111111",
    });

    assert.equal(result.id, "11111111-1111-1111-1111-111111111111");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/derivations\/runs\/11111111-1111-1111-1111-111111111111\?/);
    assert.match(calls[0].url, /project=ground-control/);
  });

  it("list_facts GETs with run and fact kind filters", async () => {
    const calls = makeFetchSpy({ body: [{ id: "fact-1" }] });
    const result = await gcDerivationToolHandler({
      action: "list_facts",
      project: "ground-control",
      run_id: "11111111-1111-1111-1111-111111111111",
      fact_kind: "COMPONENT",
    });

    assert.equal(Array.isArray(result), true);
    assert.equal(result[0].id, "fact-1");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/derivations\/facts\?/);
    assert.match(calls[0].url, /project=ground-control/);
    assert.match(calls[0].url, /runId=11111111-1111-1111-1111-111111111111/);
    assert.match(calls[0].url, /factKind=COMPONENT/);
  });

  it("list_capture_limits GETs with reason filter", async () => {
    const calls = makeFetchSpy({ body: [{ id: "limit-1" }] });
    const result = await gcDerivationToolHandler({
      action: "list_capture_limits",
      project: "ground-control",
      reason: "UNSUPPORTED_SURFACE",
    });

    assert.equal(Array.isArray(result), true);
    assert.equal(result[0].id, "limit-1");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/derivations\/capture-limits\?/);
    assert.match(calls[0].url, /project=ground-control/);
    assert.match(calls[0].url, /reason=UNSUPPORTED_SURFACE/);
  });

  it("run rejects missing required fields", async () => {
    makeFetchSpy();
    await assert.rejects(
      gcDerivationToolHandler({
        action: "run",
        project: "ground-control",
        scope_mode: "FULL_REPO",
        commit_sha: "25c991231cf2a1464792846b083d1bd885299b3c",
        languages: ["java"],
      }),
      /surfaces/,
    );
  });
});
