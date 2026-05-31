package com.keplerops.groundcontrol.domain.riskscenarios.state;

/**
 * GC-T015 (NIST risk response / ongoing monitoring) categorization of what
 * change in the environment caused a reassessment trigger to fire.
 *
 * <p>The TREATMENT/ASSET/CONTROL/ASSESSMENT_REFRESH/METHODOLOGY_SPECIFIC values
 * predate GC-T015 (GC-T004 / C8 #863). The remaining values encode NIST SP
 * 800-30 Rev. 1 §3.4 "Conditions for ongoing risk monitoring" — change in
 * threat, vulnerability, predisposing condition, observation, topology,
 * environment — plus KRI_BREACH for the GC-T007 monitoring loop.
 */
public enum ReassessmentTriggerCategory {
    TREATMENT_PROGRESS_CHANGED,
    ASSET_STATE_CHANGED,
    CONTROL_STATE_CHANGED,
    ASSESSMENT_REFRESH,
    METHODOLOGY_SPECIFIC,
    THREAT_CHANGED,
    VULNERABILITY_CHANGED,
    PREDISPOSING_CONDITION_CHANGED,
    OBSERVATION_CHANGED,
    TOPOLOGY_CHANGED,
    ENVIRONMENT_CHANGED,
    KRI_BREACH
}
