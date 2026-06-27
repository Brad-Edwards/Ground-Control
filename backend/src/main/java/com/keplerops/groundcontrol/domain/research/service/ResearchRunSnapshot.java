package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactReadiness;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchGatePoint;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GC-RSCH-N011 / ADR-065 — bounded run-status read snapshot composed entirely
 * from persisted research-domain state. It is a view, never an executor; it
 * cannot advance a run and carries only bounded low-cardinality fields.
 */
public record ResearchRunSnapshot(
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

    /** Readiness of one artifact type for the run (ADR-065 §4). */
    public record ArtifactReadiness(
            ResearchRunStage stage, ResearchArtifactType artifactType, ResearchArtifactReadiness readiness) {}

    /** A gate still awaiting a required human decision (ADR-065 §3). */
    public record PendingGate(ResearchGatePoint gatePoint, ResearchRunStage guardedStageExit) {}

    /** Bounded source-disposition counts (ADR-065 §5). */
    public record SourceCounts(
            int candidateSources, int screenedIncluded, int screenedExcluded, int chartedFullText, int accessGaps) {}

    /** Budget caps separated from observed usage (ADR-065 §7). */
    public record Cost(
            Long budgetTokens,
            Integer budgetWallClockMinutes,
            Long budgetCostUsdMicros,
            long observedTokens,
            long observedCostUsdMicros) {}

    /** Bounded most-recent failure observation (ADR-065 §6); null when none. */
    public record LastError(String code, String errorClass, String summary, Instant at) {}
}
