package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.CreateScopedControlImplementationCommand;
import com.keplerops.groundcontrol.domain.riskcontrol.service.ScopedControlImplementationService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.UpdateScopedControlImplementationCommand;
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
@RequestMapping("/api/v1/scoped-control-implementations")
public class ScopedControlImplementationController {

    private final ScopedControlImplementationService service;
    private final ProjectService projectService;

    public ScopedControlImplementationController(
            ScopedControlImplementationService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScopedControlImplementationResponse create(
            @Valid @RequestBody ScopedControlImplementationRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ScopedControlImplementationResponse.from(service.create(new CreateScopedControlImplementationCommand(
                projectId,
                request.uid(),
                request.controlId(),
                request.name(),
                request.implementationScope(),
                request.operationalAssetId())));
    }

    @GetMapping
    public List<ScopedControlImplementationResponse> list(
            @RequestParam(required = false) UUID controlId, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var rows = controlId == null
                ? service.listByProject(projectId)
                : service.listByProjectAndControl(projectId, controlId);
        return rows.stream().map(ScopedControlImplementationResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ScopedControlImplementationResponse getById(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ScopedControlImplementationResponse.from(service.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public ScopedControlImplementationResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateScopedControlImplementationRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ScopedControlImplementationResponse.from(service.update(new UpdateScopedControlImplementationCommand(
                projectId, id, request.name(), request.implementationScope(), request.operationalAssetId())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        service.delete(projectId, id);
    }
}
