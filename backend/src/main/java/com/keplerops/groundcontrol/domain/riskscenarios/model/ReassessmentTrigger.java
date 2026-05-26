package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Typed value type for a single reassessment trigger on a TreatmentPlan
 * (GC-T004 / C8, issue #863). Serialised as a JSON object in the
 * {@code reassessment_triggers} TEXT column via
 * {@link com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.ReassessmentTriggerListConverter}.
 *
 * <p>{@code targetType} + ({@code targetEntityId} OR {@code targetIdentifier}) resolve through
 * {@code GraphTargetResolverService} so target validity is enforced at the same boundary as
 * the other link surfaces. {@code note} carries methodology-specific or legacy free-text
 * context that cannot be expressed by the enum.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReassessmentTrigger(
        @NotNull ReassessmentTriggerCategory category,
        ReassessmentTriggerTargetType targetType,
        UUID targetEntityId,
        @Size(max = 500) String targetIdentifier,
        @Size(max = 4000) String note) {}
