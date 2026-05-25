package com.keplerops.groundcontrol.domain.riskscenarios.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ActionItemStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Typed value type for a single action item on a TreatmentPlan.
 * Serialised as a JSON object in the {@code action_items} TEXT column via
 * {@link com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters.ActionItemListConverter}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActionItem(
        @NotBlank @Size(max = 200) String owner,
        @NotNull Instant dueDate,
        @NotNull ActionItemStatus status,
        @Size(max = 200) String assignee,
        @Size(max = 4000) String description) {}
