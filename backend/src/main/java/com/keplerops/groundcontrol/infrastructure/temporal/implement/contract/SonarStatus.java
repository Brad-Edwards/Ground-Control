package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** SonarCloud quality-gate status observed for a pull request (Phase D). {@code NONE} = no analysis configured. */
public enum SonarStatus {
    OK,
    ERROR,
    PENDING,
    NONE
}
