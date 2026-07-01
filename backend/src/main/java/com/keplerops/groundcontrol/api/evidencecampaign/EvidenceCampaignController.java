package com.keplerops.groundcontrol.api.evidencecampaign;

import com.keplerops.groundcontrol.domain.evidence.campaign.service.CreateEvidenceCampaignCommand;
import com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceCampaignService;
import com.keplerops.groundcontrol.domain.evidence.campaign.service.UpdateEvidenceCampaignCommand;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
 * REST surface for GC-S005 scheduled evidence-collection campaigns.
 *
 * <p>Campaigns are project-scoped. Create/update/pause/resume manage the
 * recurring directive; {@code /trigger} forces an immediate collection and
 * returns the resulting run (this route is admin-restricted in
 * {@code ApiPathMatrix} because it actively reaches out to external systems);
 * {@code /runs} lists prior executions.
 */
@RestController
@RequestMapping("/api/v1/evidence-campaigns")
public class EvidenceCampaignController {

    private final EvidenceCampaignService service;
    private final ProjectService projectService;

    public EvidenceCampaignController(EvidenceCampaignService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceCampaignResponse create(
            @Valid @RequestBody EvidenceCampaignRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceCampaignResponse.from(service.create(toCreateCommand(projectId, request)));
    }

    @GetMapping
    public List<EvidenceCampaignResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return service.listByProject(projectId).stream()
                .map(EvidenceCampaignResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public EvidenceCampaignResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceCampaignResponse.from(service.getById(projectId, id));
    }

    @PutMapping("/{id}")
    public EvidenceCampaignResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody EvidenceCampaignUpdateRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceCampaignResponse.from(service.update(projectId, id, toUpdateCommand(request)));
    }

    @PostMapping("/{id}/pause")
    public EvidenceCampaignResponse pause(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceCampaignResponse.from(service.pause(projectId, id));
    }

    @PostMapping("/{id}/resume")
    public EvidenceCampaignResponse resume(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceCampaignResponse.from(service.resume(projectId, id));
    }

    @PostMapping("/{id}/trigger")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceCampaignRunResponse trigger(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceCampaignRunResponse.from(service.trigger(projectId, id));
    }

    @GetMapping("/{id}/runs")
    public List<EvidenceCampaignRunResponse> runs(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return service.listRuns(projectId, id).stream()
                .map(EvidenceCampaignRunResponse::from)
                .toList();
    }

    private CreateEvidenceCampaignCommand toCreateCommand(UUID projectId, EvidenceCampaignRequest request) {
        return new CreateEvidenceCampaignCommand(
                projectId,
                request.uid(),
                request.name(),
                request.frequency(),
                request.adapterName(),
                request.scopeType(),
                request.schemaId(),
                request.connectionProfileId(),
                request.connectionEndpoint(),
                request.credentialRef(),
                request.scopeCriteria(),
                request.targetControlIds(),
                request.retentionDays(),
                request.firstRunAt());
    }

    private UpdateEvidenceCampaignCommand toUpdateCommand(EvidenceCampaignUpdateRequest request) {
        return new UpdateEvidenceCampaignCommand(
                request.name(),
                request.frequency(),
                request.scopeType(),
                request.schemaId(),
                request.connectionProfileId(),
                request.connectionEndpoint(),
                request.credentialRef(),
                request.scopeCriteria(),
                request.targetControlIds(),
                request.retentionDays());
    }
}
