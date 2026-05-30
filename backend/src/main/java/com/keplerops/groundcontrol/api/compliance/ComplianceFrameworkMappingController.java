package com.keplerops.groundcontrol.api.compliance;

import com.keplerops.groundcontrol.domain.compliance.service.ComplianceFrameworkMappingService;
import com.keplerops.groundcontrol.domain.compliance.service.CreateComplianceFrameworkMappingCommand;
import com.keplerops.groundcontrol.domain.compliance.service.UpdateComplianceFrameworkMappingCommand;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
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

/**
 * REST surface for the compliance-framework-mapping aggregate (GC-I002 /
 * GC-I005 / GC-I007 / GC-L011). Mirrors the {@code RiskControlMappingController}
 * pattern: resolve project at the boundary, delegate to the domain service,
 * project to a stable API response record.
 */
@RestController
@RequestMapping("/api/v1/compliance-framework-mappings")
public class ComplianceFrameworkMappingController {

    private final ComplianceFrameworkMappingService service;
    private final ProjectService projectService;

    public ComplianceFrameworkMappingController(
            ComplianceFrameworkMappingService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ComplianceFrameworkMappingResponse create(
            @Valid @RequestBody ComplianceFrameworkMappingRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ComplianceFrameworkMappingResponse.from(service.create(new CreateComplianceFrameworkMappingCommand(
                projectId,
                request.requirementId(),
                request.controlId(),
                request.framework(),
                request.frameworkIdentifier(),
                request.frameworkVersion(),
                request.frameworkElement(),
                request.coverageLevel(),
                request.rationale())));
    }

    @GetMapping
    public List<ComplianceFrameworkMappingResponse> list(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) ComplianceFrameworkIdentifier framework,
            @RequestParam(required = false) UUID requirementId,
            @RequestParam(required = false) UUID controlId) {
        var projectId = projectService.resolveProjectId(project);
        List<com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping> mappings;
        if (framework != null) {
            mappings = service.listByFramework(projectId, framework);
        } else if (requirementId != null) {
            mappings = service.listByRequirement(projectId, requirementId);
        } else if (controlId != null) {
            mappings = service.listByControl(projectId, controlId);
        } else {
            mappings = service.listByProject(projectId);
        }
        return mappings.stream().map(ComplianceFrameworkMappingResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ComplianceFrameworkMappingResponse getById(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ComplianceFrameworkMappingResponse.from(service.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public ComplianceFrameworkMappingResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateComplianceFrameworkMappingRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ComplianceFrameworkMappingResponse.from(service.update(new UpdateComplianceFrameworkMappingCommand(
                projectId,
                id,
                request.framework(),
                request.frameworkIdentifier(),
                request.frameworkVersion(),
                request.frameworkElement(),
                request.coverageLevel(),
                request.rationale())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        service.delete(projectId, id);
    }
}
