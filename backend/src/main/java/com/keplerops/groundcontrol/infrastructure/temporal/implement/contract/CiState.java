package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Aggregate CI state observed for a pull request (Phase D). */
public enum CiState {
    PENDING,
    SUCCESS,
    FAILURE
}
