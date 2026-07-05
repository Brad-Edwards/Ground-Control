package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Pull-request lifecycle state observed from GitHub (Phase D/E merge observation). */
public enum PrState {
    OPEN,
    MERGED,
    CLOSED
}
