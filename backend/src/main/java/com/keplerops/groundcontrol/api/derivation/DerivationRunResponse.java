package com.keplerops.groundcontrol.api.derivation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DerivationRunResponse(
        UUID id,
        String projectIdentifier,
        DerivationScopeMode scopeMode,
        String commitSha,
        String baseCommitSha,
        List<String> paths,
        List<String> languages,
        List<String> surfaces,
        String requestedBy,
        Instant requestedAt,
        int adapterCount,
        int factCount,
        int captureLimitCount,
        Instant createdAt,
        Instant updatedAt) {

    public static DerivationRunResponse from(DerivationRun run) {
        return new DerivationRunResponse(
                run.getId(),
                run.getProject().getIdentifier(),
                run.getScopeMode(),
                run.getCommitSha(),
                run.getBaseCommitSha(),
                run.getPaths(),
                run.getLanguages(),
                run.getSurfaces(),
                run.getRequestedBy(),
                run.getRequestedAt(),
                run.getAdapterCount(),
                run.getFactCount(),
                run.getCaptureLimitCount(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
