package com.keplerops.groundcontrol.domain.research.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * GC-RSCH-N006 / ADR-084 §2 — one structured allow rule in a research egress
 * policy. It binds a {@link ResearchDataClass} and {@link ResearchDestinationClass}
 * to the maximum {@link ResearchDataForm} that may leave the local boundary for
 * that pair, with an optional bounded {@code purpose}. Absence of a matching
 * allowance is a deny (local-only) — the policy is never expressed as free text,
 * so retrieved/untrusted content can never widen it (GC-RSCH-N014).
 */
public record ResearchEgressAllowance(
        @NotNull ResearchDataClass dataClass,
        @NotNull ResearchDestinationClass destinationClass,
        @NotNull ResearchDataForm allowedForm,
        @Size(max = 200) String purpose) {

    /** True when this allowance covers the requested (dataClass, destinationClass) at least at {@code form}. */
    public boolean covers(
            ResearchDataClass requestedClass, ResearchDestinationClass destination, ResearchDataForm form) {
        return dataClass == requestedClass && destinationClass == destination && allowedForm.permits(form);
    }
}
