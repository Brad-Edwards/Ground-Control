package com.keplerops.groundcontrol.api.projects;

import com.keplerops.groundcontrol.api.research.ResearchIntakeRequest;
import com.keplerops.groundcontrol.api.research.ResearchIntakeResponse;
import com.keplerops.groundcontrol.domain.projects.service.CreateProjectCommand;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.projects.service.UpdateProjectCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ResearchIntakeService researchIntakeService;

    public ProjectController(ProjectService projectService, ResearchIntakeService researchIntakeService) {
        this.projectService = projectService;
        this.researchIntakeService = researchIntakeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody ProjectRequest request) {
        var command = new CreateProjectCommand(
                request.identifier(),
                request.name(),
                request.description(),
                request.type(),
                request.researchIntake() == null
                        ? null
                        : request.researchIntake().toCommand());
        var project = projectService.create(command);
        return ProjectResponse.from(
                project, researchIntakeService.findByProject(project).orElse(null));
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.list().stream()
                .map(p -> ProjectResponse.from(
                        p, researchIntakeService.findByProject(p).orElse(null)))
                .toList();
    }

    @GetMapping("/{identifier}")
    public ProjectResponse getByIdentifier(@PathVariable String identifier) {
        var project = projectService.getByIdentifier(identifier);
        return ProjectResponse.from(
                project, researchIntakeService.findByProject(project).orElse(null));
    }

    @PutMapping("/{identifier}")
    public ProjectResponse update(@PathVariable String identifier, @Valid @RequestBody UpdateProjectRequest request) {
        var command = new UpdateProjectCommand(request.name(), request.description());
        var project = projectService.updateByIdentifier(identifier, command);
        return ProjectResponse.from(
                project, researchIntakeService.findByProject(project).orElse(null));
    }

    @PutMapping("/{identifier}/research-intake")
    public ResearchIntakeResponse replaceResearchIntake(
            @PathVariable String identifier, @Valid @RequestBody ResearchIntakeRequest request) {
        var project = projectService.getByIdentifier(identifier);
        var saved = researchIntakeService.replace(project, request.toCommand());
        return ResearchIntakeResponse.from(saved);
    }
}
