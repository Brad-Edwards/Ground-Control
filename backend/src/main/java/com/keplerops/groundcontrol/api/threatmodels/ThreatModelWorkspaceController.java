package com.keplerops.groundcontrol.api.threatmodels;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelWorkspaceService;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
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
 * Read-only workspace view for threat modeling per GC-Q010.
 *
 * <p>The literal {@code workspace} path segment is unambiguous under Spring's
 * {@code PathPatternParser} — it is not captured by the {@code \{id\}} pattern on
 * {@link ThreatModelController} which matches UUID-shaped segments.
 *
 * <p>Mirrors the param style of {@code GrcAnalysisController.evidenceFreshness}:
 * {@code @DateTimeFormat(iso=DATE_TIME)} for {@code asOf} and
 * {@code @Positive} default for {@code freshnessWindowDays}.
 */
@RestController
@RequestMapping("/api/v1/threat-models")
@Validated
public class ThreatModelWorkspaceController {

    private static final int DEFAULT_FRESHNESS_WINDOW_DAYS = 90;

    private final ThreatModelWorkspaceService workspaceService;
    private final ProjectService projectService;

    public ThreatModelWorkspaceController(ThreatModelWorkspaceService workspaceService, ProjectService projectService) {
        this.workspaceService = workspaceService;
        this.projectService = projectService;
    }

    @GetMapping("/workspace")
    public ThreatModelWorkspaceResponse workspace(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(required = false) StrideCategory stride,
            @RequestParam(required = false) ThreatModelStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays) {
        UUID projectId = projectService.resolveProjectId(project);
        return ThreatModelWorkspaceResponse.from(
                workspaceService.workspace(projectId, asOf, freshnessWindowDays, assetId, stride, status));
    }
}
