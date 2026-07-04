package com.keplerops.groundcontrol.domain.grcassessment.service;

import com.keplerops.groundcontrol.domain.derivation.service.BoundaryDeclaration;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentMode;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewDecision;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewPolicy;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentScopeType;
import java.util.List;
import java.util.UUID;

public record CreateGrcAssessmentRunCommand(
        UUID projectId,
        GrcAssessmentMode mode,
        GrcAssessmentScopeType scopeType,
        List<String> scopeValues,
        String commitSha,
        String baseCommitSha,
        List<String> languages,
        List<String> surfaces,
        List<BoundaryDeclaration> declaredBoundaries,
        String threatPackId,
        String threatPackVersion,
        GrcAssessmentReviewPolicy reviewPolicy,
        GrcAssessmentReviewDecision reviewDecision,
        String idempotencyKey,
        Integer partitionLimit) {

    public CreateGrcAssessmentRunCommand {
        scopeValues = scopeValues == null ? List.of() : List.copyOf(scopeValues);
        languages = languages == null ? List.of() : List.copyOf(languages);
        surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
        declaredBoundaries = declaredBoundaries == null ? List.of() : List.copyOf(declaredBoundaries);
    }
}
