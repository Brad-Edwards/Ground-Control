package com.keplerops.groundcontrol.api.derivation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationCaptureLimit;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DerivationCaptureLimitResponse(
        UUID id,
        UUID derivationRunId,
        String projectIdentifier,
        String adapterId,
        CaptureLimitReason reason,
        String language,
        String surface,
        String detail,
        String commitSha,
        Instant capturedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static DerivationCaptureLimitResponse from(DerivationCaptureLimit captureLimit) {
        return new DerivationCaptureLimitResponse(
                captureLimit.getId(),
                captureLimit.getDerivationRun().getId(),
                captureLimit.getProject().getIdentifier(),
                captureLimit.getAdapterId(),
                captureLimit.getReason(),
                captureLimit.getLanguage(),
                captureLimit.getSurface(),
                captureLimit.getDetail(),
                captureLimit.getCommitSha(),
                captureLimit.getCapturedAt(),
                captureLimit.getCreatedAt(),
                captureLimit.getUpdatedAt());
    }
}
