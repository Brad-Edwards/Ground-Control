package com.keplerops.groundcontrol.infrastructure.temporal.control;

import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowControlPort;
import com.keplerops.groundcontrol.infrastructure.temporal.TemporalWorkerProperties;
import io.temporal.client.WorkflowClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Temporal-backed {@link WorkflowControlPort} when
 * {@code groundcontrol.temporal.control.enabled=true}. Reuses the {@link WorkflowClient} created by
 * {@code TemporalWorkerConfiguration} (the shared Temporal connection), so the worker must also be
 * enabled. When this is off, no {@code WorkflowControlPort} bean exists and
 * {@code WorkflowExecutionService} reports 503 — keeping the app bootable without a Temporal server.
 */
@Configuration
@EnableConfigurationProperties(TemporalControlProperties.class)
@ConditionalOnProperty(prefix = "groundcontrol.temporal.control", name = "enabled", havingValue = "true")
class TemporalControlConfiguration {

    @Bean
    WorkflowControlPort temporalWorkflowControlAdapter(
            WorkflowClient workflowClient,
            TemporalControlProperties properties,
            TemporalWorkerProperties workerProperties) {
        // Share the worker's task queue unless the control surface deliberately overrides it, so
        // started ImplementWorkflow executions land on the queue the registered worker polls.
        var taskQueue = properties.taskQueue() != null ? properties.taskQueue() : workerProperties.taskQueue();
        var resolved =
                new TemporalControlProperties(properties.enabled(), taskQueue, properties.defaultCompletionCommand());
        return new TemporalWorkflowControlAdapter(workflowClient, resolved);
    }
}
