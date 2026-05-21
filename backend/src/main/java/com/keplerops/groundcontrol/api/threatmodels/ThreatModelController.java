package com.keplerops.groundcontrol.api.threatmodels;

import com.keplerops.groundcontrol.api.requirements.RequirementResponse;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.threatmodels.service.CreateThreatModelCommand;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelService;
import com.keplerops.groundcontrol.domain.threatmodels.service.UpdateThreatModelCommand;
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
@RequestMapping("/api/v1/threat-models")
public class ThreatModelController {

    private final ThreatModelService threatModelService;
    private final ProjectService projectService;

    public ThreatModelController(ThreatModelService threatModelService, ProjectService projectService) {
        this.threatModelService = threatModelService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ThreatModelResponse create(
            @Valid @RequestBody ThreatModelRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var command = new CreateThreatModelCommand(
                projectId,
                request.uid(),
                request.title(),
                request.threatSource(),
                request.threatEvent(),
                request.effect(),
                request.stride(),
                request.narrative());
        return ThreatModelResponse.from(threatModelService.create(command));
    }

    @GetMapping
    public List<ThreatModelResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return threatModelService.listByProject(projectId).stream()
                .map(ThreatModelResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ThreatModelResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ThreatModelResponse.from(threatModelService.getById(projectId, id));
    }

    @GetMapping("/uid/{uid}")
    public ThreatModelResponse getByUid(@PathVariable String uid, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ThreatModelResponse.from(threatModelService.getByUid(uid, projectId));
    }

    @PutMapping("/{id}")
    public ThreatModelResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateThreatModelRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var command = new UpdateThreatModelCommand(
                request.title(),
                request.threatSource(),
                request.threatEvent(),
                request.effect(),
                request.stride(),
                request.narrative(),
                Boolean.TRUE.equals(request.clearStride()),
                Boolean.TRUE.equals(request.clearNarrative()));
        return ThreatModelResponse.from(threatModelService.update(projectId, id, command));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        threatModelService.delete(projectId, id);
    }

    @GetMapping("/{id}/requirements")
    public List<RequirementResponse> getLinkedRequirements(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return threatModelService.findLinkedRequirements(projectId, id).stream()
                .map(RequirementResponse::from)
                .toList();
    }

    @PutMapping("/{id}/status")
    public ThreatModelResponse transitionStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ThreatModelStatusTransitionRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ThreatModelResponse.from(threatModelService.transitionStatus(projectId, id, request.status()));
    }
}
