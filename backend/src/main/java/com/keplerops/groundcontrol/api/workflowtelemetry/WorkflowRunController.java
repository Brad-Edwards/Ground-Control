package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.ImportRunCostCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordWorkflowRunCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowRunFilter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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
 * Workflow-run telemetry & economics reporting surface (issue #859, ADR-061).
 *
 * <p>Thin controller — depends only on the service + DTOs to respect the {@code api/ -> domain/}
 * ArchUnit boundary. Project-scoped reads/writes resolve through {@link ProjectService} so they
 * cannot read another project's data. The cross-project operator rollup is a dedicated endpoint
 * ({@code GET /cross-project-aggregate}) gated to {@code ROLE_ADMIN} in {@code ApiPathMatrix}: an
 * explicit authorization decision, never an accidental fall-through from a project read.
 */
@RestController
@RequestMapping("/api/v1/workflow-runs")
public class WorkflowRunController {

    private final WorkflowTelemetryService telemetryService;
    private final ProjectService projectService;

    public WorkflowRunController(WorkflowTelemetryService telemetryService, ProjectService projectService) {
        this.telemetryService = telemetryService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRunResponse record(
            @Valid @RequestBody RecordWorkflowRunRequest request, @RequestParam(required = false) String project) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        var command = new RecordWorkflowRunCommand(
                projectIdentifier,
                request.repo(),
                request.issueNumber(),
                request.prNumber(),
                request.branch(),
                request.workflowType(),
                request.runtimeDriver(),
                request.requirementUids(),
                request.startedAt(),
                request.endedAt(),
                request.finalState(),
                request.outcome(),
                request.provenance(),
                request.provider(),
                request.model(),
                request.modelInvocationCount(),
                request.wallClockMinutes(),
                request.costProxy(),
                request.costCurrency(),
                request.tokenUsage());
        return WorkflowRunResponse.from(telemetryService.recordRun(command));
    }

    @PostMapping("/{runId}/events")
    @ResponseStatus(HttpStatus.CREATED)
    public PhaseEventResponse recordEvent(
            @PathVariable UUID runId,
            @Valid @RequestBody RecordPhaseEventRequest request,
            @RequestParam(required = false) String project) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        var command = new RecordPhaseEventCommand(
                runId,
                projectIdentifier,
                request.phase(),
                request.eventType(),
                request.cycleIndex(),
                request.occurredAt(),
                request.durationMs(),
                request.outcome(),
                request.provenance());
        return PhaseEventResponse.from(telemetryService.recordPhaseEvent(command));
    }

    @PostMapping("/{runId}/cost")
    public WorkflowRunResponse importCost(
            @PathVariable UUID runId,
            @Valid @RequestBody ImportRunCostRequest request,
            @RequestParam(required = false) String project) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        var command = new ImportRunCostCommand(
                runId,
                projectIdentifier,
                request.provider(),
                request.model(),
                request.modelInvocationCount(),
                request.wallClockMinutes(),
                request.costProxy(),
                request.costCurrency(),
                request.tokenUsage());
        return WorkflowRunResponse.from(telemetryService.importCost(command));
    }

    @GetMapping
    public List<WorkflowRunResponse> list(
            @RequestParam(required = false) String project,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        return telemetryService.listRuns(projectIdentifier, limit).stream()
                .map(WorkflowRunResponse::from)
                .toList();
    }

    @GetMapping("/aggregate")
    public WorkflowRunAggregateResponse aggregate(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String runtime,
            @RequestParam(required = false) String requirement,
            @RequestParam(required = false) String workflowType,
            @RequestParam(required = false) WorkflowRunOutcome outcome,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        return aggregateScoped(projectIdentifier, repo, runtime, requirement, workflowType, outcome, from, to);
    }

    /**
     * Cross-project operator rollup. Admin-only via {@code ApiPathMatrix}; passes {@code project=null}
     * so the aggregate spans every project.
     */
    @GetMapping("/cross-project-aggregate")
    public WorkflowRunAggregateResponse crossProjectAggregate(
            @RequestParam(required = false) String repo,
            @RequestParam(required = false) String runtime,
            @RequestParam(required = false) String requirement,
            @RequestParam(required = false) String workflowType,
            @RequestParam(required = false) WorkflowRunOutcome outcome,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return aggregateScoped(null, repo, runtime, requirement, workflowType, outcome, from, to);
    }

    private WorkflowRunAggregateResponse aggregateScoped(
            String project,
            String repo,
            String runtime,
            String requirement,
            String workflowType,
            WorkflowRunOutcome outcome,
            Instant from,
            Instant to) {
        if (from == null && to == null) {
            to = Instant.now();
            from = to.minus(WorkflowTelemetryService.DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS);
        }
        var filter = new WorkflowRunFilter(from, to, project, repo, workflowType, runtime, outcome, requirement);
        return WorkflowRunAggregateResponse.from(telemetryService.aggregate(filter));
    }
}
