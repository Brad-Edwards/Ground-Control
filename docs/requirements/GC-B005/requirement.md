---
id: GC-B005
title: "Document Grammars"
status: ACTIVE
type: FUNCTIONAL
priority: COULD
wave: 3
created_at: 2026-03-13T23:11:10.883163Z
updated_at: 2026-03-26T08:41:33.339367Z
---

# GC-B005 — Document Grammars

## Statement

The system shall support custom document grammars defining the fields and relation types valid within a document, enabling project-specific requirement schemas.

## Rationale

Different projects and domains have different requirements schemas. StrictDoc supports custom grammars; GC should provide equivalent or better flexibility.

## Traceability

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentGrammar.java` (DocumentGrammar record - grammar definition)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/documents/service/DocumentService.java` (DocumentService - grammar CRUD methods)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/DocumentServiceTest.java` (DocumentServiceTest - grammar set/get/delete tests)
