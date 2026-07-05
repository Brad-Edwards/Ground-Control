package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.quality-gate.v1#/$defs/QualityGateInput}. */
public record QualityGateInput(
        String project, boolean buildPassed, boolean changelogUpdated, boolean clauseMappingComplete) {}
