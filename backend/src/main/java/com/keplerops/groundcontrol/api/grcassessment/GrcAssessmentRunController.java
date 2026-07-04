package com.keplerops.groundcontrol.api.grcassessment;

import com.keplerops.groundcontrol.domain.derivation.service.BoundaryDeclaration;
import com.keplerops.groundcontrol.domain.grcassessment.service.CreateGrcAssessmentRunCommand;
import com.keplerops.groundcontrol.domain.grcassessment.service.GrcAssessmentRunService;
import com.keplerops.groundcontrol.domain.grcassessment.service.ReviewGrcAssessmentRunCommand;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/grc-assessment-runs")
@Validated
public class GrcAssessmentRunController {

    private final GrcAssessmentRunService assessmentRunService;
    private final ProjectService projectService;

    public GrcAssessmentRunController(GrcAssessmentRunService assessmentRunService, ProjectService projectService) {
        this.assessmentRunService = assessmentRunService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GrcAssessmentRunResponse create(
            @Valid @RequestBody GrcAssessmentRunRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return GrcAssessmentRunResponse.from(assessmentRunService.createRun(toCommand(projectId, request)));
    }

    @PostMapping("/{id}/review")
    public GrcAssessmentRunResponse review(
            @PathVariable UUID id,
            @Valid @RequestBody GrcAssessmentReviewRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return GrcAssessmentRunResponse.from(assessmentRunService.reviewRun(new ReviewGrcAssessmentRunCommand(
                projectId, id, request.reviewDecision(), request.reviewedBy(), request.reviewRationale())));
    }

    @GetMapping("/{id}")
    public GrcAssessmentRunResponse get(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return GrcAssessmentRunResponse.from(assessmentRunService.getRun(projectId, id));
    }

    @GetMapping
    public List<GrcAssessmentRunResponse> list(
            @RequestParam(required = false) String project,
            @RequestParam(required = false, defaultValue = "25") int limit) {
        var projectId = projectService.requireProjectId(project);
        return assessmentRunService.listRuns(projectId, limit).stream()
                .map(GrcAssessmentRunResponse::from)
                .toList();
    }

    private static CreateGrcAssessmentRunCommand toCommand(UUID projectId, GrcAssessmentRunRequest request) {
        return new CreateGrcAssessmentRunCommand(
                projectId,
                request.mode(),
                request.scopeType(),
                request.scopeValues(),
                request.commitSha(),
                request.baseCommitSha(),
                request.languages(),
                request.surfaces(),
                request.declaredBoundaries() == null
                        ? List.of()
                        : request.declaredBoundaries().stream()
                                .map(boundary -> new BoundaryDeclaration(
                                        boundary.key(),
                                        boundary.name(),
                                        boundary.description(),
                                        boundary.pathSelectors(),
                                        boundary.surfaces()))
                                .toList(),
                request.threatPackId(),
                request.threatPackVersion(),
                request.reviewPolicy(),
                request.reviewDecision(),
                request.idempotencyKey(),
                request.partitionLimit());
    }
}
