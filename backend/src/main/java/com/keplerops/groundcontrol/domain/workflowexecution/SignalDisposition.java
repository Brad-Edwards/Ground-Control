package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Review-cap-boundary disposition (GC-O007): proceed, one over-cap cycle, or escalate. Product-surface
 * mirror of the Temporal-history {@code CapDisposition}; mapped 1:1 by the infrastructure adapter.
 */
public enum SignalDisposition {
    PROCEED,
    ONE_MORE_CYCLE,
    ESCALATE_TO_HUMAN
}
