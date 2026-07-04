import assert from "node:assert/strict";
import fc from "fast-check";

export async function conformanceSuite(name, implementations, cases) {
  assert.ok(name, "conformance suite name is required");
  assertNonEmpty(implementations, `${name} implementations`);
  assertNonEmpty(cases, `${name} conformance cases`);

  for (const implementation of implementations) {
    for (const testCase of cases) {
      await testCase.exercise(implementation.create());
    }
  }

  return {
    implementationCount: implementations.length,
    caseCount: cases.length,
  };
}

export async function negativeSuite(name, cases) {
  assert.ok(name, "negative suite name is required");
  assertNonEmpty(cases, `${name} negative cases`);

  for (const testCase of cases) {
    await assertRejects(testCase);
  }

  return { caseCount: cases.length };
}

export async function propertyInvariant(name, arbitrary, property, options) {
  assert.ok(name, "property name is required");
  await fc.assert(fc.asyncProperty(arbitrary, property), options);
}

export async function differentialOracle(name, arbitrary, referenceModel, implementation, options) {
  assert.ok(name, "differential oracle name is required");
  await fc.assert(
    fc.asyncProperty(arbitrary, async (input) => {
      assert.deepEqual(await implementation(input), await referenceModel(input));
    }),
    options,
  );
}

export async function goldenCorpus(name, cases, render) {
  assert.ok(name, "golden corpus name is required");
  assertNonEmpty(cases, `${name} golden cases`);

  for (const testCase of cases) {
    assert.deepEqual(await render(testCase.input), testCase.expected, testCase.id);
  }

  return { pinnedCount: cases.length };
}

async function assertRejects(testCase) {
  let thrown;

  try {
    await testCase.exercise();
  } catch (error) {
    thrown = error;
  }

  assert.ok(thrown, `${testCase.id} accepted unexpectedly`);
  if (testCase.expectedError) {
    assert.ok(thrown instanceof testCase.expectedError, `${testCase.id} rejected with wrong error type`);
  }
  if (testCase.expectedMessage) {
    assert.match(messageOf(thrown), testCase.expectedMessage, testCase.id);
  }
}

function messageOf(error) {
  return error instanceof Error ? error.message : String(error);
}

function assertNonEmpty(values, label) {
  assert.ok(Array.isArray(values), `${label} must be an array`);
  assert.notEqual(values.length, 0, `${label} needs at least one entry`);
}
