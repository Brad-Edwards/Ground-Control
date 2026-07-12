package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataForm;
import com.keplerops.groundcontrol.domain.research.model.ResearchDestinationClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchHighRiskOperationKind;
import com.keplerops.groundcontrol.domain.research.service.RequestOperationAuthorizationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * GC-RSCH-R005 / ADR-086 §1, §3 — request (propose) a research high-risk operation
 * authorization. An authorization must bind a concrete effect request, not just an
 * operation kind plus egress tuple, so the adapter/tool identity ({@code toolId}),
 * sandbox profile, bounded action summary, and retry-safe {@code sourceActionId}
 * are required — a future executor consuming an APPROVED record must be able to
 * prove which adapter/action/sandbox was authorized. {@code targetClass} is
 * target-specific and optional. All policy fields are closed enums bound by
 * Jackson, so retrieved/untrusted content can never inject a policy value
 * (GC-RSCH-N014). The schema deliberately has no actor field — the proposing actor
 * is the authenticated principal (ADR-026 / ADR-033).
 */
public record OperationAuthorizationRequest(
        @NotNull ResearchHighRiskOperationKind operationKind,
        @NotNull ResearchDataClass dataClass,
        @NotNull ResearchDestinationClass destinationClass,
        @NotNull ResearchDataForm requestedForm,
        @NotBlank @Size(max = 200) String toolId,
        @NotBlank @Size(max = 120) String sandboxProfile,
        @Size(max = 120) String targetClass,
        Instant expiresAt,
        @NotBlank @Size(max = 2000) String summary,
        @NotBlank @Size(max = 200) String sourceActionId) {

    public RequestOperationAuthorizationCommand toCommand() {
        return new RequestOperationAuthorizationCommand(
                operationKind,
                dataClass,
                destinationClass,
                requestedForm,
                toolId,
                sandboxProfile,
                targetClass,
                expiresAt,
                summary,
                sourceActionId);
    }
}
