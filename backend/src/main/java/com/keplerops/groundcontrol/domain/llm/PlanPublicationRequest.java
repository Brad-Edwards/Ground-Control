package com.keplerops.groundcontrol.domain.llm;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.List;

/**
 * Input to {@link PlanPublicationPort#publish(PlanPublicationRequest)}: the in-process completion plus
 * the bounded correlation fields the durable plan-publication surface needs (ADR-028/ADR-029). The
 * completion is kept in process — it is never returned, logged, or Jackson-serialized elsewhere.
 */
public record PlanPublicationRequest(
        String project,
        int issueNumber,
        List<String> requirementUids,
        String idempotencyKey,
        LlmCompletion completion) {

    public PlanPublicationRequest {
        if (project == null || project.isBlank()) {
            throw new DomainValidationException("project is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainValidationException("idempotencyKey is required");
        }
        if (completion == null) {
            throw new DomainValidationException("completion is required");
        }
        requirementUids = requirementUids == null ? List.of() : List.copyOf(requirementUids);
    }
}
