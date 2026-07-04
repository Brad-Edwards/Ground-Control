package com.keplerops.groundcontrol.api.grcassessment;

import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GrcAssessmentReviewRequest(
        @NotNull GrcAssessmentReviewDecision reviewDecision,
        @Size(max = 200) String reviewedBy,
        @Size(max = 2000) String reviewRationale) {}
