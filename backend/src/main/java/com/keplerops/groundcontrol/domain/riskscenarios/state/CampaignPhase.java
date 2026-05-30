package com.keplerops.groundcontrol.domain.riskscenarios.state;

import java.util.Set;

/**
 * GC-T006: Risk assessment campaign phase state machine.
 *
 * <p>Phases run in a structured order: PLANNING → IDENTIFICATION → ANALYSIS →
 * EVALUATION → TREATMENT → CLOSED. PLANNING may abort to CLOSED if the campaign
 * is cancelled before scoping. Any phase may move forward by one step; backward
 * transitions are not supported (re-open as a new campaign instead).
 *
 * <p>The methodology profile becomes binding once the campaign leaves ANALYSIS
 * — moving past ANALYSIS without a bound methodologyProfileId is rejected by
 * {@code RiskAssessmentCampaign.advanceTo(...)}.
 */
public enum CampaignPhase {
    PLANNING,
    IDENTIFICATION,
    ANALYSIS,
    EVALUATION,
    TREATMENT,
    CLOSED;

    public Set<CampaignPhase> validTargets() {
        return switch (this) {
            case PLANNING -> Set.of(IDENTIFICATION, CLOSED);
            case IDENTIFICATION -> Set.of(ANALYSIS, CLOSED);
            case ANALYSIS -> Set.of(EVALUATION, CLOSED);
            case EVALUATION -> Set.of(TREATMENT, CLOSED);
            case TREATMENT -> Set.of(CLOSED);
            case CLOSED -> Set.of();
        };
    }

    public boolean canTransitionTo(CampaignPhase target) {
        return target != null && validTargets().contains(target);
    }

    /**
     * Phases at or beyond which the methodology profile is locked in.
     * Once a campaign reaches EVALUATION the bound methodology profile
     * cannot be swapped — re-running with a different methodology is a
     * new campaign (preserves audit trail per GC-T006).
     *
     * <p>CLOSED is intentionally excluded: a campaign can be cancelled from
     * PLANNING straight to CLOSED without ever binding a methodology profile.
     * The lock applies to the live working phases only.
     */
    public boolean methodologyLocked() {
        return this == EVALUATION || this == TREATMENT;
    }
}
