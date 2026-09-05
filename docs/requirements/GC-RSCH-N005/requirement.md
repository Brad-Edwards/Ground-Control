---
id: GC-RSCH-N005
title: "NFR-5 Security"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 14
created_at: 2026-05-25T22:47:46.861410Z
updated_at: 2026-09-05T20:22:53Z
---

# GC-RSCH-N005 — NFR-5 Security

## Statement

Security: generated code and browser activity shall run with least privilege, sandboxing, scoped credentials, and network/filesystem policy.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Status note

The backend research-run control plane that implemented this requirement was
deleted by the #1500 re-platform
([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)).
The literature-review skill pipeline under `skills/lit-review*/` and the citation
MCP under `mcp/citation/` survive, but they enforce a lighter contract than this
requirement states, so no surviving artifact implements it as written.

The status is `DRAFT` rather than `DEPRECATED`: the research capability is not
retired, and how this requirement should fit the file-and-skill architecture is
an open question to revisit. `DRAFT` is the honest reading, since it says the
requirement is specified but not implemented. The original backend evidence is
preserved under `## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `1008` (Research privacy, security, and prompt-injection controls)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/shared/security/ApiPathMatrix.java` (Admin-gated decision/consume routes (least privilege))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchOperationAuthorizationService.java` (allowedTools inventory (not authorization); bounded records)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/security/ApiSecurityConfigTest.java` (Decision/consume admin-gate security-matrix tests)
