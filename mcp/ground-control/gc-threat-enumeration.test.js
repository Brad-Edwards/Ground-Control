import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import {
  GC_THREAT_ENUMERATION_DESCRIPTION,
  gcThreatEnumerationToolHandler,
  gcThreatEnumerationZodShape,
} from "./gc-threat-enumeration.js";

const ORIGINAL_FETCH = globalThis.fetch;
const ORIGINAL_BASE_URL = process.env.GC_BASE_URL;

function makeFetchSpy({ status = 200, body = { candidates: [], limitations: [] } } = {}) {
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

describe("gcThreatEnumerationZodShape", () => {
  const schema = z.object(gcThreatEnumerationZodShape);

  it("accepts a minimal enumerate payload with packId only", () => {
    const parsed = schema.parse({ packId: "stride-baseline-v1" });
    assert.equal(parsed.packId, "stride-baseline-v1");
    assert.equal(parsed.project, undefined);
    assert.equal(parsed.version, undefined);
    assert.equal(parsed.snapshotId, undefined);
  });

  it("accepts a fully specified enumerate payload", () => {
    const parsed = schema.parse({
      project: "ground-control",
      packId: "stride-baseline-v1",
      version: "1.2.0",
      snapshotId: "11111111-1111-1111-1111-111111111111",
    });
    assert.equal(parsed.packId, "stride-baseline-v1");
    assert.equal(parsed.version, "1.2.0");
    assert.equal(parsed.snapshotId, "11111111-1111-1111-1111-111111111111");
  });

  it("rejects a missing packId", () => {
    assert.throws(() => schema.parse({ project: "ground-control" }));
  });

  it("rejects an empty packId", () => {
    assert.throws(() => schema.parse({ packId: "" }));
  });

  it("rejects a malformed snapshotId", () => {
    assert.throws(() =>
      schema.parse({ packId: "stride-baseline-v1", snapshotId: "not-a-uuid" }),
    );
  });

  it("description mentions GC-GRC-007", () => {
    assert.ok(GC_THREAT_ENUMERATION_DESCRIPTION.includes("GC-GRC-007"));
  });
});

describe("gcThreatEnumerationToolHandler", () => {
  it("issues a GET to /api/v1/threat-enumeration with required packId param", async () => {
    const calls = makeFetchSpy();
    await gcThreatEnumerationToolHandler({ packId: "stride-baseline-v1" });
    assert.equal(calls[0].method, "GET");
    assert.match(calls[0].url, /\/api\/v1\/threat-enumeration\b/);
    assert.match(calls[0].url, /packId=stride-baseline-v1/);
  });

  it("passes project, version, and snapshotId as query params", async () => {
    const calls = makeFetchSpy();
    await gcThreatEnumerationToolHandler({
      project: "ground-control",
      packId: "stride-baseline-v1",
      version: "1.2.0",
      snapshotId: "11111111-1111-1111-1111-111111111111",
    });
    assert.match(calls[0].url, /project=ground-control/);
    assert.match(calls[0].url, /version=1\.2\.0/);
    assert.match(calls[0].url, /snapshotId=11111111-1111-1111-1111-111111111111/);
  });

  it("returns the parsed response body", async () => {
    const body = { candidates: [{ producingRuleId: "R001", category: "STRIDE_BASELINE" }], limitations: [] };
    makeFetchSpy({ body });
    const result = await gcThreatEnumerationToolHandler({ packId: "stride-baseline-v1" });
    assert.deepEqual(result, body);
  });
});
