package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.research.model.ResearchDataClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchDataForm;
import com.keplerops.groundcontrol.domain.research.model.ResearchDestinationClass;
import com.keplerops.groundcontrol.domain.research.model.ResearchHighRiskOperationKind;
import java.time.Instant;

/**
 * GC-RSCH-R005 / ADR-086 §3 — request (propose) a research high-risk operation
 * authorization. Bounded, typed facts only; the proposing actor is taken from
 * the authenticated server context, not this command. All policy fields are
 * closed enums so retrieved/untrusted content can never set them (GC-RSCH-N014).
 */
public record RequestOperationAuthorizationCommand(
        ResearchHighRiskOperationKind operationKind,
        ResearchDataClass dataClass,
        ResearchDestinationClass destinationClass,
        ResearchDataForm requestedForm,
        String toolId,
        String sandboxProfile,
        String targetClass,
        Instant expiresAt,
        String summary,
        String sourceActionId) {}
