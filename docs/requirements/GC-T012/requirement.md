---
id: GC-T012
title: "Multi-Framework Risk Terminology Crosswalk"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 4
created_at: 2026-03-30T01:53:34.424029Z
updated_at: 2026-09-05T20:12:24Z
---

# GC-T012 — Multi-Framework Risk Terminology Crosswalk

## Statement

The system shall preserve methodology-specific terminology while exposing a normalized crosswalk between common concepts such as threat source, threat event, vulnerability or exposure, asset, process, or objective, consequence or effect, control, likelihood or frequency, impact or loss magnitude, and treatment. The system shall not collapse method-specific concepts into a single ambiguous field when semantics differ, and assessment outputs shall remain traceable to their originating methodology profile.

## Rationale

Supporting FAIR, NIST, ISO, and related approaches requires epistemic traceability across differing vocabularies. A formal crosswalk prevents the platform from pretending that terms like threat, event, likelihood, frequency, impact, and loss all mean exactly the same thing in every method.

## Retirement

Every artifact that implemented this requirement was deleted by the #1500
re-platform ([ADR-089](../../../architecture/adrs/089-retire-grc-product-and-next-issue-recommendation.md)),
which stripped Ground Control to the MCP server over repo-local files. No
surviving artifact implements it, so the status is `DEPRECATED` rather than
`ACTIVE`: an active requirement with no implementation evidence asserts a
capability the system does not have. The original evidence is preserved under
`## Historical traceability` below (issue #650).

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#719` (GC-T012: Multi-Framework Risk Terminology Crosswalk)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/MethodologyProfile.java` (MethodologyProfile aggregate (crosswalk owner))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/model/CrosswalkEntry.java` (CrosswalkEntry record (C2: source vocabulary, field path, label, scale, units, conversion rule, limitations))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/NormalizedConcept.java` (NormalizedConcept enum (C1: ten cross-framework concepts))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/state/CrosswalkVocabularySurface.java` (CrosswalkVocabularySurface enum (input/output/treatment surfaces))
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/domain/riskscenarios/service/MethodologyProfileService.java` (MethodologyProfileService crosswalk validation + seed (C2/C3/C4/C6))
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V129__add_methodology_profile_crosswalk_entries.sql` (V129 methodology_profile.crosswalk_entries column (C3))
- IMPLEMENTS → CONFIG `backend/src/main/resources/db/migration/V130__add_methodology_profile_crosswalk_entries_audit.sql` (V130 methodology_profile_audit Envers parity column)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/shared/CrosswalkEntryListConverterTest.java` (CrosswalkEntryListConverter round-trip tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/domain/MethodologyProfileServiceTest.java` (MethodologyProfileService seed + validation tests)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/unit/api/MethodologyProfileControllerTest.java` (MethodologyProfileController @WebMvcTest slice tests)
