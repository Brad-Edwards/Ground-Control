package com.keplerops.groundcontrol.domain.riskappetite.state;

/**
 * Lifecycle of a {@link com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile}
 * version (GC-T005). A profile is authored as {@code DRAFT}, becomes {@code ACTIVE} once it is the
 * board-approved appetite in force for its effective window, and is {@code RETIRED} when superseded.
 * Only {@code ACTIVE} versions participate in effective-window overlap checks and appetite evaluation.
 */
public enum RiskAppetiteProfileStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
