---
id: GC-CLD-3
title: "Oracle Battery and Invariant Inventory"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 9
created_at: 2026-07-04T02:45:41.986472Z
updated_at: 2026-07-28T03:05:27.609872Z
---

# GC-CLD-3 — Oracle Battery and Invariant Inventory

## Statement

Ground Control shall support CLD oracle batteries for contract-bearing boundaries. A battery shall compose the checks appropriate to the boundary risk score, including conformance suites, property tests, negative suites, golden or replay corpora, executable reference models, formal specifications where warranted, and an invariant inventory that maps each stable invariant identifier to its enforcing check. A declared contract layer or invariant shall not count as enforced unless a named machine check exists and is discoverable.

## Rationale

The contract package is only authoritative when enforcement is mechanical. An invariant-to-check inventory and reusable oracle scaffolds prevent green-but-vacuous tests and make contract strength auditable across implementation churn.

## Traceability

- DOCUMENTS → ADR `architecture/adrs/087-contract-locked-development-methodology.md` (ADR-087: Contract-Locked Development Methodology)
- DOCUMENTS → GITHUB_ISSUE `1291` (ADR: contract-locked development methodology)
- DOCUMENTS → ADR `ADR-087` (ADR-087: Contract-Locked Development Methodology)
- IMPLEMENTS → GITHUB_ISSUE `1292` (Oracle battery toolkit: conformance scaffolds, property tests, differential reference models)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/test/java/com/keplerops/groundcontrol/test/oracle/PortImplementation.java` (Java oracle battery port implementation provider)
- IMPLEMENTS → CODE_FILE `backend/src/test/java/com/keplerops/groundcontrol/test/oracle/AbstractPortConformanceSuite.java` (Java port conformance-suite scaffold)
- IMPLEMENTS → CODE_FILE `backend/src/test/java/com/keplerops/groundcontrol/test/oracle/OracleInvariants.java` (Java oracle invariant assertions)
- IMPLEMENTS → CODE_FILE `backend/src/test/java/com/keplerops/groundcontrol/test/oracle/NegativeSuite.java` (Java negative-suite helper)
- IMPLEMENTS → CODE_FILE `backend/src/test/java/com/keplerops/groundcontrol/test/oracle/GoldenCorpus.java` (Java golden/replay corpus helper)
- IMPLEMENTS → CODE_FILE `backend/src/test/java/com/keplerops/groundcontrol/test/oracle/DifferentialOracle.java` (Java differential oracle helper)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/test/oracle/OracleBatteryScaffoldTest.java` (Java oracle battery scaffold self-tests and failure-path proof)
