import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_CONTROL_IDENTIFICATION_DESCRIPTION,
  gcControlIdentificationToolHandler,
  gcControlIdentificationZodShape,
} from "./gc-control-identification.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

function makeFetchSpy({ status = 200, body = { candidates: [], gaps: [] } } = {}) {
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
});

afterEach(() => {
  globalThis.fetch = ORIGINAL_FETCH;
  if (ORIGINAL_BASE_URL === undefined) delete process.env.GC_BASE_URL;
  else process.env.GC_BASE_URL = ORIGINAL_BASE_URL;
});

describe("gcControlIdentificationZodShape", () => {
  const schema = z.object(gcControlIdentificationZodShape);

  it("accepts an identify payload with threatPackId", () => {
    const parsed = schema.parse({ threatPackId: "stride-baseline-v1" });
    assert.equal(parsed.threatPackId, "stride-baseline-v1");
  });

  it("accepts a coverage payload with action and threatModelId", () => {
    const parsed = schema.parse({
      action: "coverage",
      threatModelId: "11111111-1111-1111-1111-111111111111",
    });
    assert.equal(parsed.action, "coverage");
  });

  it("rejects an unknown action", () => {
    assert.throws(() => schema.parse({ action: "confirm" }));
  });

  it("rejects a malformed threatModelId", () => {
    assert.throws(() => schema.parse({ action: "coverage", threatModelId: "not-a-uuid" }));
  });

  it("description mentions GC-GRC-008", () => {
    assert.ok(GC_CONTROL_IDENTIFICATION_DESCRIPTION.includes("GC-GRC-008"));
  });
});

describe("gcControlIdentificationToolHandler", () => {
  it("defaults to a GET on /api/v1/control-identification with threatPackId", async () => {
    const calls = makeFetchSpy();
    await gcControlIdentificationToolHandler({ threatPackId: "stride-baseline-v1" });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/control-identification\b/);
    assert.match(calls[0].url, /threatPackId=stride-baseline-v1/);
  });

  it("passes project, version, and snapshotId as query params for identify", async () => {
    const calls = makeFetchSpy();
    await gcControlIdentificationToolHandler({
      project: "ground-control",
      threatPackId: "stride-baseline-v1",
      version: "1.2.0",
      snapshotId: "11111111-1111-1111-1111-111111111111",
    });
    assert.match(calls[0].url, /project=ground-control/);
    assert.match(calls[0].url, /version=1\.2\.0/);
    assert.match(calls[0].url, /snapshotId=11111111-1111-1111-1111-111111111111/);
  });

  it("routes action=coverage to the coverage endpoint with threatModelId", async () => {
    const calls = makeFetchSpy({ body: { threatModelId: "t", controls: [] } });
    await gcControlIdentificationToolHandler({
      action: "coverage",
      project: "ground-control",
      threatModelId: "22222222-2222-2222-2222-222222222222",
    });
    assert.match(calls[0].url, /\/api\/v1\/control-identification\/coverage\b/);
    assert.match(calls[0].url, /threatModelId=22222222-2222-2222-2222-222222222222/);
  });

  it("returns the parsed response body", async () => {
    // request() snake_cases response keys; use single-word keys so the body round-trips.
    const body = { candidates: [{ uid: "AC-3" }], gaps: [] };
    makeFetchSpy({ body });
    const result = await gcControlIdentificationToolHandler({ threatPackId: "stride-baseline-v1" });
    assert.deepEqual(result, body);
  });
});
