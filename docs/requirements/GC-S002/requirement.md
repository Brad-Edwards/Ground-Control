---
id: GC-S002
title: "IAM Evidence Adapter Specification"
status: ACTIVE
type: INTERFACE
priority: SHOULD
wave: 5
created_at: 2026-03-14T16:56:01.708264Z
updated_at: 2026-06-25T05:15:22.079935Z
---

# GC-S002 — IAM Evidence Adapter Specification

## Statement

The system shall specify an evidence adapter for Identity and Access Management systems (Okta, Azure AD, AWS IAM) capable of collecting: user access reviews, provisioning/deprovisioning events, MFA enrollment status, privileged access reports, and dormant account lists.

## Rationale

IAM evidence is required by nearly every compliance framework (SOC 2 CC6.1, ISO 27001 A.9, SOX ITGC). Agent-collected IAM evidence replaces quarterly manual access review screenshots with continuous, structured evidence.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#211` (GC-S002: IAM Evidence Adapter Specification)
- DOCUMENTS → DOCUMENTATION `docs/architecture/ARCHITECTURE.md` (ARCHITECTURE.md — GC-S002 IAM evidence adapter specification)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/iam/IamEvidenceSpecification.java` (IamEvidenceSpecification — GC-S002 normative IAM adapter spec entry point)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/iam/IamEvidenceProvider.java` (IamEvidenceProvider — GC-S002 IAM provider keys (Okta/Azure AD/AWS IAM))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/evidence/collection/iam/IamEvidenceFamily.java` (IamEvidenceFamily — GC-S002 five evidence families as scope types/schemas)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/IamEvidenceAdapterSpecificationTest.java` (IamEvidenceAdapterSpecificationTest — GC-S002 conformance contract test)
