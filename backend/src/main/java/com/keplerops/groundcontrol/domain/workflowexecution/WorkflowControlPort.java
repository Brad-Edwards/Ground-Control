package com.keplerops.groundcontrol.domain.workflowexecution;

import com.keplerops.groundcontrol.domain.workflowexecution.service.SendSignalCommand;
import com.keplerops.groundcontrol.domain.workflowexecution.service.StartWorkflowCommand;
import java.util.List;
import java.util.Optional;

/**
 * Domain port over the workflow execution engine. The single infrastructure adapter
 * ({@code TemporalWorkflowControlAdapter}) implements it against Temporal's client + Visibility; the
 * domain stays Temporal-free (ArchUnit {@code domain_should_not_import_temporal_sdk}).
 *
 * <p>Authorization and project-scope enforcement live in {@link
 * com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService}, not here: by
 * the time a method is called the service has already resolved the project and (for describe/signal)
 * proven the workflow id belongs to it, so the port is a pure executor over well-formed inputs.
 */
public interface WorkflowControlPort {

    /** Start a new execution. Throws a conflict when an execution with the same id is already running. */
    WorkflowExecutionRef start(StartWorkflowCommand command);

    /** List executions owned by {@code project} (exact project-ownership predicate), newest first, capped. */
    List<WorkflowExecutionView> listForProject(String project, int limit);

    /** Describe a single execution by id, or empty when no such execution exists. */
    Optional<WorkflowExecutionView> describe(String workflowId);

    /** Send an operator signal to an existing execution. */
    void signal(String workflowId, SendSignalCommand command);
}
