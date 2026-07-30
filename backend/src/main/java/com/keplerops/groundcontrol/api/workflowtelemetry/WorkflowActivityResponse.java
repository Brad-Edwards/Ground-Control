package com.keplerops.groundcontrol.api.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowActivitySnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Project-scoped, bounded snapshot for the Live Activity workspace (issue #1437). */
public record WorkflowActivityResponse(
        Instant asOf,
        long openRunTotal,
        boolean openRunsTruncated,
        List<OpenRunResponse> openRuns,
        List<RunSummaryResponse> recentlyFinished) {

    public static WorkflowActivityResponse from(WorkflowActivitySnapshot snapshot) {
        return new WorkflowActivityResponse(
                snapshot.asOf(),
                snapshot.openRunTotal(),
                snapshot.openRunsTruncated(),
                snapshot.openRuns().stream().map(OpenRunResponse::from).toList(),
                snapshot.recentlyFinished().stream()
                        .map(RunSummaryResponse::from)
                        .toList());
    }

    public record OpenRunResponse(
            RunSummaryResponse run,
            @Schema(nullable = true) String currentPhase,
            @Schema(nullable = true) String currentPhaseTitle,
            @Schema(nullable = true) Instant currentPhaseSince,
            @Schema(nullable = true) Integer currentCycle,
            long stallThresholdMs,
            @Schema(nullable = true) RoutingObservationResponse routing,
            List<GateAttemptResponse> gates) {

        static OpenRunResponse from(WorkflowActivitySnapshot.OpenRun row) {
            return new OpenRunResponse(
                    RunSummaryResponse.from(row.run()),
                    row.currentPhase(),
                    row.currentPhaseTitle(),
                    row.currentPhaseSince(),
                    row.currentCycle(),
                    row.stallThreshold().toMillis(),
                    row.routing() == null ? null : RoutingObservationResponse.from(row.routing()),
                    row.gates().stream().map(GateAttemptResponse::from).toList());
        }
    }

    /** Correlation and economics fields needed by the operations bands, without lazy child graphs. */
    public record RunSummaryResponse(
            java.util.UUID id,
            String project,
            @Schema(nullable = true) String repo,
            @Schema(nullable = true) Integer issueNumber,
            @Schema(nullable = true) Integer prNumber,
            @Schema(nullable = true) String branch,
            String workflowType,
            @Schema(nullable = true) String runtimeDriver,
            @Schema(nullable = true) Instant startedAt,
            @Schema(nullable = true) Instant endedAt,
            WorkflowRunState finalState,
            WorkflowRunOutcome outcome,
            @Schema(nullable = true) BigDecimal costProxy,
            @Schema(nullable = true) String costCurrency,
            @Schema(nullable = true) Long tokenUsage) {

        static RunSummaryResponse from(WorkflowRun run) {
            return new RunSummaryResponse(
                    run.getId(),
                    run.getProject(),
                    run.getRepo(),
                    run.getIssueNumber(),
                    run.getPrNumber(),
                    run.getBranch(),
                    run.getWorkflowType(),
                    run.getRuntimeDriver(),
                    run.getStartedAt(),
                    run.getEndedAt(),
                    run.getFinalState(),
                    run.getOutcome(),
                    run.getCostProxy(),
                    run.getCostCurrency(),
                    run.getTokenUsage());
        }
    }

    public record RoutingObservationResponse(
            String stage,
            @Schema(nullable = true) String stepAlias,
            @Schema(nullable = true) CapabilityTier tier,
            @Schema(nullable = true) String model,
            @Schema(nullable = true) String expectedModel,
            @Schema(nullable = true) Boolean modelMatchesExpected,
            Instant occurredAt) {

        static RoutingObservationResponse from(WorkflowActivitySnapshot.RoutingObservation routing) {
            return new RoutingObservationResponse(
                    routing.stage(),
                    routing.stepAlias(),
                    routing.tier(),
                    routing.model(),
                    routing.expectedModel(),
                    routing.modelMatchesExpected(),
                    routing.occurredAt());
        }
    }

    public record GateAttemptResponse(
            String stationId,
            String stationTitle,
            @Schema(nullable = true) PhaseEventType eventType,
            StationResult stationResult,
            @Schema(nullable = true) Integer cycleIndex,
            @Schema(nullable = true) Instant occurredAt,
            @Schema(nullable = true) Long durationMs,
            long findingCount,
            int findingsDropped) {

        static GateAttemptResponse from(WorkflowActivitySnapshot.GateAttempt gate) {
            return new GateAttemptResponse(
                    gate.stationId(),
                    gate.stationTitle(),
                    gate.eventType(),
                    gate.stationResult(),
                    gate.cycleIndex(),
                    gate.occurredAt(),
                    gate.durationMs(),
                    gate.findingCount(),
                    gate.findingsDropped());
        }
    }
}
