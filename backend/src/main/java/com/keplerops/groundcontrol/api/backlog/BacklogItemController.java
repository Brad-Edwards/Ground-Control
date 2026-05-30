package com.keplerops.groundcontrol.api.backlog;

import com.keplerops.groundcontrol.domain.backlog.service.BacklogItemService;
import com.keplerops.groundcontrol.domain.backlog.service.CreateBacklogItemCommand;
import com.keplerops.groundcontrol.domain.backlog.service.UpdateBacklogItemCommand;
import com.keplerops.groundcontrol.domain.backlog.service.WsjfAnalysisService;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/v1/backlog-items")
public class BacklogItemController {

    /**
     * Hard upper bound on caller-controlled WSJF Monte Carlo iterations. Every
     * iteration allocates an 8-byte sample slot plus a boxed Double in the JSON
     * response, so an unbounded request param would let an authenticated caller
     * trigger OOM. 1,000,000 keeps the worst-case heap allocation at roughly
     * 8 MB of primitives plus ~24-32 MB of boxed Doubles, which is large but
     * recoverable under normal heap sizing.
     */
    static final int MAX_WSJF_ITERATIONS = 1_000_000;

    private final BacklogItemService backlogItemService;
    private final WsjfAnalysisService wsjfAnalysisService;
    private final ProjectService projectService;

    public BacklogItemController(
            BacklogItemService backlogItemService,
            WsjfAnalysisService wsjfAnalysisService,
            ProjectService projectService) {
        this.backlogItemService = backlogItemService;
        this.wsjfAnalysisService = wsjfAnalysisService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BacklogItemResponse create(
            @Valid @RequestBody BacklogItemRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var command = new CreateBacklogItemCommand(
                projectId,
                request.uid(),
                request.title(),
                request.description(),
                request.userBusinessValue(),
                request.timeCriticality(),
                request.riskReductionOpportunityEnablement(),
                request.jobDuration());
        return BacklogItemResponse.from(backlogItemService.create(command));
    }

    @GetMapping
    public List<BacklogItemResponse> list(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return backlogItemService.listByProject(projectId).stream()
                .map(BacklogItemResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public BacklogItemResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return BacklogItemResponse.from(backlogItemService.getById(projectId, id));
    }

    @GetMapping("/uid/{uid}")
    public BacklogItemResponse getByUid(@PathVariable String uid, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return BacklogItemResponse.from(backlogItemService.getByUid(projectId, uid));
    }

    @PutMapping("/{id}")
    public BacklogItemResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBacklogItemRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var command = new UpdateBacklogItemCommand(
                request.title(),
                request.description(),
                request.userBusinessValue(),
                request.timeCriticality(),
                request.riskReductionOpportunityEnablement(),
                request.jobDuration());
        return BacklogItemResponse.from(backlogItemService.update(projectId, id, command));
    }

    @PutMapping("/{id}/status")
    public BacklogItemResponse transitionStatus(
            @PathVariable UUID id,
            @Valid @RequestBody BacklogItemStatusRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return BacklogItemResponse.from(backlogItemService.transitionStatus(projectId, id, request.status()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        backlogItemService.delete(projectId, id);
    }

    @GetMapping("/{id}/wsjf")
    public WsjfDistributionResponse computeWsjf(
            @PathVariable UUID id,
            @RequestParam(required = false) String project,
            @RequestParam(defaultValue = "0") long seed,
            @RequestParam(defaultValue = "10000") int iterations) {
        // Reject caller-supplied iteration counts that would let an
        // authenticated user push the JVM into OOM through this endpoint. The
        // domain layer also rejects iterations <= 0, but only enforces a lower
        // bound; the response-shape allocator (double[] + boxed List<Double>)
        // is what actually exhausts heap when the upper bound is missing.
        if (iterations <= 0 || iterations > MAX_WSJF_ITERATIONS) {
            throw new DomainValidationException(
                    "iterations must be between 1 and " + MAX_WSJF_ITERATIONS + ", got " + iterations,
                    "validation_error",
                    Map.of(
                            "field",
                            "iterations",
                            "max",
                            String.valueOf(MAX_WSJF_ITERATIONS),
                            "requested",
                            String.valueOf(iterations)));
        }
        var projectId = projectService.resolveProjectId(project);
        var dist = wsjfAnalysisService.computeForItem(projectId, id, seed, iterations);
        return WsjfDistributionResponse.from(id, dist);
    }
}
