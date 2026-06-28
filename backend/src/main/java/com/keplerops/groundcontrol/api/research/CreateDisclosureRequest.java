package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.service.CreateDisclosureCommand;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Create the final-manuscript disclosure for a run (GC-RSCH-N013, ADR-068 §4).
 * The actor is taken from the authenticated server context, not the request
 * body (ADR-026).
 */
public record CreateDisclosureRequest(
        @NotNull UUID finalArtifactId,
        @NotNull Integer finalAttemptNo,
        boolean aiPartsDeclaredNone,
        boolean uncertaintyDeclaredNone,
        boolean humanApprovalsDeclaredNone) {

    public CreateDisclosureCommand toCommand() {
        return new CreateDisclosureCommand(
                finalArtifactId,
                finalAttemptNo,
                aiPartsDeclaredNone,
                uncertaintyDeclaredNone,
                humanApprovalsDeclaredNone);
    }
}
