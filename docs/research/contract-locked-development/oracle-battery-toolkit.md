# Oracle Battery Toolkit

Issue #1292 adds reusable scaffolds for CLD oracle batteries. The toolkit
turns a boundary contract into native tests in the package that owns the
boundary:

- Java: `com.keplerops.groundcontrol.test.oracle` under backend test sources.
- Frontend/console TypeScript: `frontend/src/test/oracle-battery.ts` using
  Vitest and fast-check.
- MCP JavaScript: `mcp/ground-control/oracle-battery.js` using node:test and
  fast-check, matching the MCP package runner.

The helpers are test infrastructure. They do not define a second contract
taxonomy, authorization matrix, schema registry, or runtime service.

## Oracle Selection

| Boundary condition | Required oracle |
|--------------------|-----------------|
| Guarded or locked port with multiple implementations | Abstract conformance suite. Every implementation provider runs the same behavior cases unchanged. |
| In-memory and JPA/Postgres variants | Abstract conformance suite with one clean provider per implementation and project-scope/conflict cases. |
| State machine, idempotency, ordering, reachability, round-trip, or shrinkable input invariant | Property test using jqwik or fast-check. |
| Authenticated endpoint, schema-bound input, or protocol transition | Negative suite generated from contract data: anonymous/wrong-role/cross-scope, malformed input, or illegal transition. |
| Parser, renderer, classifier, durable-record renderer, or transformer | Golden/replay corpus with exact input-output pairs and pinned case counts. |
| High-value semantic boundary with an independent obvious model | Differential oracle comparing the reference model and implementation on generated inputs. |
| L3 or concurrency/security-critical protocol per ADR-012 | Formal model or verifier-backed check wired into CI. |

An invariant counts as enforced only when the inventory row names a machine
check that exists in the repository. Documentation alone is context.

## Java Scaffold

Use `AbstractPortConformanceSuite<T>` when a port has more than one
implementation:

```java
class MyPortConformanceTest extends AbstractPortConformanceSuite<MyPort> {
    @Override
    protected List<PortImplementation<MyPort>> implementations() {
        return List.of(
                new PortImplementation<>("memory", this::newMemoryPort),
                new PortImplementation<>("jpa", this::newJpaPort));
    }

    @TestFactory
    Stream<DynamicTest> savesAndLoads() {
        return conformanceCase("save/load", port -> {
            port.save(contractFixture());
            assertThat(port.load("contract-id")).isPresent();
        });
    }
}
```

Use `OracleInvariants` inside jqwik properties for common shapes:
idempotency, round-trip, and ordering. Use `NegativeSuite` for generated
authz/invalid-input/protocol cases, `GoldenCorpus` for exact output corpora,
and `DifferentialOracle` when a design-authority reference model exists.

The demonstration test
`DerivationAdapterDifferentialOracleTest` compares the real `DerivationAdapter`
port implementation `StubDerivationAdapter` against an independent reference
model over generated `DerivationAdapterRequest` values.

## TypeScript And MCP Scaffolds

Frontend/console tests can import the Vitest helper:

```ts
conformanceSuite("client port", implementations, cases);
propertyInvariant("round-trip", fc.string(), (value) => {
  expect(decode(encode(value))).toBe(value);
});
differentialOracle("reference model", arbitraryInput, reference, implementation);
```

MCP tests use the native node:test helper:

```js
test("tool contract", async () => {
  await negativeSuite("mcp negative matrix", cases);
  await differentialOracle("reference model", arbitraryInput, reference, tool);
});
```

Both helpers preserve fast-check seeds and shrink paths in failures so
counterexamples are reproducible.

## Golden And Replay Corpora

Corpus files should be tracked fixtures, not mutable snapshots. A corpus has:

- a stable corpus id;
- schema or renderer version when applicable;
- one stable id per case;
- input and exact expected output;
- a pinned count asserted by the test.

Updating a corpus is a contract change. Do not refresh a corpus just to make a
changed implementation pass; update the owning contract or reference model in
the same change.

## Inventory Rows

Each CLD boundary should be able to name:

- boundary id and lock level;
- contract artifact path or incumbent source of truth;
- invariant ids;
- oracle types selected from the table above;
- implementation providers;
- arbitrary/generator providers;
- corpus path and pinned count;
- reference-model entrypoint, when used;
- enforcing test/spec path.

The inventory row is the discoverability surface for policy checks and human
review. The scaffolds only run the checks named by that data.

## Guardrails

- Consume `ApiPathMatrix`, Bean Validation, Zod schemas, OpenAPI/JSON Schema,
  and invariant inventories when present; do not duplicate them.
- Do not put secrets, bearer tokens, raw headers, local environment values, or
  host-specific absolute paths in generated inputs or corpora.
- Keep Java, frontend, and MCP harnesses native to their package runners.
- Keep differential reference models semantically independent from the real
  implementation.
