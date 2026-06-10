package com.keplerops.groundcontrol.api.riskscenarios;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskScenarioWorkspaceService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only workspace view for risk scenario management per GC-Q009.
 *
 * <p>The literal {@code workspace} path segment is unambiguous under Spring's
 * {@code PathPatternParser} — it is not captured by the {@code \{id\}} pattern on
 * {@link RiskScenarioController} which matches UUID-shaped segments.
 *
 * <p>Mirrors the param style of {@link com.keplerops.groundcontrol.api.threatmodels.ThreatModelWorkspaceController}.
 */
@RestController
@RequestMapping("/api/v1/risk-scenarios")
@Validated
public class RiskScenarioWorkspaceController {

    private static final int DEFAULT_FRESHNESS_WINDOW_DAYS = 90;

    private final RiskScenarioWorkspaceService workspaceService;
    private final ProjectService projectService;

    public RiskScenarioWorkspaceController(
            RiskScenarioWorkspaceService workspaceService, ProjectService projectService) {
        this.workspaceService = workspaceService;
        this.projectService = projectService;
    }

    @GetMapping("/workspace")
    public RiskScenarioWorkspaceResponse workspace(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) UUID assetId,
            @RequestParam(required = false) RiskScenarioStatus status,
            @RequestParam(required = false) UUID methodologyProfileId,
            @RequestParam(required = false) RiskAssessmentApprovalStatus approvalState,
            @RequestParam(required = false) TreatmentPlanStatus treatmentStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_FRESHNESS_WINDOW_DAYS) @Positive int freshnessWindowDays,
            @RequestParam(required = false, defaultValue = "") String compare) {
        UUID projectId = projectService.resolveProjectId(project);
        List<UUID> compareIds = parseCompareIds(compare);
        return RiskScenarioWorkspaceResponse.from(workspaceService.workspace(
                projectId,
                asOf,
                freshnessWindowDays,
                assetId,
                status,
                methodologyProfileId,
                approvalState,
                treatmentStatus,
                compareIds));
    }

    /**
     * Parses a comma-separated string of UUID values into a list.
     * Empty or blank input returns an empty list.
     */
    private static List<UUID> parseCompareIds(String compare) {
        if (compare == null || compare.isBlank()) {
            return List.of();
        }
        return Arrays.stream(compare.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();
    }
}
