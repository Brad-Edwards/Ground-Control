package com.keplerops.groundcontrol.domain.grcanalysis.service;

/**
 * Ordering modes for the GC-T008 top-N risk projection. Both modes operate on
 * the latest-per-scenario {@code RiskAssessmentResult} row:
 *
 * <ul>
 *   <li>{@code CURRENT_ASSESSMENT_OUTPUT}: rank by the qualitative risk level
 *       string carried in {@code computedOutputs.risk_level} (NIST / ISO
 *       methodologies). FAIR rows surface a per-entry limitation because the
 *       ranking metric is methodology-specific.</li>
 *   <li>{@code ASSESSMENT_AT_DESC}: rank by {@code assessmentAt} timestamp,
 *       most recent first. Methodology-neutral.</li>
 * </ul>
 */
public enum RiskTopNOrderBy {
    CURRENT_ASSESSMENT_OUTPUT,
    ASSESSMENT_AT_DESC
}
