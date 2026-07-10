package com.keplerops.groundcontrol.api.workflowexecution;

import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService.SignalRequest;
import com.keplerops.groundcontrol.domain.workflowexecution.service.WorkflowExecutionService.StartRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product control surface for {@code /implement} Temporal workflows (GC-O009 phase 3, issue #1278):
 * start executions, read execution state from Temporal Visibility + correlation data, and send the
 * closed operator-signal catalog. REST and MCP are the product boundary — Temporal gRPC/Web stay
 * infrastructure (ADR-028).
 *
 * <p>Thin controller: validate DTOs and delegate to {@link WorkflowExecutionService}, which owns
 * project-scope resolution, the authorization boundary, and the Temporal adapter. Signal routes are
 * gated to {@code ROLE_ADMIN} in {@code ApiPathMatrix} (interim until GC-P024 gate authority); start
 * and reads are authenticated + project-scoped in the service.
 */
@RestController
@RequestMapping("/api/v1/workflow-executions")
public class WorkflowExecutionController {

    private final WorkflowExecutionService service;

    public WorkflowExecutionController(WorkflowExecutionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowExecutionStartResponse start(
            @Valid @RequestBody StartWorkflowExecutionRequest request, @RequestParam(required = false) String project) {
        var ref = service.start(
                project,
                new StartRequest(
                        request.workflowType(),
                        request.issueNumber(),
                        request.sonarProjectKey(),
                        request.reviewCap(),
                        request.requirementUids(),
                        request.pollIntervalSeconds()));
        return WorkflowExecutionStartResponse.from(ref);
    }

    @GetMapping
    public List<WorkflowExecutionResponse> list(
            @RequestParam(required = false) String project, @RequestParam(required = false) Integer limit) {
        return service.list(project, limit).stream()
                .map(WorkflowExecutionResponse::from)
                .toList();
    }

    @GetMapping("/{workflowId}")
    public WorkflowExecutionResponse get(
            @PathVariable String workflowId, @RequestParam(required = false) String project) {
        return WorkflowExecutionResponse.from(service.get(project, workflowId));
    }

    @PostMapping("/{workflowId}/signals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void signal(
            @PathVariable String workflowId,
            @Valid @RequestBody SendSignalRequest request,
            @RequestParam(required = false) String project) {
        service.signal(
                project,
                workflowId,
                new SignalRequest(
                        request.signalType(),
                        request.reason(),
                        request.retryFromPhase(),
                        request.reviewer(),
                        request.disposition()));
    }
}
