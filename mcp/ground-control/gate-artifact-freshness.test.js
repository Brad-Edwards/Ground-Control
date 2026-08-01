import assert from "node:assert/strict";
import test from "node:test";
import { mkdtempSync, mkdirSync, readdirSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  childGateArtifactPaths,
  emitPolicyAndValeAttempts,
  emitSpotbugsAttempt,
  readGateArtifact,
} from "./implement/gate-helpers.js";

function repo() {
  return mkdtempSync(join(tmpdir(), "gc-gate-artifact-"));
}

function recorder() {
  const attempts = [];
  return { attempts, recordStationAttempt: async (a) => attempts.push(a) };
}

function age(path, secondsAgo) {
  const when = new Date(Date.now() - secondsAgo * 1000);
  utimesSync(path, when, when);
}

test("resolving the paths clears the previous attempt's artifacts", () => {
  const root = repo();
  const first = childGateArtifactPaths(root);
  writeFileSync(first.policy, JSON.stringify({ violations: [{ code: "GC-X" }] }));
  writeFileSync(first.vale, JSON.stringify({}));

  childGateArtifactPaths(root);

  const remaining = readdirSync(first.dir);
  assert.equal(remaining.includes("policy.json"), false);
  assert.equal(remaining.includes("vale.json"), false);
});

test("a gate that never writes leaves the station unmeasured, not passing", async () => {
  // The regression: the prior attempt's clean artifact stayed on disk, so an attempt whose gate
  // crashed before writing replayed that pass as its own result.
  const root = repo();
  const stale = childGateArtifactPaths(root);
  writeFileSync(stale.policy, JSON.stringify({ violations: [] }));

  const artifacts = childGateArtifactPaths(root);
  const emitter = recorder();
  await emitPolicyAndValeAttempts(emitter, artifacts, new Date());

  assert.deepEqual(emitter.attempts, []);
});

test("an artifact older than the attempt is not read as the attempt's result", () => {
  const root = repo();
  const artifacts = childGateArtifactPaths(root);
  writeFileSync(artifacts.policy, JSON.stringify({ violations: [] }));
  age(artifacts.policy, 600);

  assert.equal(readGateArtifact(artifacts.policy, artifacts.freshnessFloorMs, () => true), null);
});

test("an artifact written by this attempt is read", async () => {
  const root = repo();
  const artifacts = childGateArtifactPaths(root);
  writeFileSync(artifacts.policy, JSON.stringify({ violations: [] }));

  const emitter = recorder();
  await emitPolicyAndValeAttempts(emitter, artifacts, new Date());

  assert.equal(emitter.attempts.length, 1);
  assert.equal(emitter.attempts[0].stationId, "policy");
  assert.equal(emitter.attempts[0].stationResult, "pass");
});

test("a policy artifact missing its violations container is unmeasured, not a pass", async () => {
  // It parses, so the adapter extracts zero findings, and zero findings used to mean pass. A
  // truncated write or a changed output format would certify a gate nothing ever inspected.
  const root = repo();
  const artifacts = childGateArtifactPaths(root);
  writeFileSync(artifacts.policy, JSON.stringify({ unexpected: "shape" }));

  const emitter = recorder();
  await emitPolicyAndValeAttempts(emitter, artifacts, new Date());

  assert.deepEqual(emitter.attempts, []);
});

test("a vale artifact of the wrong container type is unmeasured", async () => {
  const root = repo();
  const artifacts = childGateArtifactPaths(root);
  writeFileSync(artifacts.vale, JSON.stringify([{ Check: "Vale.Spelling" }]));

  const emitter = recorder();
  await emitPolicyAndValeAttempts(emitter, artifacts, new Date());

  assert.deepEqual(emitter.attempts, []);
});

test("an unparseable artifact is unmeasured", () => {
  const root = repo();
  const artifacts = childGateArtifactPaths(root);
  writeFileSync(artifacts.vale, "{ truncated");

  assert.equal(readGateArtifact(artifacts.vale, artifacts.freshnessFloorMs, () => true), null);
});

test("a spotbugs report from a previous attempt is not replayed", async () => {
  const root = repo();
  const dir = join(root, "backend", "build", "reports", "spotbugs");
  mkdirSync(dir, { recursive: true });
  const report = join(dir, "main.xml");
  writeFileSync(report, '<BugCollection><BugInstance type="X" /></BugCollection>');
  age(report, 600);
  const { freshnessFloorMs } = childGateArtifactPaths(root);

  const emitter = recorder();
  await emitSpotbugsAttempt(emitter, root, { startedAt: new Date(), durationMs: 1, freshnessFloorMs });

  assert.deepEqual(emitter.attempts, []);
});

test("a file that is not a spotbugs report never certifies the station", async () => {
  const root = repo();
  const dir = join(root, "backend", "build", "reports", "spotbugs");
  mkdirSync(dir, { recursive: true });
  const { freshnessFloorMs } = childGateArtifactPaths(root);
  writeFileSync(join(dir, "main.xml"), "<html><body>not a report</body></html>");

  const emitter = recorder();
  await emitSpotbugsAttempt(emitter, root, { startedAt: new Date(), durationMs: 1, freshnessFloorMs });

  assert.deepEqual(emitter.attempts, []);
});

test("a spotbugs report from this attempt is recorded", async () => {
  const root = repo();
  const dir = join(root, "backend", "build", "reports", "spotbugs");
  mkdirSync(dir, { recursive: true });
  const { freshnessFloorMs } = childGateArtifactPaths(root);
  writeFileSync(join(dir, "main.xml"), "<BugCollection></BugCollection>");

  const emitter = recorder();
  await emitSpotbugsAttempt(emitter, root, { startedAt: new Date(), durationMs: 1, freshnessFloorMs });

  assert.equal(emitter.attempts.length, 1);
  assert.equal(emitter.attempts[0].stationResult, "pass");
});
