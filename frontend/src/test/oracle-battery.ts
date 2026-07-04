import fc, {
  type Arbitrary,
  type Parameters as FastCheckParameters,
} from "fast-check";
import { describe, expect, it } from "vitest";

export type NamedImplementation<T> = {
  name: string;
  create: () => T;
};

export type ConformanceCase<T> = {
  name: string;
  exercise: (subject: T) => void | Promise<void>;
};

export type NegativeCase = {
  id: string;
  kind: "authorization" | "invalid-input" | "protocol-violation";
  exercise: () => unknown | Promise<unknown>;
  expectedError?: ErrorConstructor;
  expectedMessage?: RegExp;
};

export type GoldenCase<I, O> = {
  id: string;
  input: I;
  expected: O;
};

export function conformanceSuite<T>(
  name: string,
  implementations: NamedImplementation<T>[],
  cases: ConformanceCase<T>[],
): void {
  requireNonEmpty(name, "conformance suite name");
  requireNonEmptyArray(implementations, `${name} implementations`);
  requireNonEmptyArray(cases, `${name} conformance cases`);

  describe(name, () => {
    for (const implementation of implementations) {
      describe(implementation.name, () => {
        for (const testCase of cases) {
          it(testCase.name, async () => {
            await testCase.exercise(implementation.create());
          });
        }
      });
    }
  });
}

export function negativeSuite(name: string, cases: NegativeCase[]): void {
  requireNonEmpty(name, "negative suite name");
  requireNonEmptyArray(cases, `${name} negative cases`);

  describe(name, () => {
    for (const testCase of cases) {
      it(`${testCase.kind} :: ${testCase.id}`, async () => {
        let thrown: unknown;

        try {
          await testCase.exercise();
        } catch (error) {
          thrown = error;
        }

        expect(thrown, testCase.id).toBeDefined();
        if (testCase.expectedError) {
          expect(thrown).toBeInstanceOf(testCase.expectedError);
        }
        if (testCase.expectedMessage) {
          expect(messageOf(thrown)).toMatch(testCase.expectedMessage);
        }
      });
    }
  });
}

export function propertyInvariant<T>(
  name: string,
  arbitrary: Arbitrary<T>,
  property: (input: T) => void | Promise<void>,
  options?: FastCheckParameters<[T]>,
): void {
  it(name, async () => {
    await assertPropertyInvariant(arbitrary, property, options);
  });
}

export async function assertPropertyInvariant<T>(
  arbitrary: Arbitrary<T>,
  property: (input: T) => void | Promise<void>,
  options?: FastCheckParameters<[T]>,
): Promise<void> {
  await fc.assert(
    fc.asyncProperty(arbitrary, async (input) => {
      await property(input);
    }),
    options,
  );
}

export function differentialOracle<I, O>(
  name: string,
  arbitrary: Arbitrary<I>,
  referenceModel: (input: I) => O | Promise<O>,
  implementation: (input: I) => O | Promise<O>,
  options?: FastCheckParameters<[I]>,
): void {
  it(name, async () => {
    await fc.assert(
      fc.asyncProperty(arbitrary, async (input) => {
        await assertDifferentialEquivalent(
          input,
          referenceModel,
          implementation,
        );
      }),
      options,
    );
  });
}

export async function assertDifferentialEquivalent<I, O>(
  input: I,
  referenceModel: (input: I) => O | Promise<O>,
  implementation: (input: I) => O | Promise<O>,
): Promise<void> {
  expect(await implementation(input)).toEqual(await referenceModel(input));
}

export function goldenCorpus<I, O>(
  name: string,
  cases: GoldenCase<I, O>[],
  render: (input: I) => O | Promise<O>,
): void {
  requireNonEmpty(name, "golden corpus name");
  requireNonEmptyArray(cases, `${name} golden cases`);

  describe(name, () => {
    for (const testCase of cases) {
      it(`${testCase.id} (${cases.length} pinned)`, async () => {
        await assertGoldenCase(testCase, render);
      });
    }
  });
}

export async function assertGoldenCase<I, O>(
  testCase: GoldenCase<I, O>,
  render: (input: I) => O | Promise<O>,
): Promise<void> {
  expect(await render(testCase.input)).toEqual(testCase.expected);
}

function messageOf(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

function requireNonEmpty(value: string, label: string): void {
  if (!value.trim()) {
    throw new Error(`${label} is required`);
  }
}

function requireNonEmptyArray<T>(values: T[], label: string): void {
  if (values.length === 0) {
    throw new Error(`${label} needs at least one entry`);
  }
}
