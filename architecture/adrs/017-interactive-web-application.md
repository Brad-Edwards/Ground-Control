# ADR-017: Interactive Web Application

## Status

Proposed

## Date

2026-03-14

## Context

Ground Control is currently a headless system with two interfaces: a REST API (for programmatic access) and MCP tools (for AI agents). Both are effective for their target users, but human users - architects reviewing traceability, developers checking coverage, leads planning waves - lack a visual interface.

The absence of a UI forces humans to interact with Ground Control through:

- **curl/Postman** - Functional but hostile for browsing, filtering, and cross-referencing
- **MCP via AI agents** - Effective for targeted queries but poor for exploratory analysis and pattern recognition
- **Raw database queries** - Dangerous and unsustainable

Key use cases that demand a visual interface:

1. **Traceability audit** - "Is every ACTIVE requirement implemented and tested?" requires a matrix view, not scrolling through JSON arrays
2. **Wave planning** - Seeing 80+ requirements organized by wave, status, and priority simultaneously, not 20-item paginated API responses
3. **Dependency understanding** - The requirement DAG is inherently spatial; cycles, clusters, and critical paths are visual patterns
4. **Health monitoring** - Coverage percentages, orphan counts, and wave progress are dashboard metrics, not individual API calls
5. **Change investigation** - "What changed since last week?" needs a timeline with diffs, not audit table queries

### Constraints

- Must consume the existing REST API - no direct database access from the frontend
- Must work with project scoping (ADR-016) - all views are project-contextualized
- Must not require a separate deployment for simple use cases (embedded or co-deployed preferred)
- The backend team (currently one developer) must be able to build and maintain it - framework choice must optimize for productivity, not team scaling

## Decision

### 1. Technology: React + TypeScript SPA

Build a single-page application using React 19 with TypeScript, bundled with Vite:

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Framework | React 19 | Dominant ecosystem, extensive component libraries, strong TypeScript support |
| Language | TypeScript 5.x | Type safety at the UI boundary; catches API contract drift at compile time |
| Bundler | Vite 6.x | Fast dev server, simple config, production-ready output |
| Routing | React Router 7.x | Standard SPA routing |
| State | TanStack Query 5.x | Server state management with caching, deduplication, background refresh - avoids manual fetch/cache boilerplate |
| Tables | TanStack Table 8.x | Headless table library; handles sorting, filtering, pagination without opinionated UI |
| Charts | Recharts 2.x | Lightweight, composable, React-native charting for dashboard metrics |
| Graph viz (structured) | React Flow 12.x | Explicit DAG layouts with hierarchy, custom nodes - for requirement detail local neighborhood view |
| Graph viz (exploratory) | Sigma.js + Graphology | Force-directed graph exploration with graph algorithms, filtering, and search - for the `/graph` whole-graph view |
| Styling | Tailwind CSS 4.x | Utility-first CSS; fast iteration without maintaining a separate stylesheet |
| Component lib | shadcn/ui | Copy-paste components built on Radix primitives; no library lock-in, fully customizable |

### 2. Architecture: API-Driven SPA

The frontend is a pure consumer of the REST API:

```
Browser ──► SPA (React) ──► REST API ──► Spring Boot Backend
                                │
                         (same API that MCP tools use)
```

- **No BFF (Backend for Frontend)** - The REST API already returns the data shapes the UI needs. Adding a BFF layer doubles the API surface for no gain at this scale.
- **TanStack Query as the data layer** - Every API call goes through TanStack Query, which handles caching, refetching, optimistic updates, and loading/error states. No Redux, no manual state management for server data.
- **OpenAPI-generated types** - Generate TypeScript types from the Spring Boot OpenAPI spec (via `openapi-typescript`). This ensures the UI and API stay in sync at compile time.

### 3. Deployment: Embedded Static Assets

For simplicity, the built SPA assets are served by Spring Boot as static resources:

```
backend/src/main/resources/static/   ← Vite build output
```

A Gradle task runs `npm run build` and copies the output to `resources/static/`. The Spring Boot app serves the SPA at `/` and the API at `/api/v1/`. A single JAR, single deployment.

For development, Vite's dev server proxies `/api` requests to Spring Boot (port 8080), enabling hot reload without rebuilding the backend.

