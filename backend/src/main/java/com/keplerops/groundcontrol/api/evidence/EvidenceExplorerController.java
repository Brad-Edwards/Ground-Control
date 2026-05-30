package com.keplerops.groundcontrol.api.evidence;

import com.keplerops.groundcontrol.domain.evidence.service.EvidenceExplorerService;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only Evidence and State Explorer view per GC-Q012.
 *
 * <p>The literal {@code explorer} path segment is unambiguous under Spring's {@code PathPatternParser}
 * — it is not captured by the {@code \{id\}} pattern on {@link EvidenceArtifactController}. Freshness,
 * provenance, affected assets, linked controls (via evidence source kinds), and downstream finding
 * impact are composed read-only.
 */
@RestController
@RequestMapping("/api/v1/evidence-artifacts")
@Validated
public class EvidenceExplorerController {

    private static final int DEFAULT_FRESHNESS_WINDOW_DAYS = 90;

    private final EvidenceExplorerService explorerService;
    private final ProjectService projectService;

    public EvidenceExplorerController(EvidenceExplorerService explorerService, ProjectService projectService) {
        this.explorerService = explorerService;
        this.projectService = projectService;
    }

    @GetMapping("/explorer")
    public EvidenceExplorerResponse explorer(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(required = false) EvidenceType evidenceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays,
            @RequestParam(required = false, defaultValue = "true") boolean includeSuperseded) {
        UUID projectId = projectService.resolveProjectId(project);
        return EvidenceExplorerResponse.from(explorerService.explore(
                projectId, asOf, freshnessWindowDays, assetId, evidenceType, includeSuperseded));
    }
}
