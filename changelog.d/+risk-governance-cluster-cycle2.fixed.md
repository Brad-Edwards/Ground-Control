### Fixed

- **Risk governance MCP camelCase round-trip (GC-T005 / T006 / T007 /
  T015 cycle-2)**: extended `mcp/ground-control/lib.js::TO_CAMEL` with
  13 new snake_case → camelCase entries—`appetite_statement`,
  `metric_unit`, `yellow_threshold`, `red_threshold`,
  `appetite_profile_id`, `scheduled_start`, `scheduled_end`,
  `scoped_asset_ids`, `approval_metadata`, `measured_at`,
  `risk_assessment_result_id`, `monitored_risk_factors`,
  `update_cadence`. Without these, the wire body for the new lifecycle
  aggregates kept the snake_case form verbatim and Spring's Jackson
  binder silently dropped the fields, so KRIs created via
  `gc_risk_governance` were missing thresholds and the next
  `record_measurement` threw `DomainValidationException`.
- **Reassessment trigger enum surface (GC-T015)**: extended the
  `gc_risk_governance` Zod schema to source `reassessment_triggers[].category`
  and `target_type` from the shared `REASSESSMENT_TRIGGER_CATEGORIES` and
  `REASSESSMENT_TRIGGER_TARGET_TYPES` arrays in `lib.js`, so MCP callers can
  author triggers naming the new categories (`THREAT_CHANGED`,
  `VULNERABILITY_CHANGED`, …, `KRI_BREACH`) and target types
  (`RISK_APPETITE_PROFILE`, `RISK_ASSESSMENT_CAMPAIGN`,
  `KEY_RISK_INDICATOR`, `OBSERVATION`, `THREAT_MODEL`). Mirrored in
  `frontend/src/types/api.ts` and pinned by
  `tools/policy/checks.py::ENUM_CONTRACT_INVENTORY`.
- **KRI direction validation (GC-T007)**: `KeyRiskIndicatorService` now
  rejects any `direction` value outside
  `KeyRiskIndicator.VALID_DIRECTIONS` (`HIGHER_IS_WORSE` /
  `LOWER_IS_WORSE`) at the write boundary. The previous free-form
  String column silently defaulted typos (`LOWER_IS_BETTER`,
  `lower_is_worse`) to the `HIGHER_IS_WORSE` branch, mis-banding
  every subsequent measurement. The aggregate's javadoc has been
  corrected to name the actual constant.
- **Treatment plan reassessment fan-out (GC-T015)**:
  `ReassessmentSignalService.collectFromTreatmentPlan` and
  `addAllAssessmentsForTreatmentPlan` now traverse
  `TreatmentPlan.riskAssessmentResult` so a plan whose directly linked
  RAR lives under a different register record (which the GC-T015 FK
  was added to support) still gets its RAR marked
  `reassessmentRequiredAt` on plan status transitions and on fan-out
  via the asset / control link surfaces.
