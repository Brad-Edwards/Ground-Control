package com.keplerops.groundcontrol.api.decisions;

import com.keplerops.groundcontrol.domain.decisions.service.CreateDecisionAnalysisRecordCommand;
import com.keplerops.groundcontrol.domain.decisions.service.DecisionAnalysisRecordService;
import com.keplerops.groundcontrol.domain.decisions.service.UpdateDecisionAnalysisRecordCommand;
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

@RestController
@RequestMapping("/api/v1/decisions")
public class DecisionAnalysisRecordController {

    private final DecisionAnalysisRecordService service;
    private final ProjectService projectService;

    public DecisionAnalysisRecordController(DecisionAnalysisRecordService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionAnalysisRecordResponse create(
            @Valid @RequestBody DecisionAnalysisRecordRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var command = new CreateDecisionAnalysisRecordCommand(
                projectId,
                request.uid(),
                request.title(),
                request.modelName(),
                request.summary(),
                request.inputs(),
                request.simulationParameters(),
                request.results(),
                request.alternatives(),
                request.chosenAlternative(),
                request.rationale());
        return DecisionAnalysisRecordResponse.from(service.create(command));
    }

    @GetMapping
    public List<DecisionAnalysisRecordResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return service.listByProject(projectId).stream()
                .map(DecisionAnalysisRecordResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public DecisionAnalysisRecordResponse getById(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return DecisionAnalysisRecordResponse.from(service.getById(projectId, id));
    }

    @GetMapping("/uid/{uid}")
    public DecisionAnalysisRecordResponse getByUid(
            @PathVariable String uid, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return DecisionAnalysisRecordResponse.from(service.getByUid(projectId, uid));
    }

    @PutMapping("/{id}")
    public DecisionAnalysisRecordResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDecisionAnalysisRecordRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var command = new UpdateDecisionAnalysisRecordCommand(
                request.title(),
                request.modelName(),
                request.summary(),
                request.inputs(),
                request.simulationParameters(),
                request.results(),
                request.alternatives(),
                request.chosenAlternative(),
                request.rationale());
        return DecisionAnalysisRecordResponse.from(service.update(projectId, id, command));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        service.delete(projectId, id);
    }
}
