package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Version identity of the closed operator-signal contract (GC-O009 (b)). Every operator signal the
 * control surface accepts is versioned by this constant, which is stamped onto each
 * {@link com.keplerops.groundcontrol.domain.workflowexecution.audit.OperatorSignalAudit} row so the
 * audit trail records which contract version authorized the action.
 *
 * <p>The value is the {@code $id} of {@code contracts/schemas/workflow/implement-signals.v1.schema.json}
 * — the single source the JSON schema, the {@link OperatorSignalType} enum, and the MCP tool's signal
 * catalog all mirror. A backward-incompatible change to signal payload semantics requires a new
 * versioned schema and a bump here; the gate-set policy check keeps the catalogs from drifting.
 */
public final class OperatorSignalContract {

    /** Contract version identifier for the current closed operator-signal catalog. */
    public static final String VERSION = "gc.workflow.implement-signals.v1";

    private OperatorSignalContract() {}
}
