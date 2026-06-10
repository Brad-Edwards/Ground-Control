package com.keplerops.groundcontrol.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.api.research.ResearchIntakeRequest;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create-project request. {@code type} defaults to {@link ProjectType#SOFTWARE}
 * if omitted (preserves backward compatibility for pre-ADR-056 clients).
 * {@code researchIntake} must be present iff {@code type == RESEARCH};
 * enforced by the service layer so the rule is uniform across bypass writes
 * (the API and service guard agree).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "[a-z0-9-]+") String identifier,
        @NotBlank @Size(max = 255) String name,
        String description,
        ProjectType type,
        @Valid ResearchIntakeRequest researchIntake) {

    public ProjectRequest(String identifier, String name, String description) {
        this(identifier, name, description, null, null);
    }
}
