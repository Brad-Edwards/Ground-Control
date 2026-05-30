package com.keplerops.groundcontrol.domain.interchange.state;

/**
 * Discriminator for {@code GrcInterchangeProvenance}: the entity kind whose
 * imported timestamps and source-system attribution this record shadows.
 */
public enum InterchangeEntityKind {
    OPERATIONAL_ASSET,
    RISK_SCENARIO,
    CONTROL,
    FINDING,
    EVIDENCE_ARTIFACT
}
