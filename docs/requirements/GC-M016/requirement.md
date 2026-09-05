---
id: GC-M016
title: "Evidence Derivation and Temporal State History"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T02:53:19.260265Z
updated_at: 2026-08-02T18:39:44.325053Z
---

# GC-M016 — Evidence Derivation and Temporal State History

## Statement

The system shall support deriving durable evidence and historical assurance conclusions from observations, tests, and attestations without overwriting prior state. Evidence and assessment consumers shall be able to distinguish current observed state, historical observations, and summarized evidence artifacts.

## Rationale

Auditability and continuous monitoring both require the system to preserve what was observed, when it was observed, and how that observation was summarized into evidence or assurance conclusions. Collapsing these layers destroys temporal traceability.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#725` (GC-M016: Evidence Derivation and Temporal State History)
- IMPLEMENTS → ADR `architecture/adrs/045-evidence-derivation-and-temporal-state-history.md` (ADR-045: Evidence Derivation and Temporal State History)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/model/EvidenceArtifact.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/service/EvidenceArtifactService.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/api/evidence/EvidenceArtifactController.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/graph/service/EvidenceArtifactGraphProjectionContributor.java`
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V090__create_evidence_artifact.sql`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/evidence/EvidenceArtifactServiceTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/EvidenceArtifactControllerTest.java`
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/graph/EvidenceArtifactGraphProjectionContributorTest.java`
