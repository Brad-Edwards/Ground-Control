package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateKeyRiskIndicatorCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.KeyRiskIndicatorService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RecordKriMeasurementCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateKeyRiskIndicatorCommand;
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
@RequestMapping("/api/v1/key-risk-indicators")
public class KeyRiskIndicatorController {

    private final KeyRiskIndicatorService service;
    private final ProjectService projectService;

    public KeyRiskIndicatorController(KeyRiskIndicatorService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KeyRiskIndicatorResponse create(
            @Valid @RequestBody KeyRiskIndicatorRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return KeyRiskIndicatorResponse.from(service.create(new CreateKeyRiskIndicatorCommand(
                projectId,
                request.uid(),
                request.name(),
                request.description(),
                request.metricUnit(),
                request.yellowThreshold(),
                request.redThreshold(),
                request.direction(),
                request.owner(),
                request.riskRegisterRecordId(),
                request.riskScenarioId())));
    }

    @GetMapping
    public List<KeyRiskIndicatorResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return service.listByProject(projectId).stream()
                .map(KeyRiskIndicatorResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public KeyRiskIndicatorResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return KeyRiskIndicatorResponse.from(service.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public KeyRiskIndicatorResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateKeyRiskIndicatorRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return KeyRiskIndicatorResponse.from(service.update(
                projectId,
                id,
                new UpdateKeyRiskIndicatorCommand(
                        request.name(),
                        request.description(),
                        request.metricUnit(),
                        request.yellowThreshold(),
                        request.redThreshold(),
                        request.direction(),
                        request.owner(),
                        request.riskRegisterRecordId(),
                        request.riskScenarioId())));
    }

    @PostMapping("/{id}/measurements")
    public KeyRiskIndicatorResponse recordMeasurement(
            @PathVariable UUID id,
            @Valid @RequestBody KriMeasurementRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return KeyRiskIndicatorResponse.from(service.recordMeasurement(
                projectId, id, new RecordKriMeasurementCommand(request.value(), request.measuredAt())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        service.delete(projectId, id);
    }
}
