---
id: GC-O013
title: "MCP–Backend Write-Contract Drift Gate"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 2
created_at: 2026-06-15T02:51:10.998670Z
updated_at: 2026-06-15T22:30:43.243506Z
---

# GC-O013 — MCP–Backend Write-Contract Drift Gate

## Statement

The system's CI shall enforce that MCP write-tool request-body field allowlists and enum mirrors do not drift from the backend's Springdoc-generated OpenAPI contract.

(a) The gate shall compare, per tool/entity/action, the MCP adapter's exported body-field array (snake_case, normalized to camelCase via the shared TO_CAMEL mapping) against the corresponding OpenAPI request-schema properties, and the MCP enum constant arrays against the OpenAPI enum value sets, using the OpenAPI document generated from the current backend build — not a live deployment, a stale checked-in spec, or Java-source-only parsing.

(b) The gate shall be inventory-driven: each covered contract is one data row naming the MCP body-field array (or shared helper), the OpenAPI operation/request schema, the field-name normalization, the create/update/transition split, and any narrow exclusions (path params, query params, server-populated fields, transition-only fields, read-only fields, MCP control arguments, opaque user maps). Adding the next write tool shall require one inventory row, not a new checker.

(c) Initial coverage shall be the GRC write tools (gc_risk_governance, gc_threat_model, gc_risk_scenario, gc_control, gc_evidence, gc_finding, gc_audit, gc_observation, gc_asset), including the shared link-create body via the single shared helper rather than duplicated per tool.

(d) The gate shall be enforced mechanically as a required CI check and shall not require GC_BASE_URL, bearer tokens, a live service, or argv-visible secrets; it shall read tracked source and in-workspace build artifacts only. Failure messages shall name the tool, the field, and which side diverged, without exposing request bodies, headers, tokens, or stack traces.

(e) Runtime validation ownership (Jackson enum binding, Bean Validation, service validators, GraphTargetResolverService, security, audit, ErrorResponse) shall not change; the gate detects mirror drift only.

## Rationale

Issues #874–#881 (#820 Phase-A) showed that MCP write-tool body/enum drift from the backend contract is a class risk: agents send stale, unknown, or control-plane fields and the divergence is only caught at runtime as 4xx round-trips. ADR-034 established the single-source-of-truth contract for enum mirrors and is amended (2026-06-15) to extend it to write-tool request bodies; this requirement anchors the executable enforcement of that amendment. Enforcement must live in a required CI gate — the tool/CI layer is the trust boundary, and skill/ADR prose is reviewable, not gating — and must compare against the genuinely backend-generated OpenAPI so the mirror can never silently bless a broken allowlist. The inventory seam keeps extending coverage to the rest of the write surface a one-row change. Sibling to the GC-O010 documentation-coverage gate and GC-O012 GRC screening gate. Anchors issue #1106 per the structural-gate planning rule.

## Traceability

- DOCUMENTS → DOCUMENTATION `docs/DEVELOPMENT_WORKFLOW.md` (MCP–Backend Write-Contract Gate (Development Workflow))
- IMPLEMENTS → CONFIG `.github/workflows/ci.yml` (mcp-contract required CI job (gate enforcement))
- IMPLEMENTS → ADR `ADR-034` (ADR-034 amendment: MCP write-tool DTO drift gate contract)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/McpOpenApiContractSpecTest.java` (Springdoc OpenAPI spec capture for the contract gate)
- IMPLEMENTS → GITHUB_ISSUE `1106` (MCP↔OpenAPI write-contract CI test)
- DOCUMENTS → GITHUB_ISSUE `1178` (Extend MCP↔OpenAPI write-contract gate beyond GRC tools)
- TESTS → TEST `mcp/ground-control/openapi-contract.mcp-openapi-write-contract.test.js` (MCP↔OpenAPI contract gate (inventory + bidirectional field/enum assertions))
