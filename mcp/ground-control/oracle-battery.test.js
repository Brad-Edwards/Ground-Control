import test from "node:test";
import assert from "node:assert/strict";
import fc from "fast-check";

import {
  conformanceSuite,
  differentialOracle,
  goldenCorpus,
  negativeSuite,
  propertyInvariant,
} from "./oracle-battery.js";

test("oracle battery node scaffolds run conformance cases", async () => {
  await conformanceSuite("mcp text port", [
    { name: "trim-lowercase", create: () => ({ normalize: (value) => value.trim().toLowerCase() }) },
    { name: "regex-lowercase", create: () => ({ normalize: (value) => value.replace(/^\s+|\s+$/g, "").toLowerCase() }) },
  ], [
    {
      name: "normalizes strings",
      exercise: (port) => assert.equal(port.normalize("  CLD  "), "cld"),
    },
  ]);
});

test("oracle battery node scaffolds run negative cases", async () => {
  await negativeSuite("mcp negative matrix", [
    {
      id: "authz:anonymous",
      kind: "authorization",
      exercise: () => {
        throw new Error("anonymous denied");
      },
      expectedMessage: /denied/,
    },
  ]);
});

test("oracle battery node scaffolds run property and differential oracles", async () => {
  await propertyInvariant("normalize idempotent", fc.string(), (value) => {
    const normalize = (input) => input.trim().toLowerCase();

    assert.equal(normalize(normalize(value)), normalize(value));
  }, { numRuns: 25 });

  await differentialOracle(
    "sort reference",
    fc.array(fc.integer(), { maxLength: 20 }),
    (values) => [...values].sort((a, b) => a - b),
    (values) => [...values].sort((a, b) => a - b),
    { numRuns: 25 },
  );
});

test("oracle battery node scaffolds pin golden corpus counts", async () => {
  const result = await goldenCorpus("renderer", [
    { id: "simple", input: "CLD", expected: "cld" },
  ], (input) => input.toLowerCase());

  assert.equal(result.pinnedCount, 1);
});

test("oracle battery node scaffolds reject empty evidence collections", async () => {
  const implementation = {
    name: "trim-lowercase",
    create: () => ({ normalize: (value) => value.trim().toLowerCase() }),
  };
  const testCase = {
    name: "normalizes strings",
    exercise: (port) => assert.equal(port.normalize("  CLD  "), "cld"),
  };

  await assert.rejects(() => conformanceSuite("missing implementation", [], [testCase]), /at least one entry/);
  await assert.rejects(() => conformanceSuite("missing cases", [implementation], []), /at least one entry/);
  await assert.rejects(() => negativeSuite("missing negative cases", []), /at least one entry/);
  await assert.rejects(() => goldenCorpus("missing corpus", [], (input) => input), /at least one entry/);
});

test("oracle battery node scaffolds fail on real oracle mismatches", async () => {
  await assert.rejects(() => conformanceSuite("broken conformance", [
    { name: "identity", create: () => ({ normalize: (value) => value }) },
  ], [
    {
      name: "normalizes strings",
      exercise: (port) => assert.equal(port.normalize("  CLD  "), "cld"),
    },
  ]), /Expected values/);

  await assert.rejects(() => propertyInvariant("broken property", fc.constant("CLD"), (value) => {
    assert.equal(value, "cld");
  }, { numRuns: 1 }), /Property failed/);

  await assert.rejects(
    () => differentialOracle(
      "broken differential",
      fc.constant(" CLD "),
      (value) => value.trim().toLowerCase(),
      (value) => value,
      { numRuns: 1 },
    ),
    /Property failed/,
  );

  await assert.rejects(
    () => goldenCorpus("broken corpus", [{ id: "simple", input: "CLD", expected: "cld" }], (input) => input),
    /simple|Expected values/,
  );
});
