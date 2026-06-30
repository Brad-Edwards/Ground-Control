package com.keplerops.groundcontrol.api.dataclassification;

import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationEvaluationService;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeService;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the project-scoped data classification lattice (GC-GRC-006). Lattice writes
 * ({@code PUT}/{@code DELETE}) are restricted to ROLE_ADMIN in {@code ApiPathMatrix} because
 * tampering with the policy or taxonomy would silently defeat the deterministic leak detection
 * (GC-TM-010); reads and evaluation are available to any authenticated project caller.
 */
@RestController
@RequestMapping("/api/v1/data-classification")
@Validated
public class DataClassificationController {

    private final DataClassificationLatticeService latticeService;
    private final DataClassificationEvaluationService evaluationService;
    private final ProjectService projectService;

    public DataClassificationController(
            DataClassificationLatticeService latticeService,
            DataClassificationEvaluationService evaluationService,
            ProjectService projectService) {
        this.latticeService = latticeService;
        this.evaluationService = evaluationService;
        this.projectService = projectService;
    }

    @GetMapping("/lattice")
    public DataClassificationLatticeResponse getLattice(@RequestParam(required = false) String project) {
        UUID projectId = projectService.resolveProjectId(project);
        return DataClassificationLatticeResponse.from(
                projectService.resolveProjectIdentifier(project), latticeService.resolveActiveDefinition(projectId));
    }

    @PutMapping("/lattice")
    public DataClassificationLatticeResponse putLattice(
            @Valid @RequestBody DataClassificationLatticeRequest request,
            @RequestParam(required = false) String project) {
        UUID projectId = projectService.resolveProjectId(project);
        return DataClassificationLatticeResponse.from(
                projectService.resolveProjectIdentifier(project),
                latticeService.replace(projectId, request.toCommand()));
    }

    @DeleteMapping("/lattice")
    public DataClassificationLatticeResponse resetLattice(@RequestParam(required = false) String project) {
        UUID projectId = projectService.resolveProjectId(project);
        return DataClassificationLatticeResponse.from(
                projectService.resolveProjectIdentifier(project), latticeService.resetToDefault(projectId));
    }

    @GetMapping("/evaluation")
    public DataClassificationEvaluationResponse evaluate(
            @RequestParam(required = false) String project, @RequestParam(required = false) UUID snapshotId) {
        UUID projectId = projectService.resolveProjectId(project);
        var result = snapshotId == null
                ? evaluationService.evaluateLatest(projectId)
                : evaluationService.evaluateSnapshot(projectId, snapshotId);
        return DataClassificationEvaluationResponse.from(projectService.resolveProjectIdentifier(project), result);
    }
}
