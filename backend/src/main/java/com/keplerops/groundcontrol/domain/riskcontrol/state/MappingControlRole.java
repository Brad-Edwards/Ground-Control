package com.keplerops.groundcontrol.domain.riskcontrol.state;

/**
 * The contextual role a control plays in this specific risk-to-control mapping.
 *
 * <p>Intentionally distinct from {@link com.keplerops.groundcontrol.domain.controls.state.ControlFunction},
 * which is the catalog-level function. The mapping role can differ from the catalog function —
 * e.g. a PREVENTIVE catalog control might be mapped as COMPENSATING in a given scenario context
 * (GC-T003 C3, preflight §Gotchas).
 */
public enum MappingControlRole {
    /** Designed to stop the risk event from occurring. */
    PREVENTIVE,
    /** Designed to identify and alert when the risk event occurs. */
    DETECTIVE,
    /** Designed to restore normal state after the risk event. */
    CORRECTIVE,
    /** Discourages the risk event through threat of consequence. */
    DETERRENT,
    /** Substitutes for a primary control that is absent or weak. */
    COMPENSATING,
    /** Designed to restore normal operations after an incident. */
    RECOVERY,
    /** Mandates or guides required behaviors that reduce the risk. */
    DIRECTIVE
}
