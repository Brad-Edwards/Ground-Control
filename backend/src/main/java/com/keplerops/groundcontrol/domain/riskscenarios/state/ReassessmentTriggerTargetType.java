package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * GC-T015 extension: target surfaces a reassessment trigger may point at.
 *
 * <p>Original surface (GC-T004 / C8 #863): ASSET, CONTROL, RISK_SCENARIO,
 * RISK_REGISTER_RECORD, RISK_ASSESSMENT_RESULT, TREATMENT_PLAN, EXTERNAL.
 *
 * <p>GC-T005 / T006 / T007 additions: RISK_APPETITE_PROFILE,
 * RISK_ASSESSMENT_CAMPAIGN, KEY_RISK_INDICATOR, OBSERVATION, THREAT_MODEL —
 * so a reassessment trigger emitted by a KRI breach (GC-T007), campaign phase
 * advance (GC-T006), or observation change (GC-T015) can name its source.
 */
public enum ReassessmentTriggerTargetType {
    ASSET,
    CONTROL,
    RISK_SCENARIO,
    RISK_REGISTER_RECORD,
    RISK_ASSESSMENT_RESULT,
    TREATMENT_PLAN,
    RISK_APPETITE_PROFILE,
    RISK_ASSESSMENT_CAMPAIGN,
    KEY_RISK_INDICATOR,
    OBSERVATION,
    THREAT_MODEL,
    EXTERNAL
}
