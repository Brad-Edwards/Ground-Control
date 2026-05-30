package com.keplerops.groundcontrol.domain.controls.state;

/**
 * FAIR-CAM control-domain attribution per GC-I017.
 *
 * <p>FAIR-CAM partitions controls into three functional domains by where they
 * intervene in the FAIR ontology:
 * <ul>
 *   <li>{@link #LOSS_EVENT_CONTROL} — directly reduces loss event frequency or
 *       loss magnitude (preventive, detective/responsive controls operating on
 *       the event chain itself).</li>
 *   <li>{@link #VARIANCE_MANAGEMENT_CONTROL} — keeps other controls operating
 *       within their designed envelope (governance, monitoring, calibration of
 *       the loss-event controls themselves).</li>
 *   <li>{@link #DECISION_SUPPORT_CONTROL} — improves the quality of risk
 *       decisions by analysts and risk owners (risk analysis, reporting,
 *       executive escalation paths).</li>
 * </ul>
 *
 * <p>This enum is the canonical mirror per ADR-034. Declaration order matches
 * the API DTO, MCP {@code FAIR_CAM_CONTROL_DOMAINS} constant array, and the
 * frontend type alias under {@code frontend/src/types/api.ts}.
 *
 * <p>The FAIR-CAM domain is never collapsed into
 * {@link ControlEffectivenessRating}; the rating remains a separate, narrower
 * effectiveness label per GC-I013, and FAIR-CAM analytics expose
 * capability / coverage / operational-performance dimensions independently.
 */
public enum FairCamControlDomain {
    LOSS_EVENT_CONTROL,
    VARIANCE_MANAGEMENT_CONTROL,
    DECISION_SUPPORT_CONTROL
}
