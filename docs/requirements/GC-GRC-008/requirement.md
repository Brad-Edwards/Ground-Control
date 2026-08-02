---
id: GC-GRC-008
title: "Control Identification and Mapping Rules"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:25:31.692084Z
updated_at: 2026-07-12T16:35:41.562778Z
---

# GC-GRC-008 — Control Identification and Mapping Rules

## Statement

The system shall deterministically map enumerated threats to candidate controls.

(a) Mapping shall proceed threat category → control objective → candidate controls, drawing candidates from installed control packs (OSCAL catalogs such as NIST SP 800-53/800-218) and the project's existing controls.

(b) Each candidate control shall carry implementation guidance and the rule provenance that selected it.

(c) Confirmed threat→control relationships shall be recorded through the canonical mapping aggregates (ThreatModelLink MITIGATED_BY, RiskControlMapping) so coverage is graph-queryable.

(d) Where no pack control matches a threat category, the gap shall be surfaced explicitly for human/agent control design — never silently dropped.

## Rationale

Control identification is the step that turns threat modeling into secure-by-design action. Driving it from control packs reuses the existing OSCAL import machinery and keeps control selection auditable against recognized frameworks rather than LLM-invented.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `1121` (Issue #1121: GC-GRC-008 control identification and mapping rules)
