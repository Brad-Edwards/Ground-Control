# Oracle Battery Toolkit Preflight

Issue: #1292
Requirement: none

This note records architecture guardrails for the CLD oracle battery toolkit.
It is not an implementation plan, and it does not implement the scaffolds,
differential harness, corpus convention, or oracle-selection guide.

## Boundary

The toolkit is test and policy infrastructure for contract-locked development,
not a new production aggregate. It should not add controllers, services,
repositories, migrations, runtime jobs, GitHub side effects, or workflow state.

ADR-087 owns the CLD vocabulary: contract package, invariant inventory, oracle
battery, design authority, implementer, verifier, lock level, and risk scoring.
ADR-082 owns the contract-surface direction and the first conformance-suite
pattern. Issue #1292 should make those definitions easier to apply; it should
not create a second taxonomy for oracle types, risk levels, invariants, or
protected paths.

Treat contract artifacts as inputs to the battery, not as things the battery
redefines. When the ADR-082 `contracts/` surface exists, scaffolds should read
from committed OpenAPI, JSON Schema, authz matrix, and invariant-inventory
artifacts. Until then, they should consume the incumbent sources directly:
Spring MVC DTOs and Bean Validation, `ApiPathMatrix`, MCP Zod schemas and body
allowlists, frontend API-client contracts, durable-record renderers, and
existing policy inventories.

The Java conformance scaffold should be a real port contract: one abstract
behavioral suite that every implementation runs unchanged. Existing
`*AdapterContractTest` files are useful examples of interface-shape coverage,
but they are mostly stub-backed. They are not sufficient evidence for the
abstract dual-run port harness required here.

The TypeScript side must respect current package boundaries. The frontend uses
Vitest. The MCP package currently uses Node's built-in test runner plus Zod. If
Vitest and fast-check are introduced for MCP to satisfy the issue text, that is
a package-level dependency and script decision with lockfile and CI impact, not
a hidden one-off test-file style.

## Incumbents To Reuse

- CLD method authority: ADR-087 and the research packet under
  `docs/research/contract-locked-development/`.
- Contract surface: ADR-082, Springdoc OpenAPI generation, JSON Schema
  direction for durable/workflow records, breaking-change and drift-gate
  patterns, and future `contracts/` artifacts.
- Assurance ladder: ADR-012 L0-L3 classification, jqwik property tests tagged
  `@Tag("slow")`, TLA+/OpenJML where scored, and Pitest as the separate
  mutation meta-oracle for #1293.
- Java architecture shape: `api/ -> domain/ <- infrastructure/`, services as
  aggregate transaction boundaries, repositories as query owners, ArchUnit
  rules in `ArchitectureTest`, and project-scoped repository predicates.
- Java test scaffolds: `bin/scaffold-controller`, `bin/scaffold-l2-state-machine`,
  `@WebMvcTest` controller slices, `BaseIntegrationTest`/Testcontainers for
  JPA or full-stack checks, Instancio for generated examples, and existing
  state-machine property tests.
- Security and error layer: `ApiPathMatrix`, `ApiSecurityConfig`,
  `BrowserSecurityConfig`, `ApiSecurityConfigTest`,
  `ApiSecurityIntegrationTest`, `GlobalExceptionHandler`, and
  `ErrorResponse`.
- Audit and logging: `ActorFilter`, `ActorHolder`, Envers-backed audited
  entities, and low-cardinality SLF4J logging.
- MCP adapter layer: Zod shapes, `pick`, `reqArg`, `TO_CAMEL`,
  `OPAQUE_VALUE_KEYS`, `RequestError`, `parseErrorBody`,
  `addAuthorizationHeader`, `detectSensitiveBodyContent`, and existing
  node:test adapter tests.
- Frontend test layer: Vitest, React Testing Library where UI is involved,
  `api-client`'s `ApiError` behavior, session/CSRF expectations, and generated
  TypeScript-client direction from ADR-082.
- Policy style: inventory-driven checks in `tools/policy/checks.py`, shared
  golden cases such as `tools/policy/deferral_cases.json`, renderer fixtures
  such as `tools/render_pr_body_fixture.mjs`, and `make policy`.

## Cross-Cutting Layers

- Auth surface: negative suites must derive anonymous, wrong-role, and
  cross-project cases from `ApiPathMatrix` and, later, the ADR-082 authz matrix
  artifact. Do not copy path rules into feature tests or create
  controller-local authorization logic.
- Input validation: backend invalid-input cases should pass through Jackson,
  Bean Validation, `@Validated`, and service validators. MCP cases should pass
  through the adapter Zod schema before any backend call. Frontend cases should
  assert client behavior at the API-client or component boundary already used
  by the screen. Do not duplicate backend validation as independent frontend or
  MCP truth.