**Future option**: If the frontend grows or needs CDN distribution, it can be split into a separate deployment behind the same domain. The architecture supports this without code changes - only deployment topology changes.

### 4. Core Views

Six views, mapped to requirements GC-Q001 through GC-Q006:

| View | Route | Primary purpose | Key components |
|------|-------|----------------|----------------|
| **Dashboard** | `/` | Project health at a glance | Status/wave breakdown charts, coverage gauge, orphan/cycle counts, recent activity feed |
| **Explorer** | `/requirements` | Browse, filter, author requirements | Data table with inline filters, detail panel, create/edit forms, status transition actions |
| **Requirement Detail** | `/requirements/:id` | Deep dive on one requirement | All fields, relations graph (local neighborhood), traceability links, audit history |
| **Traceability Matrix** | `/traceability` | Audit coverage | Requirements x artifacts grid, color-coded by link type, gap highlighting, export to CSV |
| **Dependency Graph** | `/graph` | Visualize the DAG | Sigma.js + Graphology canvas for whole-graph exploration; React Flow for structured local neighborhood views. Node coloring by status/wave, edge coloring by relation type, click-to-detail |
| **Audit Timeline** | `/audit` | Change investigation | Chronological event list, filterable by requirement/change type/date, field-level diffs |

### 5. Project Context

All views are scoped to a single project (per ADR-016). A project selector in the navigation bar sets the active project. The selection persists in the URL (for example, `/projects/ground-control/requirements`) and local storage.

When only one project exists, the selector is hidden and the project is implicit.

## Consequences

### Positive

- Architects get a traceability matrix and dependency graph - the two views that are effectively impossible through API calls alone
- Wave planning becomes visual - seeing all requirements by status/wave simultaneously enables better prioritization
- Onboarding improves - new team members can explore requirements without learning API endpoints or MCP tools
- The dashboard creates accountability - coverage gaps and orphans are visible to everyone, not just whoever remembers to run the analysis
- Embedded deployment keeps operations simple - one JAR, one port, one process

### Negative

- Frontend adds a second technology stack (React/TypeScript) to a Java backend codebase - increases the skill surface
- UI testing (component tests, E2E tests) adds another test layer to maintain
- The UI will lag behind API features unless discipline is maintained on GC-A012 (Dual API Exposure) parity
- Embedded deployment couples frontend build to backend build - CI time increases

### Risks

| Risk | Mitigation |
|------|-----------|
| Single developer maintaining both backend and frontend | shadcn/ui and TanStack reduce boilerplate significantly. The UI is a read-heavy consumer of an existing API, not a complex interactive application. |
| Two graph libraries increases bundle size | Tree-shake each library to its respective route via code splitting; Sigma.js is ~40KB gzipped |
| OpenAPI type generation drifts from actual API | Add CI step that regenerates types and fails if uncommitted changes exist. |
| Tailwind CSS generates large stylesheets | Vite's production build tree-shakes unused utilities. Monitor bundle size in CI. |
| SPA routing conflicts with Spring Boot | Configure Spring Boot to forward all non-API, non-static routes to `index.html` (standard SPA fallback). |

## Implementation Notes

### Dependency Graph Prototype (2026-03-14)

A prototype of the dependency graph view (GC-Q005) was built using Cytoscape.js + dagre layout as a single static HTML file served via nginx container. It successfully renders the full Ground Control requirement set (170+ nodes, 100+ edges) with interactive pan/zoom, node selection with neighborhood highlighting, multi-dimensional color coding (series, priority, status, wave), and switchable DAG layouts. This validates that lightweight browser-based graph rendering is sufficient for the dependency graph view and that React Flow (proposed above) is not strictly required - Cytoscape.js with dagre provides equivalent capability with zero build tooling. See `tools/roadmap-viewer/index.html`.

## Related ADRs

- [ADR-011](011-requirements-data-model.md) - Data model the UI visualizes
- [ADR-013](013-java-spring-boot-rewrite.md) - Backend stack the UI consumes
- [ADR-016](016-project-scoping.md) - Project scoping drives the project selector and view filtering

## Related Requirements

- GC-Q001 (Interactive Web Application)
- GC-Q002 (Requirements Explorer)
- GC-Q003 (Traceability Matrix)
- GC-Q004 (Project Health Dashboard)
- GC-Q005 (Interactive Dependency Graph)
- GC-Q006 (Audit History Timeline)
