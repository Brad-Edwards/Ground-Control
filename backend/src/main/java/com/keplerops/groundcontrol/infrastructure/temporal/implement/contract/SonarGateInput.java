package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.sonar-gate.v1#/$defs/SonarGateInput}. */
public record SonarGateInput(String projectKey, int prNumber) {}
