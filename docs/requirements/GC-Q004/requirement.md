---
id: GC-Q004
title: "Project Health Dashboard"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 2
created_at: 2026-03-14T02:59:49.484574Z
updated_at: 2026-03-20T01:54:48.566531Z
---

# GC-Q004 — Project Health Dashboard

## Statement

The web application shall provide a dashboard view showing aggregate project health: requirement counts by status and wave, traceability coverage percentages, orphan and cycle counts, recent changes, and wave completion progress. All metrics shall link through to their underlying detail views.

## Rationale

Architects and leads need a single landing page that answers 'how healthy is this project?' without running individual analysis queries. Dashboard metrics surface trends and regressions early — a coverage percentage dropping after a refactor is immediately visible on a dashboard but invisible through ad-hoc API queries.

## Traceability

- IMPLEMENTS → CODE_FILE `frontend/src/pages/dashboard.tsx` (Dashboard page - Project health metrics)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/index.js` (gc_dashboard_stats MCP tool)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AnalysisService.java` (AnalysisService.getDashboardStats())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/requirements/service/AuditService.java` (AuditService.getRecentRequirementChanges())
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/admin/AnalysisController.java` (GET /api/v1/analysis/dashboard-stats endpoint)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/AnalysisServiceTest.java` (AnalysisServiceTest.GetDashboardStats unit tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/AnalysisControllerTest.java` (AnalysisControllerTest.GetDashboardStats unit test)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/integration/AnalysisIntegrationTest.java` (dashboardStats_returnsAggregatedMetrics integration test)
- IMPLEMENTS → GITHUB_ISSUE `autarchy-ai/Ground-Control#348` (GC-Q004: Project Health Dashboard)
