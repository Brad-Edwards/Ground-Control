package com.keplerops.groundcontrol.domain.grcassessment.service;

import com.keplerops.groundcontrol.domain.grcassessment.state.GrcAssessmentReviewDecision;
import java.util.UUID;

public record ReviewGrcAssessmentRunCommand(
        UUID projectId,
        UUID runId,
        GrcAssessmentReviewDecision reviewDecision,
        String reviewedBy,
        String reviewRationale) {}
