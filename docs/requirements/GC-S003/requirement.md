---
id: GC-S003
title: "Cloud Infrastructure Evidence Adapter Specification"
status: ACTIVE
type: INTERFACE
priority: SHOULD
wave: 5
created_at: 2026-03-14T16:56:04.810799Z
updated_at: 2026-06-25T21:34:13.706453Z
---

# GC-S003 — Cloud Infrastructure Evidence Adapter Specification

## Statement

The system shall specify an evidence adapter for cloud infrastructure platforms (AWS, Azure, GCP) capable of collecting: security group configurations, encryption-at-rest status, logging configurations, backup policies, and compliance scan results (AWS Config, Azure Policy, GCP Security Command Center).

## Rationale

Cloud infrastructure is the dominant attack surface for modern organizations. Agent-collected cloud evidence enables continuous compliance monitoring rather than periodic manual screenshots of console configurations.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#212` (GC-S003: Cloud Infrastructure Evidence Adapter Specification)
- DOCUMENTS → DOCUMENTATION `docs/architecture/ARCHITECTURE.md` (ARCHITECTURE.md — GC-S003 cloud infrastructure evidence adapter specification)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/cloud/CloudEvidenceSpecification.java` (CloudEvidenceSpecification — GC-S003 normative cloud adapter spec entry point)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/cloud/CloudEvidenceProvider.java` (CloudEvidenceProvider — GC-S003 cloud provider keys (AWS/Azure/GCP))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/cloud/CloudEvidenceFamily.java` (CloudEvidenceFamily — GC-S003 five evidence families as scope types/schemas)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceFamilySpec.java` (EvidenceFamilySpec — shared family data carrier (schema-id/capability derivation))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/EvidenceFamilyDescriptor.java` (EvidenceFamilyDescriptor — shared family accessor contract + scope-type resolver)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/CloudEvidenceAdapterSpecificationTest.java` (CloudEvidenceAdapterSpecificationTest — GC-S003 conformance contract test)
