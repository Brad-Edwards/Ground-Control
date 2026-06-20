package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.CreateRiskControlMappingCommand;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.UpdateRiskControlMappingCommand;
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
@RequestMapping("/api/v1/risk-control-mappings")
public class RiskControlMappingController {

    private final RiskControlMappingService service;
    private final ProjectService projectService;

    public RiskControlMappingController(RiskControlMappingService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RiskControlMappingResponse create(
            @Valid @RequestBody RiskControlMappingRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return RiskControlMappingResponse.from(service.create(new CreateRiskControlMappingCommand(
                projectId,
                request.controlId(),
                request.scopedImplementationId(),
                request.riskScenarioId(),
                request.riskRegisterRecordId(),
                request.threatModelId(), // GC-H006
                request.operationalAssetId(),
                request.mappingObjective(),
                request.controlRole(),
                request.mappingScope(),
                request.methodologyProfileId(),
                request.methodologyInfluence())));
    }

    @GetMapping
    public List<RiskControlMappingResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return service.listByProject(projectId).stream()
                .map(RiskControlMappingResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public RiskControlMappingResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskControlMappingResponse.from(service.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public RiskControlMappingResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRiskControlMappingRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskControlMappingResponse.from(service.update(new UpdateRiskControlMappingCommand(
                projectId,
                id,
                request.mappingObjective(),
                request.controlRole(),
                request.mappingScope(),
                request.methodologyProfileId(),
                request.methodologyInfluence())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        service.delete(projectId, id);
    }

    // ---- C8: Observation management ----

    @PostMapping("/{id}/observations")
    public RiskControlMappingResponse attachObservation(
            @PathVariable UUID id,
            @Valid @RequestBody AttachObservationRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskControlMappingResponse.from(service.attachObservation(projectId, id, request.observationId()));
    }

    @DeleteMapping("/{id}/observations/{observationId}")
    public RiskControlMappingResponse detachObservation(
            @PathVariable UUID id, @PathVariable UUID observationId, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskControlMappingResponse.from(service.detachObservation(projectId, id, observationId));
    }

    // ---- C8: Evidence ref management ----

    @PostMapping("/{id}/evidence")
    public RiskControlMappingResponse addEvidenceRef(
            @PathVariable UUID id,
            @Valid @RequestBody AddEvidenceRefRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskControlMappingResponse.from(service.addEvidenceRef(
                projectId, id, request.evidenceRef(), request.evidenceNote(), request.evidenceArtifactId()));
    }
}
