package com.keplerops.groundcontrol.api.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.BoundaryDeclaration;
import com.keplerops.groundcontrol.domain.derivation.service.CreateDerivationRunCommand;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationService;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/derivations")
public class DerivationController {

    private final DerivationService derivationService;
    private final ProjectService projectService;

    public DerivationController(DerivationService derivationService, ProjectService projectService) {
        this.derivationService = derivationService;
        this.projectService = projectService;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public DerivationRunResultResponse run(
            @Valid @RequestBody DerivationRunRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return DerivationRunResultResponse.from(derivationService.run(toCommand(projectId, request)));
    }

    @GetMapping("/runs")
    public List<DerivationRunResponse> listRuns(@RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return derivationService.listRuns(projectId).stream()
                .map(DerivationRunResponse::from)
                .toList();
    }

    @GetMapping("/runs/{id}")
    public DerivationRunResponse getRun(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return DerivationRunResponse.from(derivationService.getRun(projectId, id));
    }

    @GetMapping("/runs/{id}/boundary-model")
    public BoundaryModelSnapshotResponse getBoundaryModel(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return BoundaryModelSnapshotResponse.from(derivationService.getBoundaryModel(projectId, id));
    }

    @GetMapping("/facts")
    public List<SystemModelFactResponse> listFacts(
            @RequestParam(required = false) UUID runId,
            @RequestParam(required = false) SystemModelFactKind factKind,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return derivationService.listFacts(projectId, runId, factKind).stream()
                .map(SystemModelFactResponse::from)
                .toList();
    }

    @GetMapping("/capture-limits")
    public List<DerivationCaptureLimitResponse> listCaptureLimits(
            @RequestParam(required = false) UUID runId,
            @RequestParam(required = false) CaptureLimitReason reason,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return derivationService.listCaptureLimits(projectId, runId, reason).stream()
                .map(DerivationCaptureLimitResponse::from)
                .toList();
    }

    private CreateDerivationRunCommand toCommand(UUID projectId, DerivationRunRequest request) {
        return new CreateDerivationRunCommand(
                projectId,
                request.scopeMode(),
                request.commitSha(),
                request.baseCommitSha(),
                request.paths(),
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
                                .toList());
    }
}
