import fc from "fast-check";
import { describe, expect, it } from "vitest";

import {
  assertDifferentialEquivalent,
  assertGoldenCase,
  assertPropertyInvariant,
  conformanceSuite,
  differentialOracle,
  goldenCorpus,
  negativeSuite,
  propertyInvariant,
} from "./oracle-battery";

type TextPort = {
  normalize(value: string): string;
};

describe("oracle battery vitest scaffolds", () => {
  conformanceSuite<TextPort>(
    "text port conformance",
    [
      {
        name: "trim-lowercase",
        create: () => ({ normalize: (value) => value.trim().toLowerCase() }),
      },
      {
        name: "regex-lowercase",
        create: () => ({
          normalize: (value) => value.replace(/^\s+|\s+$/g, "").toLowerCase(),
        }),
      },
    ],
    [
      {
        name: "normalizes boundary strings",
        exercise: (port) => {
          expect(port.normalize("  CLD  ")).toBe("cld");
        },
      },
    ],
  );

  negativeSuite("negative matrix", [
    {
      id: "authz:anonymous",
      kind: "authorization",
      exercise: () => {
        throw new Error("anonymous denied");
      },
      expectedMessage: /denied/,
    },
    {
      id: "input:blank",
      kind: "invalid-input",
      exercise: () => {
        throw new TypeError("blank rejected");
      },
      expectedError: TypeError,
    },
  ]);

  propertyInvariant(
    "normalization is idempotent",
    fc.string(),
    (value) => {
      const normalize = (input: string) => input.trim().toLowerCase();

      expect(normalize(normalize(value))).toBe(normalize(value));
    },
    { numRuns: 25 },
  );

  differentialOracle(
    "reference sorter matches implementation sorter",
    fc.array(fc.integer(), { maxLength: 20 }),
    (values) => [...values].sort((a, b) => a - b),
    (values) => [...values].sort((a, b) => a - b),
    { numRuns: 25 },
  );

  goldenCorpus(
    "renderer corpus",
    [{ id: "simple", input: "CLD", expected: "cld" }],
    (input) => input.toLowerCase(),
  );

  it("rejects empty evidence collections", () => {
    const implementation = {
      name: "trim-lowercase",
      create: () => ({
        normalize: (value: string) => value.trim().toLowerCase(),
      }),
    };
    const testCase = {
      name: "normalizes strings",
      exercise: (port: TextPort) => {
        expect(port.normalize("  CLD  ")).toBe("cld");
      },
    };

    expect(() =>
      conformanceSuite("missing implementation", [], [testCase]),
    ).toThrow(/at least one entry/);
    expect(() =>
      conformanceSuite("missing cases", [implementation], []),
    ).toThrow(/at least one entry/);
    expect(() => negativeSuite("missing negative cases", [])).toThrow(
      /at least one entry/,
    );
    expect(() =>
      goldenCorpus("missing corpus", [], (input: string) => input),
    ).toThrow(/at least one entry/);
  });

  it("assertion primitives fail on real mismatches", async () => {
    await expect(
      assertPropertyInvariant(
        fc.constant("CLD"),
        (value) => {
          expect(value).toBe("cld");
        },
        { numRuns: 1 },
      ),
    ).rejects.toThrow();
    await expect(
      assertDifferentialEquivalent(
        " CLD ",
        (value) => value.trim().toLowerCase(),
        (value) => value,
      ),
    ).rejects.toThrow();
    await expect(
      assertGoldenCase(
        { id: "simple", input: "CLD", expected: "cld" },
        (input) => input,
      ),
    ).rejects.toThrow();
  });
});
