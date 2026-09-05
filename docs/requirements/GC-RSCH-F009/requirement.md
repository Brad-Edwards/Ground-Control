---
id: GC-RSCH-F009
title: "FR-9 Support method-specific outputs for scoping reviews, systematic reviews, systematic maps, critical/integrative review..."
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.329074Z
updated_at: 2026-05-26T01:48:12.849369Z
---

# GC-RSCH-F009 — FR-9 Support method-specific outputs for scoping reviews, systematic reviews, systematic maps, critical/integrative review...

## Statement

Support method-specific outputs for scoping reviews, systematic reviews, systematic maps, critical/integrative reviews, targeted related work, and taxonomy development.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1007` (Research protocol planning artifact)
- DOCUMENTS → GITHUB_ISSUE `1032` (Migrate Reactor discipline and artifacts into Ground Control research workflows)
- IMPLEMENTS → CONFIG `skills/lit-review/methodology/catalog.yaml` (methodology catalog — 7 methods (scoping, systematic, mapping, critical, narrative_conceptual, targeted_related_work, taxonomy_development))
- IMPLEMENTS → PULL_REQUEST `1039` (PR #1039 — research workflow skills + citation MCP (merged to dev))

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ProtocolMethodShape.java` (Method-specific protocol output shapes for the six review/taxonomy families (#1007))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunProtocolPlanServiceTest.java` (Method-shape + taxonomy source-role tests (#1007))
