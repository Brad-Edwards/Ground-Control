package com.keplerops.groundcontrol.api.grcassessment;

import com.keplerops.groundcontrol.domain.grcassessment.model.GrcAssessmentRun;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentMode;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewDecision;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewPolicy;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentRunState;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentScopeType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GrcAssessmentRunResponse(
        UUID id,
        String projectIdentifier,
        GrcAssessmentMode mode,
        GrcAssessmentScopeType scopeType,
        List<String> scopeValues,
        String commitSha,
        String baseCommitSha,
        List<String> languages,
        List<String> surfaces,
        String threatPackId,
        String threatPackVersion,
        GrcAssessmentReviewPolicy reviewPolicy,
        GrcAssessmentReviewDecision reviewDecision,
        GrcAssessmentRunState state,
        String reviewedBy,
        Instant reviewedAt,
        String reviewRationale,
        String idempotencyKey,
        int partitionCount,
        int dedupedPartitionCount,
        int duplicatePartitionCount,
        List<Map<String, Object>> partitions,
        Map<String, Object> mergeSummary,
        int graphEffectCount,
        List<Map<String, Object>> graphEffects,
        Instant createdAt,
        Instant updatedAt) {

    public static GrcAssessmentRunResponse from(GrcAssessmentRun run) {
        return new GrcAssessmentRunResponse(
                run.getId(),
                run.getProject().getIdentifier(),
                run.getMode(),
                run.getScopeType(),
                run.getScopeValues(),
                run.getCommitSha(),
                run.getBaseCommitSha(),
                run.getLanguages(),
                run.getSurfaces(),
                run.getThreatPackId(),
                run.getThreatPackVersion(),
                run.getReviewPolicy(),
                run.getReviewDecision(),
                run.getState(),
                run.getReviewedBy(),
                run.getReviewedAt(),
                run.getReviewRationale(),
                run.getIdempotencyKey(),
                run.getPartitionCount(),
                run.getDedupedPartitionCount(),
                run.getDuplicatePartitionCount(),
                run.getPartitions(),
                run.getMergeSummary(),
                run.getGraphEffectCount(),
                run.getGraphEffects(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
