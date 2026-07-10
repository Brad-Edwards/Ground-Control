package com.keplerops.groundcontrol.domain.workflowexecution;

/**
 * Closed catalog of product workflow types startable through the control surface (GC-O009 phase 3).
 *
 * <p>This is the product-facing vocabulary and the ADR-034 single source mirrored by the MCP tool
 * and frontend. The infrastructure adapter maps each value to its registered Temporal workflow type
 * name; the domain deliberately stays free of Temporal registration details.
 */
public enum WorkflowType {
    /** The gated {@code /implement} development loop ({@code ImplementWorkflow}). */
    IMPLEMENT
}
