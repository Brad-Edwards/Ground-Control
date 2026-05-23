package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateTreatmentPlanCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.TreatmentPlanService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateTreatmentPlanCommand;
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
@RequestMapping("/api/v1/treatment-plans")
public class TreatmentPlanController {

    private final TreatmentPlanService treatmentPlanService;
    private final ProjectService projectService;

    public TreatmentPlanController(TreatmentPlanService treatmentPlanService, ProjectService projectService) {
        this.treatmentPlanService = treatmentPlanService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TreatmentPlanResponse create(
            @Valid @RequestBody TreatmentPlanRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return TreatmentPlanResponse.from(treatmentPlanService.create(new CreateTreatmentPlanCommand(
                projectId,
                request.uid(),
                request.title(),
                request.riskRegisterRecordId(),
                request.riskScenarioId(),
                request.strategy(),
                request.owner(),
                request.rationale(),
                request.dueDate(),
                request.status(),
                request.actionItems(),
                request.reassessmentTriggers(),
                request.methodologyProfileId(),
                request.methodologyStrategyKey())));
    }

    @GetMapping
    public List<TreatmentPlanResponse> list(
            @RequestParam(required = false) UUID riskRegisterRecordId, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        if (riskRegisterRecordId != null) {
            return treatmentPlanService.listByRiskRegisterRecord(projectId, riskRegisterRecordId).stream()
                    .map(TreatmentPlanResponse::from)
                    .toList();
        }
        return treatmentPlanService.listByProject(projectId).stream()
                .map(TreatmentPlanResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public TreatmentPlanResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return TreatmentPlanResponse.from(treatmentPlanService.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public TreatmentPlanResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTreatmentPlanRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return TreatmentPlanResponse.from(treatmentPlanService.update(
                projectId,
                id,
                new UpdateTreatmentPlanCommand(
                        request.title(),
                        request.riskScenarioId(),
                        request.strategy(),
                        request.owner(),
                        request.rationale(),
                        request.dueDate(),
                        request.actionItems(),
                        request.reassessmentTriggers(),
                        request.methodologyProfileId(),
                        request.methodologyStrategyKey())));
    }

    @PutMapping("/{id}/status")
    public TreatmentPlanResponse transitionStatus(
            @PathVariable UUID id,
            @Valid @RequestBody TreatmentPlanStatusTransitionRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return TreatmentPlanResponse.from(treatmentPlanService.transitionStatus(projectId, id, request.status()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        treatmentPlanService.delete(projectId, id);
    }
}