- Error envelopes: HTTP failures must continue to use `GroundControlException`
  subclasses, `GlobalExceptionHandler`, and `ErrorResponse`. MCP failures
  should preserve `RequestError` and parsed backend `error.code/message/detail`.
  Differential or corpus failures should report minimized counterexamples
  without dumping stack traces, full request bodies, tokens, or raw environment.
- Secret handling: golden corpora, replay inputs, minimized counterexamples,
  and generated negative cases must not contain real tokens, credentials,
  `.env` values, or host-specific paths. Reuse existing sensitive-content
  filters for any issue-thread or PR-body rendering; this issue should not
  require GitHub posting.
- Config shape: the toolkit should need no production configuration. If a real
  operator knob appears later, use `@ConfigurationProperties` or package-level
  test config, not ad hoc env parsing.
- OS/runtime exposure: scaffolds and harnesses should read tracked source,
  test fixtures, and build artifacts inside the workspace. They should not
  shell out to `gh`, `curl`, live Ground Control instances, or arbitrary
  user-supplied commands. If a runner must invoke a local generator, use fixed
  argv, timeouts, output caps, and sanitized errors.
- Persistence: a Java port conformance suite must run the same contract
  against in-memory and JPA/Postgres implementations through provider/factory
  seams. Tests should verify project scoping, transaction behavior, uniqueness,
  and conflict handling at the repository/service boundary without making the
  abstract suite import infrastructure directly.
- Observability: oracle helper code should avoid new production logs. Test
  diagnostics should name boundary id, invariant id, oracle type, seed, and
  corpus case id where applicable, with low-cardinality names and no payload
  dumps.
- Workflow/policy: if invariant inventory or corpus pinning becomes a policy
  check, add one inventory-driven rule and tests. Do not put unenforceable
  prompt text in skills or docs and call it a gate.

## Extensibility

The seam is a battery inventory entry, not a new harness per boundary. A
boundary entry should be able to name the contract artifact, oracle types,
invariant IDs, implementation providers, generator/arbitrary providers, corpus
path, pinned corpus counts, reference-model entrypoint, and risk-score inputs.
Adding the next boundary should usually require one inventory row plus
contract-specific data and generators.

The Java dual-run seam should be an implementation provider or factory that
creates a clean instance for each test run. Abstract conformance tests should
depend on the port contract and data builders, while concrete subclasses own
in-memory and JPA setup.

The differential seam should compare a design-authority reference model and
the real implementation over the same canonical command/result DTOs. The
reference model is not a mock owned by the implementation lane, and it should
not call production repositories or services.

The corpus seam should be a stable input-to-output convention with exact output
files, schema/version metadata, pinned counts, and clear update rules. Parsers,
renderers, classifiers, and record builders should use the same convention
rather than bespoke snapshots.

## Gotchas And Anti-Patterns

- Do not satisfy conformance with stub-only interface tests. The required
  claim is "all implementations obey the same behavior," not "the interface
  can express a few outcomes."
- Do not create duplicate schemas for request bodies, durable records, authz
  rules, or invariant inventories. Consume OpenAPI/JSON Schema/authz data when
  available and incumbent Java/Zod shapes while the contracts directory is
  still absent.
- Do not make the negative suite an alternate validation framework. It should
  generate cases that exercise the existing validators and error envelope.
- Do not conflate property tests with exhaustive enum matrix tests. Use jqwik
  or fast-check for generative invariants such as state machines, idempotency,
  ordering, round-trip, shrinking, and differential inputs; keep small finite
  matrices as direct example tests where clearer.
- Do not conflate golden/replay corpora with mutable snapshots. Updating a
  corpus is a contract change with pinned counts and reviewable rationale, not
  a test-refresh reflex.
- Do not conflate reference models with in-memory adapters. A reference model
  is design-authority-authored, slow, obvious, and semantically independent;
  an in-memory adapter is one implementation that still must pass conformance.
- Do not build one cross-language "oracle engine" abstraction. Share
  conventions and inventories; keep Java, frontend, and MCP harnesses native
  to their package test runners unless a package-level migration is made.
- Do not add new exception hierarchies, error envelopes, auth path registries,
  logging frameworks, config parsers, or workflow markers for this toolkit.
- Do not make generated-data tests flaky. Seeds, shrink output, and minimized
  counterexamples must be reproducible locally and in CI.
- Do not let counterexamples, replay corpora, or failure messages echo secrets,
  bearer tokens, raw headers, provider credentials, or full user payloads.

## Non-Goals

- No implementation of the #1292 scaffolds, dependency additions, corpus
  files, reference model, or selection guide in this note.
- No mutation threshold or meta-oracle gate; that is #1293.
- No protected-path role-split gate; that is #1294.
- No architecture registry or lock-level data model; that is #1295.
- No CLD pilot, workflow productization, or portfolio packaging; those are
  #1296 through #1299.
- No production REST, MCP, frontend, persistence, audit, security, deployment,
  or runtime behavior change.
- No live-service, network, GitHub, cloud, or provider interaction.
