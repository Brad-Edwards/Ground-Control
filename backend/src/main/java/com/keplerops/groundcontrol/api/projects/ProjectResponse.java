package com.keplerops.groundcontrol.api.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keplerops.groundcontrol.api.research.ResearchIntakeResponse;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.research.model.ResearchIntake;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectResponse(
        UUID id,
        String identifier,
        String name,
        String description,
        ProjectType type,
        Instant createdAt,
        Instant updatedAt,
        ResearchIntakeResponse researchIntake) {

    public static ProjectResponse from(Project p) {
        return from(p, null);
    }

    public static ProjectResponse from(Project p, ResearchIntake intake) {
        return new ProjectResponse(
                p.getId(),
                p.getIdentifier(),
                p.getName(),
                p.getDescription(),
                p.getType(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                intake == null ? null : ResearchIntakeResponse.from(intake));
    }
}
