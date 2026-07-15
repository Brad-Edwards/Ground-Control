package com.keplerops.groundcontrol.domain.projects.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final ResearchIntakeService researchIntakeService;

    public ProjectService(ProjectRepository projectRepository, ResearchIntakeService researchIntakeService) {
        this.projectRepository = projectRepository;
        this.researchIntakeService = researchIntakeService;
    }

    public Project create(CreateProjectCommand command) {
        if (projectRepository.existsByIdentifier(command.identifier())) {
            throw new ConflictException("Project with identifier '" + command.identifier() + "' already exists");
        }
        var resolvedType = command.type() == null ? ProjectType.SOFTWARE : command.type();
        rejectRetiredTypeForCreation(resolvedType);
        validateIntakeAgainstType(resolvedType, command.researchIntake());
        var project = new Project(command.identifier(), command.name(), resolvedType);
        if (command.description() != null) {
            project.setDescription(command.description());
        }
        var saved = projectRepository.save(project);
        log.info("project_created: identifier={} id={} type={}", saved.getIdentifier(), saved.getId(), saved.getType());
        if (resolvedType == ProjectType.RESEARCH && command.researchIntake() != null) {
            researchIntakeService.create(saved, command.researchIntake());
        }
        return saved;
    }

    private void rejectRetiredTypeForCreation(ProjectType type) {
        // ADR-089 §4: GRC persists as a legacy value and stays readable, but is not offered
        // for new project creation. Reject at the service boundary so both the REST API and
        // MCP bypass writes hit the same guard (the enum still accepts GRC when reading rows).
        if (type == ProjectType.GRC) {
            throw new DomainValidationException(
                    "type=GRC is a legacy value and cannot be used for new project creation (ADR-089 §4);"
                            + " existing GRC projects remain readable",
                    "project_type_grc_not_creatable",
                    Map.of("type", type.name()));
        }
    }

    private void validateIntakeAgainstType(
            ProjectType type, com.keplerops.groundcontrol.domain.research.service.ResearchIntakeCommand intake) {
        if (type == ProjectType.RESEARCH && intake == null) {
            throw new DomainValidationException(
                    "type=RESEARCH requires researchIntake to be present",
                    "research_intake_required",
                    Map.of("type", type.name()));
        }
        if (type != ProjectType.RESEARCH && intake != null) {
            throw new DomainValidationException(
                    "researchIntake is only allowed when type=RESEARCH",
                    "research_intake_not_allowed",
                    Map.of("type", type.name()));
        }
    }

    @Transactional(readOnly = true)
    public Project getById(UUID id) {
        return projectRepository.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
    }

    @Transactional(readOnly = true)
    public Project getByIdentifier(String identifier) {
        return projectRepository
                .findByIdentifier(identifier)
                .orElseThrow(() -> new NotFoundException("Project not found: " + identifier));
    }

    @Transactional(readOnly = true)
    public List<Project> list() {
        return projectRepository.findAll();
    }

    public Project updateByIdentifier(String identifier, UpdateProjectCommand command) {
        var project = getByIdentifier(identifier);
        if (command.name() != null) {
            project.setName(command.name());
        }
        if (command.description() != null) {
            project.setDescription(command.description());
        }
        var saved = projectRepository.save(project);
        log.info("project_updated: identifier={} id={}", saved.getIdentifier(), saved.getId());
        return saved;
    }

    public Project update(UUID id, UpdateProjectCommand command) {
        var project = getById(id);
        if (command.name() != null) {
            project.setName(command.name());
        }
        if (command.description() != null) {
            project.setDescription(command.description());
        }
        var saved = projectRepository.save(project);
        log.info("project_updated: identifier={} id={}", saved.getIdentifier(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Project resolveProject(String projectIdentifier) {
        if (projectIdentifier != null) {
            return getByIdentifier(projectIdentifier);
        }
        long count = projectRepository.count();
        if (count == 1) {
            return projectRepository.findAll().getFirst();
        }
        throw new DomainValidationException(
                "Multiple projects exist. Specify a 'project' parameter.",
                "project_required",
                Map.of("project_count", count));
    }

    @Transactional(readOnly = true)
    public Project requireProject(String projectIdentifier) {
        if (projectIdentifier == null || projectIdentifier.isBlank()) {
            throw new DomainValidationException(
                    "A 'project' parameter is required for this route.",
                    "project_required",
                    Map.of("parameter", "project"));
        }
        return getByIdentifier(projectIdentifier);
    }

    @Transactional(readOnly = true)
    public UUID resolveProjectId(String projectIdentifier) {
        return resolveProject(projectIdentifier).getId();
    }

    @Transactional(readOnly = true)
    public UUID requireProjectId(String projectIdentifier) {
        return requireProject(projectIdentifier).getId();
    }

    @Transactional(readOnly = true)
    public String resolveProjectIdentifier(String projectIdentifier) {
        return resolveProject(projectIdentifier).getIdentifier();
    }

    @Transactional(readOnly = true)
    public String requireProjectIdentifier(String projectIdentifier) {
        return requireProject(projectIdentifier).getIdentifier();
    }
}
