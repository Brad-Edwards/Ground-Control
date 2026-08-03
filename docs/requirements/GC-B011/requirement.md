---
id: GC-B011
title: "Requirement-Section Membership"
status: ACTIVE
type: CONSTRAINT
priority: MUST
wave: 2
created_at: 2026-03-13T23:11:25.663528Z
updated_at: 2026-03-26T14:45:20.978415Z
---

# GC-B011 — Requirement-Section Membership

## Statement

A requirement shall belong to at most one document section. A requirement may exist outside any document as a standalone entity.

## Rationale

Allowing a requirement in multiple sections creates ambiguity about its canonical location. Standalone requirements support ad-hoc creation before organization.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/SectionContentService.java` (SectionContentService - at-most-one-section validation)
- IMPLEMENTS → CODE_FILE `backend/src/main/resources/db/migration/V022__unique_requirement_per_section.sql` (Partial unique index enforcing at-most-one section per requirement)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/SectionContentServiceTest.java` (SectionContentServiceTest - rejects requirement already in section)
- IMPLEMENTS → GITHUB_ISSUE `413` (GC-B011: Requirement-Section Membership)
