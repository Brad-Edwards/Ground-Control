---
id: GC-RSCH-R002
title: "R-2 Never treat model memory as scientific evidence"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 12
created_at: 2026-05-25T22:47:46.068243Z
updated_at: 2026-07-02T17:41:58.445704Z
---

# GC-RSCH-R002 — R-2 Never treat model memory as scientific evidence

## Statement

The system shall never treat model memory as scientific evidence. Claims require source evidence, experiment artifacts, or explicit unsupported/inferred labeling.

## Rationale

Captured from Reactor auto-research requirements analysis (/home/atomik/src/reactor/notes/auto-research-requirements-and-oss-assessment.md). Research is intended to become a Ground Control project type; implementation requires ADR work before code changes.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/ContractEntryKind.java` (ContractEntryKind.requiresSourceGrounding — claims (REQUIREMENT/METHOD_LIMIT/NON_CLAIM) require source evidence (R-2))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/model/MethodologyRequirementsContractEntrySourceLink.java` (Entry→READ methodology source grounding: no claim without source evidence (R-2))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/research/service/ResearchRunService.java` (ResearchRunService validation rejects ungrounded contract claims (R-2))
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/research/ResearchRunMethodologyContractServiceTest.java` (Contract service tests: ungrounded-claim rejection / source-link completeness (R-2))
- DOCUMENTS → GITHUB_ISSUE `1006` (Research methodology requirements artifact)
