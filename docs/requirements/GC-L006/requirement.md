---
id: GC-L006
title: "MCP GRC Entity CRUD"
status: ACTIVE
type: INTERFACE
priority: MUST
wave: 4
created_at: 2026-03-14T16:56:30.207725Z
updated_at: 2026-05-18T07:06:19.105746Z
---

# GC-L006 — MCP GRC Entity CRUD

## Statement

The system shall expose full CRUD operations for graph-native GRC domain entities including risks (risk scenarios, risk register records, risk assessment results), controls (control definitions, control tests, control effectiveness assessments), findings (with finding links), remediation plans (risk treatment plans), evidence artifacts (append-only per ADR-045), observations, operational assets (including third-party records modeled as AssetType.THIRD_PARTY), and topology relationships, as MCP tools with structured inputs and outputs, maintaining feature parity with the REST API. The MCP surface shall follow the consolidated, action-discriminated style established by ADR-035 and shall route ad-hoc reads through gc_query when no curated read action exists. Future first-class aggregates that are not yet present in the REST API are tracked as separate requirements and are explicitly out of scope here: third-party / vendor management as a first-class aggregate is tracked in GC-L009, questionnaire definitions and responses in GC-L010, and a universal compliance-framework-mapping aggregate in GC-L011. The asset, observation, topology, and evidence portions of this scope are also covered by GC-L008 (Asset and Observation MCP Operations); the two requirements deliberately overlap on that substrate.

## Rationale

For agentic GRC, every graph-native domain entity that has a backend REST aggregate must be operable via MCP, including the asset and observation substrate that risk and control workflows depend on. The original statement enumerated three terms (third parties, questionnaires, compliance framework mappings) that do not have a first-class REST aggregate in the current codebase; the codex architecture preflight (architecture/notes/mcp-grc-entity-crud-preflight.md) ruled that MCP cannot satisfy feature parity against a non-existent REST aggregate, and forbade faking them at the MCP layer by tunneling data through generic metadata fields. Those three terms have been carved out into GC-L009, GC-L010, and GC-L011 so this requirement reflects what was actually delivered (eight MCP tools covering the eight remaining entity categories via the gc_asset, gc_observation, gc_control, gc_finding, gc_evidence, gc_risk_scenario, and gc_risk_governance consolidated tools, plus gc_query for reads).

## Traceability

- DOCUMENTS → DOCUMENTATION `architecture/notes/mcp-grc-entity-crud-preflight.md` (MCP GRC Entity CRUD preflight note)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-risk-scenario.js` (gc_risk_scenario MCP tool)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-risk-governance.js` (gc_risk_governance MCP tool (treatment plans, risk register, assessments))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-control.js` (gc_control MCP tool)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-finding.js` (gc_finding MCP tool)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-evidence.js` (gc_evidence MCP tool (append-only per ADR-045))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/gc-query.js` (gc_query read-only escape hatch (ADR-035))
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP server entrypoint (gc_asset, gc_observation registrations + 44-tool catalog))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/GraphTargetResolverService.java` (GraphTargetResolverService — EVIDENCE projection alignment)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V104__migrate_legacy_evidence_links.sql` (V104 legacy EVIDENCE link migration)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/GraphTargetResolverServiceTest.java` (GraphTargetResolverServiceTest — EVIDENCE projection rejects-missing pins)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AssetGraphProjectionContributorTest.java` (AssetGraphProjectionContributorTest — emits EVIDENCE_ARTIFACT edge pin)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/RiskGraphProjectionContributorTest.java` (RiskGraphProjectionContributorTest — emits EVIDENCE_ARTIFACT edge pin)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ControlGraphProjectionContributorTest.java` (ControlGraphProjectionContributorTest — EVIDENCE → EVIDENCE_ARTIFACT mapping)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/ThreatModelGraphProjectionContributorTest.java` (ThreatModelGraphProjectionContributorTest — emits EVIDENCE_ARTIFACT edge pin)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FindingGraphProjectionContributorTest.java` (FindingGraphProjectionContributorTest — emits EVIDENCE_ARTIFACT edge pin)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/FindingLinkServiceTest.java` (FindingLinkServiceTest — internal EVIDENCE create + external OPERATIONAL_ARTIFACT)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/FindingLinkControllerTest.java` (FindingLinkControllerTest — internal EVIDENCE controller contract)
- TESTS → TEST `mcp/ground-control/gc-risk-scenario.test.js` (gc_risk_scenario MCP adapter tests)
- TESTS → TEST `mcp/ground-control/gc-control.test.js` (gc_control MCP adapter tests)
- TESTS → TEST `mcp/ground-control/gc-finding.test.js` (gc_finding MCP adapter tests)
- TESTS → TEST `mcp/ground-control/gc-evidence.test.js` (gc_evidence MCP adapter tests)
- TESTS → TEST `mcp/ground-control/gc-risk-governance.test.js` (gc_risk_governance MCP adapter tests)
- TESTS → TEST `mcp/ground-control/gc-query.test.js` (gc_query MCP adapter tests)
- IMPLEMENTS → GITHUB_ISSUE `#218` (GC-L006: MCP GRC Entity CRUD)
- TESTS → TEST `mcp/ground-control/lib.tosnakecase.test.js` (MCP transport/lib tests (error envelope, snake↔camel conversion))
