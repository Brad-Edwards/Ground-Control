package com.keplerops.groundcontrol.api.controlidentification;

import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationService;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlMappingConfirmationService;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for deterministic control identification and mapping (GC-GRC-008). All routes are
 * authenticated via the shared {@code /api/v1/**} rule in {@code ApiPathMatrix}; the confirmation write
 * records through the same canonical aggregates (and at the same authorization level) as the existing
 * risk-control-mapping and threat-model-link write surfaces.
 */
@RestController
@RequestMapping("/api/v1/control-identification")
@Validated
public class ControlIdentificationController {

    private final ControlIdentificationService identificationService;
    private final ControlMappingConfirmationService confirmationService;
    private final ProjectService projectService;

    public ControlIdentificationController(
            ControlIdentificationService identificationService,
            ControlMappingConfirmationService confirmationService,
            ProjectService projectService) {
        this.identificationService = identificationService;
        this.confirmationService = confirmationService;
        this.projectService = projectService;
    }

    /**
     * Identify candidate controls (and control-design gaps) for the threats enumerated against the
     * project's latest architecture-model snapshot, or a specific snapshot when {@code snapshotId} is
     * provided. {@code threatPackId} is required; {@code version} is optional (null / blank → latest).
     */
    @GetMapping
    public ControlIdentificationResponse identify(
            @RequestParam(required = false) String project,
            @RequestParam String threatPackId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) UUID snapshotId) {
        UUID projectId = projectService.resolveProjectId(project);
        var result = snapshotId == null
                ? identificationService.identifyForLatestSnapshot(projectId, threatPackId, version)
                : identificationService.identifyForSnapshot(projectId, snapshotId, threatPackId, version);
        return ControlIdentificationResponse.from(projectService.resolveProjectIdentifier(project), result);
    }

    /** Return the controls recorded as covering a threat ("which controls cover threat X"). */
    @GetMapping("/coverage")
    public ControlCoverageResponse coverage(
            @RequestParam(required = false) String project, @RequestParam UUID threatModelId) {
        UUID projectId = projectService.resolveProjectId(project);
        var coverage = confirmationService.controlsCoveringThreat(projectId, threatModelId);
        return ControlCoverageResponse.from(projectService.resolveProjectIdentifier(project), coverage);
    }

    /**
     * Confirm a candidate control as a mitigation of a threat, recording the relationship through both
     * the {@code RiskControlMapping} coverage edge and the {@code ThreatModelLink MITIGATED_BY} traversal
     * edge. Idempotent. The control must be one the deterministic mapping engine selects for the threat
     * (validated server-side); a non-derived control is rejected as unprocessable.
     */
    @PostMapping("/confirmations")
    public ConfirmControlMappingResponse confirm(
            @RequestParam(required = false) String project, @Valid @RequestBody ConfirmControlMappingRequest request) {
        UUID projectId = projectService.resolveProjectId(project);
        var confirmation = confirmationService.confirm(
                projectId,
                request.threatModelId(),
                request.controlId(),
                request.controlRole(),
                request.mappingObjective(),
                request.mappingScope());
        return ConfirmControlMappingResponse.from(confirmation);
    }
}
