package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.api.workflowtelemetry.stream.WorkflowRunStreamHub;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingDisposition;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.GateFindingCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.ImportRunCostCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordWorkflowRunCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowMeasurementService;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowRunFilter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final WorkflowMeasurementService measurementService;
    private final ProjectService projectService;
    private final WorkflowRunStreamHub streamHub;

    public WorkflowRunController(
            WorkflowTelemetryService telemetryService,
            WorkflowMeasurementService measurementService,
            ProjectService projectService,
            WorkflowRunStreamHub streamHub) {
        this.telemetryService = telemetryService;
        this.measurementService = measurementService;
        this.projectService = projectService;
        this.streamHub = streamHub;
    }

    /**
     * Live projection of this project's runs and phase events (issue #1436). Resolves the project
     * before registering, so the connection is scoped exactly as the polling reads above are, and
     * falls through the existing authenticated {@code /api/v1/**} rule — a stream is not an
     * access-control exemption.
     *
     * <p>Delivery is best-effort: the client refetches the REST snapshots on connect and reconnect
     * and reconciles by entity id. The stream reports committed telemetry and cannot advance, retry,
     * or prove the present liveness of a workflow.
     */
    @ApiResponse(
            responseCode = "200",
            description = "Event stream. Each `workflow-run` or `phase-event` frame carries the same JSON"
                    + " projection the corresponding REST read returns; heartbeats are SSE comments with no"
                    + " payload. Delivery is best-effort and may duplicate — reconcile by entity id.",
            content =
                    @Content(
                            mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema =
                                    @Schema(
                                            oneOf = {WorkflowRunResponse.class, PhaseEventResponse.class},
                                            description = "Payload of one named SSE data frame")))
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String project) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        return streamHub.subscribe(projectIdentifier, currentPrincipal());
    }

    /**
     * Principal for the per-principal connection quota, read on the request thread. It is a quota
     * key only — the security chain has already authorized this request, and this value is never
     * treated as authentication on a delivery thread.
     */
    private static String currentPrincipal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            // Security disabled (dev/test profiles): every caller shares one quota bucket.
            return WorkflowRunStreamHub.ANONYMOUS_PRINCIPAL;
        }
        return authentication.getName();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRunResponse recordRun(
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
                request.provenance(),
                request.sourceId(),
                request.stationId(),
                request.stationResult(),
                toFindingCommands(request.findings()));
        return PhaseEventResponse.from(telemetryService.recordPhaseEvent(command));
    }

    /**
     * ADR-090 process variables over a window (issue #1355).
     *
     * <p>Project-scoped through {@code ProjectService}, so it falls under the shared authenticated
     * rule rather than the admin-only cross-project rollup: this reports one project's own line.
     */
    @GetMapping("/measurement")
    public MeasurementAggregateResponse measurement(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        var yields = measurementService.aggregateStationYield(projectIdentifier, from, to);
        var window = measurementService.resolveReportingWindow(from, to);

        var stations = yields.values().stream()
                .map(y -> new MeasurementAggregateResponse.StationYieldRow(
                        y.stationId(),
                        y.firstPassNumerator(),
                        y.firstPassDenominator(),
                        y.firstPassYield(),
                        y.evaluableAttempts(),
                        y.reworkAttempts(),
                        y.unresolvedRuns(),
                        y.iterationsToGreen()))
                .toList();

        var counts = measurementService.aggregateFindingCounts(projectIdentifier, from, to).stream()
                .map(r -> new MeasurementAggregateResponse.FindingCountRow(
                        (String) r[0],
                        (FindingSourceKind) r[1],
                        (String) r[2],
                        (String) r[3],
                        (String) r[4],
                        (FindingDisposition) r[5],
                        (Long) r[6]))
                .toList();

        return new MeasurementAggregateResponse(
                window.from(), window.to(), WorkflowMeasurementService.MEASUREMENT_VERSION, stations, counts);
    }

    /**
     * Move one finding to a terminal disposition (issue #1355).
     *
     * <p>Separate from the detection path on purpose: an emitter observing a finding is not evidence
     * that anything was decided about it, and `wontfix` carries ADR-029's authorization requirement.
     */
    @PostMapping("/findings/{findingId}/disposition")
    public GateFindingResponse recordFindingDisposition(
            @PathVariable UUID findingId,
            @Valid @RequestBody RecordFindingDispositionRequest request,
            @RequestParam(required = false) String project) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        return GateFindingResponse.from(
                measurementService.recordFindingDisposition(findingId, projectIdentifier, request.disposition()));
    }

    /**
     * Map the request's finding batch onto immutable commands.
     *
     * <p>Every finding enters as {@code OPEN}: detection and disposition are different moments, and
     * an emitter observing a finding is not evidence that anything was decided about it. Terminal
     * dispositions arrive only through the dedicated transition endpoint, which can require the
     * ADR-029 authorization a {@code wontfix} needs.
     */
    private static List<GateFindingCommand> toFindingCommands(List<GateFindingRequest> findings) {
        if (findings == null) {
            return null;
        }
        return findings.stream()
                .map(f -> new GateFindingCommand(
                        f.findingKey(),
                        f.sourceKind(),
                        f.sourceId(),
                        f.category(),
                        f.severity(),
                        f.classification(),
                        FindingDisposition.OPEN))
                .toList();
    }

    /**
     * Phase events for one run, oldest first. This is what makes an in-flight run inspectable at
     * event level (issue #1435): the aggregate only reports per-phase hot spots across a window, so
     * before this endpoint there was no way to see which gate a still-open run is sitting in.
     */
    @GetMapping("/{runId}/events")
    public List<PhaseEventResponse> listEvents(
            @PathVariable UUID runId,
            @RequestParam(required = false) String project,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        var projectIdentifier = projectService.requireProjectIdentifier(project);
        return telemetryService.listPhaseEvents(runId, projectIdentifier, limit).stream()
                .map(PhaseEventResponse::from)
                .toList();
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
        if (from == null && to == null) {
            to = Instant.now();
            from = to.minus(WorkflowTelemetryService.DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS);
        }
        return respond(
                new WorkflowRunFilter(from, to, projectIdentifier, repo, workflowType, runtime, outcome, requirement));
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
        if (from == null && to == null) {
            to = Instant.now();
            from = to.minus(WorkflowTelemetryService.DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS);
        }
        return respond(new WorkflowRunFilter(from, to, null, repo, workflowType, runtime, outcome, requirement));
    }

    private WorkflowRunAggregateResponse respond(WorkflowRunFilter filter) {
        return WorkflowRunAggregateResponse.from(telemetryService.aggregate(filter));
    }
}
