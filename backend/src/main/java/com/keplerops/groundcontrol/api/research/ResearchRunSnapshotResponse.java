package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** GC-RSCH-N011 — read view of the bounded observability snapshot (ADR-065). */
public record ResearchRunSnapshotResponse(
        UUID runId,
        String projectIdentifier,
        String uid,
        ResearchRunStage currentStage,
        ResearchRunStatus status,
        List<ArtifactReadiness> artifactReadiness,
        List<PendingGate> pendingGates,
        SourceCounts sourceCounts,
        Cost cost,
        LastError lastError) {

    public record ArtifactReadiness(
            ResearchRunStage stage, ResearchArtifactType artifactType, ResearchArtifactReadiness readiness) {}

    public record PendingGate(ResearchGatePoint gatePoint, ResearchRunStage guardedStageExit) {}

    public record SourceCounts(
            int candidateSources, int screenedIncluded, int screenedExcluded, int chartedFullText, int accessGaps) {}

    public record Cost(
            Long budgetTokens,
            Integer budgetWallClockMinutes,
            Long budgetCostUsdMicros,
            long observedTokens,
            long observedCostUsdMicros) {}

    public record LastError(String code, String errorClass, String summary, Instant at) {}

    public static ResearchRunSnapshotResponse from(ResearchRunSnapshot s) {
        return new ResearchRunSnapshotResponse(
                s.runId(),
                s.projectIdentifier(),
                s.uid(),
                s.currentStage(),
                s.status(),
                s.artifactReadiness().stream()
                        .map(r -> new ArtifactReadiness(r.stage(), r.artifactType(), r.readiness()))
                        .toList(),
                s.pendingGates().stream()
                        .map(g -> new PendingGate(g.gatePoint(), g.guardedStageExit()))
                        .toList(),
                new SourceCounts(
                        s.sourceCounts().candidateSources(),
                        s.sourceCounts().screenedIncluded(),
                        s.sourceCounts().screenedExcluded(),
                        s.sourceCounts().chartedFullText(),
                        s.sourceCounts().accessGaps()),
                new Cost(
                        s.cost().budgetTokens(),
                        s.cost().budgetWallClockMinutes(),
                        s.cost().budgetCostUsdMicros(),
                        s.cost().observedTokens(),
                        s.cost().observedCostUsdMicros()),
                s.lastError() == null
                        ? null
                        : new LastError(
                                s.lastError().code(),
                                s.lastError().errorClass(),
                                s.lastError().summary(),
                                s.lastError().at()));
    }
}
