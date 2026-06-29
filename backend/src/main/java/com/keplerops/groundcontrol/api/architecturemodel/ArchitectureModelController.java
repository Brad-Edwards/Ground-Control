package com.keplerops.groundcontrol.api.architecturemodel;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementStateCommand;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelService;
import com.keplerops.groundcontrol.domain.architecturemodel.service.CreateArchitectureModelSnapshotCommand;
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
@RequestMapping("/api/v1/architecture-models")
public class ArchitectureModelController {

    private final ArchitectureModelService architectureModelService;
    private final ProjectService projectService;

    public ArchitectureModelController(
            ArchitectureModelService architectureModelService, ProjectService projectService) {
        this.architectureModelService = architectureModelService;
        this.projectService = projectService;
    }

    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public ArchitectureModelSnapshotResponse createSnapshot(
            @Valid @RequestBody ArchitectureModelSnapshotRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ArchitectureModelSnapshotResponse.from(
                architectureModelService.createSnapshot(toCommand(projectId, request)));
    }

    @GetMapping("/snapshots")
    public List<ArchitectureModelSnapshotSummaryResponse> listSnapshots(
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return architectureModelService.listSnapshots(projectId).stream()
                .map(ArchitectureModelSnapshotSummaryResponse::from)
                .toList();
    }

    @GetMapping("/snapshots/{id}")
    public ArchitectureModelSnapshotResponse getSnapshot(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ArchitectureModelSnapshotResponse.from(architectureModelService.getSnapshot(projectId, id));
    }

    @GetMapping("/elements")
    public List<ArchitectureModelElementResponse> listElements(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return architectureModelService.listElements(projectId).stream()
                .map(ArchitectureModelElementResponse::from)
                .toList();
    }

    @GetMapping("/elements/{id}")
    public ArchitectureModelElementResponse getElement(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ArchitectureModelElementResponse.from(architectureModelService.getElement(projectId, id));
    }

    @GetMapping("/diff")
    public ArchitectureModelDiffResponse diff(
            @RequestParam UUID fromSnapshotId,
            @RequestParam UUID toSnapshotId,
            @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        return ArchitectureModelDiffResponse.from(
                architectureModelService.diff(projectId, fromSnapshotId, toSnapshotId));
    }

    private CreateArchitectureModelSnapshotCommand toCommand(UUID projectId, ArchitectureModelSnapshotRequest request) {
        return new CreateArchitectureModelSnapshotCommand(
                projectId,
                request.modelVersion(),
                request.commitSha(),
                request.source(),
                request.createdBy(),
                request.elements().stream().map(this::toCommand).toList());
    }

    private ArchitectureModelElementStateCommand toCommand(ArchitectureModelElementRequest request) {
        return new ArchitectureModelElementStateCommand(
                request.stableKey(),
                request.elementKind(),
                request.label(),
                request.summary(),
                request.sourcePath(),
                request.trustBoundaryKey(),
                request.dataClassificationKey(),
                request.flowSourceStableKey(),
                request.flowTargetStableKey(),
                request.flowDirection(),
                request.provenanceSource(),
                request.provenanceKey(),
                request.adapterId(),
                request.toolName(),
                request.toolVersion(),
                request.rulesetName(),
                request.rulesetVersion(),
                request.derivationRunId(),
                request.commitSha(),
                request.metadata());
    }
}
