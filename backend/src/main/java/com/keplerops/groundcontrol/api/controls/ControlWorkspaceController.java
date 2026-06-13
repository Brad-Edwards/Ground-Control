package com.keplerops.groundcontrol.api.controls;

import com.keplerops.groundcontrol.domain.controls.service.ControlWorkspaceService;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/controls")
@Validated
public class ControlWorkspaceController {

    private static final int DEFAULT_FRESHNESS_WINDOW_DAYS = 90;

    private final ControlWorkspaceService workspaceService;
    private final ProjectService projectService;

    public ControlWorkspaceController(ControlWorkspaceService workspaceService, ProjectService projectService) {
        this.workspaceService = workspaceService;
        this.projectService = projectService;
    }

    @GetMapping("/workspace")
    public ControlWorkspaceResponse workspace(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays,
            @RequestParam(required = false) ControlStatus status,
            @RequestParam(required = false) ControlFunction controlFunction,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String queue) {
        var projectId = projectService.resolveProjectId(project);
        return ControlWorkspaceResponse.from(workspaceService.workspace(
                projectId, asOf, freshnessWindowDays, status, controlFunction, owner, queue));
    }
}
