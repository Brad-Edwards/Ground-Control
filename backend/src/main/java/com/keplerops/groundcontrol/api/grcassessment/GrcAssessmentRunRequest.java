package com.keplerops.groundcontrol.api.grcassessment;

import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentMode;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewDecision;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewPolicy;
import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentScopeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record GrcAssessmentRunRequest(
        @NotNull GrcAssessmentMode mode,
        @NotNull GrcAssessmentScopeType scopeType,
        @Size(max = 500) List<@NotBlank @Size(max = 500) String> scopeValues,
        @Size(min = 7, max = 64) @Pattern(regexp = "^[0-9a-fA-F]{7,64}$") String commitSha,
        @Size(min = 7, max = 64) @Pattern(regexp = "^[0-9a-fA-F]{7,64}$") String baseCommitSha,
        @Size(max = 50) List<@NotBlank @Size(max = 80) String> languages,
        @Size(max = 50) List<@NotBlank @Size(max = 80) String> surfaces,
        @Size(max = 100) List<@Valid BoundaryDeclarationRequest> declaredBoundaries,
        @Size(max = 200) String threatPackId,
        @Size(max = 100) String threatPackVersion,
        GrcAssessmentReviewPolicy reviewPolicy,
        GrcAssessmentReviewDecision reviewDecision,
        @Size(max = 200) String idempotencyKey,
        Integer partitionLimit) {

    public record BoundaryDeclarationRequest(
            @NotBlank @Size(max = 120) @Pattern(regexp = "^[a-z0-9][a-z0-9_.-]{0,119}$") String key,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Size(max = 50) List<@NotBlank @Size(max = 500) String> pathSelectors,
            @Size(max = 50) List<@NotBlank @Size(max = 80) String> surfaces) {}
}
