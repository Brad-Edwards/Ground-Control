package com.keplerops.groundcontrol.api.threatenumeration;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationService;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for deterministic STRIDE threat enumeration over the derived architecture model
 * (GC-GRC-007). Both endpoints are read-only and authenticated; they fall through to the
 * {@code /api/v1/**} authenticated rule in {@code ApiPathMatrix}. Pack writes remain behind the
 * ADMIN pack-registry gate.
 */
@RestController
@RequestMapping("/api/v1/threat-enumeration")
@Validated
public class ThreatEnumerationController {

    private final ThreatEnumerationService enumerationService;
    private final ProjectService projectService;

    public ThreatEnumerationController(ThreatEnumerationService enumerationService, ProjectService projectService) {
        this.enumerationService = enumerationService;
        this.projectService = projectService;
    }

    /**
     * Enumerate STRIDE threats for the project's latest architecture-model snapshot, or for a
     * specific snapshot when {@code snapshotId} is provided. {@code packId} is required; {@code
     * version} is optional (null / blank → latest available version).
     */
    @GetMapping
    public ThreatEnumerationResponse enumerate(
            @RequestParam(required = false) String project,
            @RequestParam String packId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) UUID snapshotId) {
        UUID projectId = projectService.resolveProjectId(project);
        var result = snapshotId == null
                ? enumerationService.enumerateLatest(projectId, packId, version)
                : enumerationService.enumerateSnapshot(projectId, snapshotId, packId, version);
        return ThreatEnumerationResponse.from(projectService.resolveProjectIdentifier(project), result);
    }
}
