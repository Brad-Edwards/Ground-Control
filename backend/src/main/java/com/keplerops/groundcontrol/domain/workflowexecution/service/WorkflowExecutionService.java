package com.keplerops.groundcontrol.domain.workflowexecution.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowexecution.OperatorSignalType;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowControlPort;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionId;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionRef;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionStatus;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionView;
import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowType;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product control surface for workflow executions (GC-O009 phase 3). The single authorization and
 * audit boundary for start / status / signal: it resolves the project through {@link ProjectService},
 * proves every read/signal targets that project via the {@link WorkflowExecutionId} scheme, validates
 * the closed workflow-type and signal catalogs, and then delegates the Temporal work to
 * {@link WorkflowControlPort}.
 *
 * <p>The port is optional at runtime: when {@code groundcontrol.temporal.control.enabled} is off no
 * adapter bean exists and the service reports {@link ServiceUnavailableException} (503) instead of
 * failing to wire, keeping CI/dev bootable without a Temporal server.
 */
@Service
public class WorkflowExecutionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    private final ObjectProvider<WorkflowControlPort> controlPortProvider;
    private final ProjectService projectService;

    public WorkflowExecutionService(
            ObjectProvider<WorkflowControlPort> controlPortProvider, ProjectService projectService) {
        this.controlPortProvider = controlPortProvider;
        this.projectService = projectService;
    }

    /** Start a workflow execution for {@code project}, returning its identity. */
    @Transactional(readOnly = true)
    public WorkflowExecutionRef start(String project, StartRequest request) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        var workflowType = requireSupportedType(request.workflowType());
        if (request.issueNumber() < 1) {
            throw new DomainValidationException("issueNumber must be a positive integer");
        }
        var workflowId = WorkflowExecutionId.forImplement(projectIdentifier, request.issueNumber());
        var command = new StartWorkflowCommand(
                workflowId,
                workflowType,
                projectIdentifier,
                request.issueNumber(),
                request.sonarProjectKey(),
                request.reviewCap(),
                request.requirementUids(),
                request.pollIntervalSeconds());
        log.info(
                "Starting workflow execution project={} type={} issue={} workflowId={}",
                projectIdentifier,
                workflowType,
                request.issueNumber(),
                workflowId);
        return port().start(command);
    }

    /** List the caller project's executions, newest first. */
    @Transactional(readOnly = true)
    public List<WorkflowExecutionView> list(String project, Integer limit) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        var effectiveLimit = clampLimit(limit);
        return port().listForProject(projectIdentifier, effectiveLimit);
    }

    /** Describe one execution owned by the caller project; unknown/cross-project ids resolve to 404. */
    @Transactional(readOnly = true)
    public WorkflowExecutionView get(String project, String workflowId) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        requireOwnedByProject(workflowId, projectIdentifier);
        return port().describe(workflowId).orElseThrow(() -> notFound(workflowId));
    }

    /** Send an operator signal to one execution owned by the caller project. */
    @Transactional(readOnly = true)
    public void signal(String project, String workflowId, SignalRequest request) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        requireOwnedByProject(workflowId, projectIdentifier);
        var command = validatedSignal(request);
        // Confirm the execution exists AND is in a signalable state before sending. Describe returns
        // the same 404 envelope for a missing/cross-project id (callers cannot probe existence across
        // projects), and a closed execution (completed/failed/canceled/terminated/timed-out) is
        // rejected with a stable domain validation error rather than falling through to opaque
        // Temporal closed-workflow behavior. The adapter still translates the race where the workflow
        // closes between describe and signal.
        var port = port();
        var view = port.describe(workflowId).orElseThrow(() -> notFound(workflowId));
        requireSignalable(view.status(), workflowId);
        log.info("Sending {} signal project={} workflowId={}", command.type(), projectIdentifier, workflowId);
        port.signal(workflowId, command);
    }

    private static void requireSignalable(WorkflowExecutionStatus status, String workflowId) {
        // Only an open execution accepts operator signals. PAUSED is open (signals queue); every other
        // status is a closed/terminal or indeterminate state that cannot act on a signal.
        if (status != WorkflowExecutionStatus.RUNNING && status != WorkflowExecutionStatus.PAUSED) {
            throw new DomainValidationException("Cannot signal execution " + workflowId + " in status " + status
                    + "; only running executions accept operator signals");
        }
    }

    private WorkflowControlPort port() {
        var port = controlPortProvider.getIfAvailable();
        if (port == null) {
            throw new ServiceUnavailableException(
                    "Workflow control is not enabled (set groundcontrol.temporal.control.enabled=true)");
        }
        return port;
    }

    private static WorkflowType requireSupportedType(WorkflowType workflowType) {
        if (workflowType == null) {
            throw new DomainValidationException("workflowType is required");
        }
        return workflowType;
    }

    private void requireOwnedByProject(String workflowId, String projectIdentifier) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new DomainValidationException("workflowId is required");
        }
        if (!WorkflowExecutionId.belongsToProject(workflowId, projectIdentifier)) {
            // Do not reveal whether the id exists under another project.
            throw notFound(workflowId);
        }
    }

    private static SendSignalCommand validatedSignal(SignalRequest request) {
        if (request == null || request.type() == null) {
            throw new DomainValidationException("signalType is required");
        }
        switch (request.type()) {
            case CANCEL -> {
                if (isBlank(request.reason())) {
                    throw new DomainValidationException("reason is required for a CANCEL signal");
                }
            }
            case RETRY_FROM -> {
                if (request.retryFromPhase() == null) {
                    throw new DomainValidationException("retryFromPhase is required for a RETRY_FROM signal");
                }
            }
            case REVIEW_CAP_DISPOSITION -> {
                if (request.reviewer() == null || request.disposition() == null) {
                    throw new DomainValidationException(
                            "reviewer and disposition are required for a REVIEW_CAP_DISPOSITION signal");
                }
            }
            default -> throw new DomainValidationException("Unsupported signalType: " + request.type());
        }
        return new SendSignalCommand(
                request.type(), request.reason(), request.retryFromPhase(), request.reviewer(), request.disposition());
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static NotFoundException notFound(String workflowId) {
        return new NotFoundException("Workflow execution not found: " + workflowId);
    }

    /** Service-level start inputs (already parsed/validated at the edge; re-validated here). */
    public record StartRequest(
            WorkflowType workflowType,
            int issueNumber,
            String sonarProjectKey,
            Integer reviewCap,
            List<String> requirementUids,
            Integer pollIntervalSeconds) {}

    /** Service-level signal inputs; only the fields required by {@link SendSignalCommand#type()} are used. */
    public record SignalRequest(
            OperatorSignalType type,
            String reason,
            com.keplerops.groundcontrol.domain.workflowexecution.RetryPhase retryFromPhase,
            com.keplerops.groundcontrol.domain.workflowexecution.Reviewer reviewer,
            com.keplerops.groundcontrol.domain.workflowexecution.SignalDisposition disposition) {}
}
