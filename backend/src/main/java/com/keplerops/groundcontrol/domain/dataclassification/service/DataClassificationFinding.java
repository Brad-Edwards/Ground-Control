package com.keplerops.groundcontrol.domain.dataclassification.service;

import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationFindingReason;

/**
 * A single deterministic evaluation finding for one labeled flow (GC-GRC-006): either a policy
 * violation ({@link DataClassificationFindingReason#LABEL_FLOW_NOT_PERMITTED}) or a limitation. It
 * carries only stable keys, label keys, and a reason code — never raw model payloads or data
 * samples — so it is safe to log and serialize.
 */
public record DataClassificationFinding(
        String flowStableKey,
        String sourceStableKey,
        String sinkStableKey,
        String sourceLabelKey,
        String sinkLabelKey,
        DataClassificationFindingReason reason,
        String detail) {}
