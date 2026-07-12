package com.keplerops.groundcontrol.infrastructure.temporal.implement;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.llm.LlmCompletionRequest;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationObservation;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationPort;
import com.keplerops.groundcontrol.domain.llm.PlanPublicationRequest;
import com.keplerops.groundcontrol.infrastructure.llm.LlmProviderRegistry;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanInput;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.AuthorPlanResult;
import com.keplerops.groundcontrol.infrastructure.temporal.implement.contract.ResolvedLlmRoute;

/**
 * The LLM-backed collaborator for the {@code authorPlan} content activity (ADR-028 LLM provider
 * boundary). Composed into {@link ImplementContentActivitiesImpl} so the provider dependency reaches
 * only this one method, never the deterministic activities or the other content-activity seams.
 *
 * <p>{@link #authorPlan} resolves the provider for the already-bound {@link ResolvedLlmRoute} (route
 * resolution itself happened once, before the workflow started — see {@code WorkflowExecutionService});
 * looking the provider up here is the "repeat the safe availability check" defense in depth the
 * preflight calls for. It then builds the prompt inside this activity process (never a Temporal input),
 * invokes the port, keeps the completion in process, and hands it to {@link PlanPublicationPort} —
 * returning only the bounded {@code (posted, commentId)} fact. Raw issue bodies, repository text,
 * prompts, and completions never cross the Temporal boundary.
 */
public final class LlmPlanAuthor {

    private static final int MAX_OUTPUT_TOKENS = 8192;

    private final LlmProviderRegistry providerRegistry;
    private final PlanPublicationPort planPublicationPort;

    public LlmPlanAuthor(LlmProviderRegistry providerRegistry, PlanPublicationPort planPublicationPort) {
        this.providerRegistry = providerRegistry;
        this.planPublicationPort = planPublicationPort;
    }

    public AuthorPlanResult authorPlan(AuthorPlanInput input) {
        var project = requireNonBlank(input.project(), "project");
        var route = requireRoute(input.route());
        var provider = providerRegistry.get(route.providerId());

        // Observe before infer. Temporal activities are at-least-once: if inference succeeded and the
        // publication's acknowledgement was lost, the retry would otherwise pay for a second completion.
        // The observation carries only safe scalars and runs before a single token is bought.
        var existing = planPublicationPort.findExistingPlan(
                new PlanPublicationObservation(project, input.issueNumber(), input.idempotencyKey()));
        if (existing.isPresent()) {
            return new AuthorPlanResult(existing.get().posted(), existing.get().commentId());
        }

        var prompt = buildPrompt(project, input.issueNumber(), input.requirementUids());
        var completion = provider.complete(new LlmCompletionRequest(route.modelId(), prompt, MAX_OUTPUT_TOKENS));

        var publication = planPublicationPort.publish(new PlanPublicationRequest(
                project, input.issueNumber(), input.requirementUids(), input.idempotencyKey(), completion));
        return new AuthorPlanResult(publication.posted(), publication.commentId());
    }

    /**
     * Activity-owned prompt content, built here rather than supplied as a Temporal input (preflight:
     * "the activity resolves the referenced content and builds the prompt inside the activity"). This
     * first slice references only the bounded scalars already on {@link AuthorPlanInput} — the fuller
     * issue/requirement context resolution is a later program phase's prompt-construction work, not a
     * generic prompt DSL (preflight anti-pattern list).
     */
    private static String buildPrompt(String project, int issueNumber, java.util.List<String> requirementUids) {
        var builder = new StringBuilder();
        builder.append("Author an implementation plan for project ")
                .append(project)
                .append(", issue #")
                .append(issueNumber)
                .append('.');
        if (!requirementUids.isEmpty()) {
            builder.append(" Requirements: ")
                    .append(String.join(", ", requirementUids))
                    .append('.');
        }
        return builder.toString();
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " is required");
        }
        return value;
    }

    private static ResolvedLlmRoute requireRoute(ResolvedLlmRoute route) {
        if (route == null) {
            throw new DomainValidationException("route is required");
        }
        return route;
    }
}
