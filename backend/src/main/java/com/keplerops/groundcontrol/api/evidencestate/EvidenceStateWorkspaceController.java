package com.keplerops.groundcontrol.api.evidencestate;

import com.keplerops.groundcontrol.domain.evidencestate.service.EvidenceStateWorkspaceService;
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

@RestController
@RequestMapping("/api/v1/evidence-state")
@Validated
public class EvidenceStateWorkspaceController {

    private static final int DEFAULT_FRESHNESS_WINDOW_DAYS = 90;

    private final EvidenceStateWorkspaceService workspaceService;
    private final ProjectService projectService;

    public EvidenceStateWorkspaceController(
            EvidenceStateWorkspaceService workspaceService, ProjectService projectService) {
        this.workspaceService = workspaceService;
        this.projectService = projectService;
    }

    @GetMapping("/workspace")
    public EvidenceStateWorkspaceResponse workspace(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays,
            @RequestParam(required = false, defaultValue = "false") boolean includeSuperseded,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(required = false) UUID controlId) {
        var projectId = projectService.resolveProjectId(project);
        return EvidenceStateWorkspaceResponse.from(workspaceService.workspace(
                projectId, asOf, freshnessWindowDays, includeSuperseded, assetId, controlId));
    }
}
