package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateMethodologyProfileCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.MethodologyProfileService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateMethodologyProfileCommand;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/methodology-profiles")
public class MethodologyProfileController {

    private final MethodologyProfileService methodologyProfileService;
    private final ProjectService projectService;

    public MethodologyProfileController(
            MethodologyProfileService methodologyProfileService, ProjectService projectService) {
        this.methodologyProfileService = methodologyProfileService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MethodologyProfileResponse create(
            @Valid @RequestBody MethodologyProfileRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return MethodologyProfileResponse.from(methodologyProfileService.create(new CreateMethodologyProfileCommand(
                projectId,
                request.profileKey(),
                request.name(),
                request.version(),
                request.family(),
                request.description(),
                request.inputSchema(),
                request.outputSchema(),
                request.status(),
                request.treatmentStrategyVocabulary(),
                request.crosswalkEntries())));
    }

    @GetMapping
    public List<MethodologyProfileResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return methodologyProfileService.listByProject(projectId).stream()
                .map(MethodologyProfileResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public MethodologyProfileResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return MethodologyProfileResponse.from(methodologyProfileService.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public MethodologyProfileResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMethodologyProfileRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return MethodologyProfileResponse.from(methodologyProfileService.update(
                projectId,
                id,
                new UpdateMethodologyProfileCommand(
                        request.name(),
                        request.version(),
                        request.family(),
                        request.description(),
                        request.inputSchema(),
                        request.outputSchema(),
                        request.status(),
                        request.treatmentStrategyVocabulary(),
                        request.crosswalkEntries())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        methodologyProfileService.delete(projectId, id);
    }
}
