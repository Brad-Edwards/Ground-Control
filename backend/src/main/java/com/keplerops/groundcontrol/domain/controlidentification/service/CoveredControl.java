package com.keplerops.groundcontrol.domain.controlidentification.service;

import java.util.UUID;

/**
 * A single control that covers a threat, together with which canonical mapping edges record the
 * coverage (GC-GRC-008 acceptance: "which controls cover threat X"). A control may be recorded via the
 * {@code RiskControlMapping} coverage edge, the {@code ThreatModelLink MITIGATED_BY} traversal edge, or
 * both.
 */
public record CoveredControl(
        UUID controlId, String controlUid, String title, boolean viaRiskControlMapping, boolean viaThreatModelLink) {}
