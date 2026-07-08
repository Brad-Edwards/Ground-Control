package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.sonar-gate.v1#/$defs/SonarGateResult}. */
public record SonarGateResult(SonarStatus status) {}
