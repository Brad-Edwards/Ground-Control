package com.keplerops.groundcontrol.domain.projects.service;

import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeCommand;

/**
 * Command DTO for creating a Project. {@code type} defaults to
 * {@link ProjectType#SOFTWARE} when null; {@code researchIntake} must be
 * present iff {@code type == RESEARCH}. The service enforces the
 * intake-vs-type invariant; the API also enforces it at the validation layer.
 */
public record CreateProjectCommand(
        String identifier, String name, String description, ProjectType type, ResearchIntakeCommand researchIntake) {

    /** Backward-compatible constructor for callers that don't supply a type. */
    public CreateProjectCommand(String identifier, String name, String description) {
        this(identifier, name, description, ProjectType.SOFTWARE, null);
    }
}
