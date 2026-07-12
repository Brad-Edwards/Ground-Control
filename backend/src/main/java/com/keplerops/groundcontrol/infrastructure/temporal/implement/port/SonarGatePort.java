package com.keplerops.groundcontrol.infrastructure.temporal.implement.port;

import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.SonarStatus;

/**
 * Infrastructure port for reading the SonarCloud quality-gate status of a pull request (Phase D).
 * Interface only in phase 2 (issue #1277); the SonarCloud adapter lands with the control-surface phase.
 */
public interface SonarGatePort {

    /** Fetch the SonarCloud quality-gate status for the PR; {@link SonarStatus#NONE} when unconfigured. */
    SonarStatus fetchQualityGate(String projectKey, int prNumber);
}
