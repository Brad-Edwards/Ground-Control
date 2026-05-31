package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateRiskAssessmentCampaignCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAssessmentCampaignService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateRiskAssessmentCampaignCommand;
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
@RequestMapping("/api/v1/risk-assessment-campaigns")
public class RiskAssessmentCampaignController {

    private final RiskAssessmentCampaignService service;
    private final ProjectService projectService;

    public RiskAssessmentCampaignController(RiskAssessmentCampaignService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RiskAssessmentCampaignResponse create(
            @Valid @RequestBody RiskAssessmentCampaignRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return RiskAssessmentCampaignResponse.from(service.create(new CreateRiskAssessmentCampaignCommand(
                projectId,
                request.uid(),
                request.title(),
                request.owner(),
                request.objective(),
                request.methodologyProfileId(),
                request.appetiteProfileId(),
                request.scheduledStart(),
                request.scheduledEnd(),
                request.scope(),
                request.approvalMetadata(),
                request.scopedAssetIds())));
    }

    @GetMapping
    public List<RiskAssessmentCampaignResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return service.listByProject(projectId).stream()
                .map(RiskAssessmentCampaignResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public RiskAssessmentCampaignResponse getById(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskAssessmentCampaignResponse.from(service.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public RiskAssessmentCampaignResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRiskAssessmentCampaignRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskAssessmentCampaignResponse.from(service.update(
                projectId,
                id,
                new UpdateRiskAssessmentCampaignCommand(
                        request.title(),
                        request.owner(),
                        request.objective(),
                        request.methodologyProfileId(),
                        request.appetiteProfileId(),
                        request.scheduledStart(),
                        request.scheduledEnd(),
                        request.scope(),
                        request.approvalMetadata(),
                        request.scopedAssetIds())));
    }

    @PutMapping("/{id}/phase")
    public RiskAssessmentCampaignResponse advancePhase(
            @PathVariable UUID id,
            @Valid @RequestBody CampaignPhaseTransitionRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return RiskAssessmentCampaignResponse.from(service.advancePhase(projectId, id, request.phase()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        service.delete(projectId, id);
    }
}
