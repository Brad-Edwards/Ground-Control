package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.service.DecideOperationAuthorizationCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * GC-RSCH-R005 / ADR-084 §3 — an admin/operator decision on a proposed research
 * high-risk operation authorization. The deciding actor is the authenticated
 * principal, never a request field.
 */
public record OperationAuthorizationDecisionRequest(@NotNull Boolean approve, @Size(max = 500) String note) {

    public DecideOperationAuthorizationCommand toCommand() {
        return new DecideOperationAuthorizationCommand(Boolean.TRUE.equals(approve), note);
    }
}
