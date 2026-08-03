---
id: GC-L002
title: "MCP Analysis Tools"
status: ACTIVE
type: INTERFACE
priority: MUST
wave: 1
created_at: 2026-03-13T23:14:44.564890Z
updated_at: 2026-03-18T07:26:49.994394Z
---

# GC-L002 — MCP Analysis Tools

## Statement

The system shall expose all analysis capabilities (cycle detection, orphan detection, impact analysis, coverage gaps, cross-wave validation) as MCP tools with structured results.

## Rationale

Agents need analysis results to make informed decisions about requirement quality. MCP tools must return structured data suitable for agent reasoning, not HTML or human-formatted reports.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (MCP Server - Analysis tools)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (MCP lib — REST API client functions for analysis tools)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService — backend analysis operations backing MCP tools)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest — unit tests for all analysis operations)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (AnalysisIntegrationTest — integration tests for analysis endpoints)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#347` (GC-L002: MCP Analysis Tools)
