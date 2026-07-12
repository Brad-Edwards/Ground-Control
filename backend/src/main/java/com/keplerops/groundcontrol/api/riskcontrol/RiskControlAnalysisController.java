package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlCoverageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis/risk-control")
public class RiskControlAnalysisController {

    private final RiskControlCoverageService coverageService;
    private final ProjectService projectService;

    public RiskControlAnalysisController(RiskControlCoverageService coverageService, ProjectService projectService) {
        this.coverageService = coverageService;
        this.projectService = projectService;
    }

    /** C5a — Scenarios with no mapped controls. */
    @GetMapping("/unmapped-scenarios")
    public UnmappedScenariosResponse unmappedScenarios(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var scenarios = coverageService.findUnmappedScenarios(projectId);
        var summaries = scenarios.stream()
                .map(UnmappedScenariosResponse.ScenarioSummary::from)
                .toList();
        return new UnmappedScenariosResponse(summaries);
    }

    /** C6 — Controls not mapped to any relevant scenario. */
    @GetMapping("/unmapped-controls")
    public UnmappedControlsResponse unmappedControls(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var controls = coverageService.findUnmappedControls(projectId);
        var summaries = controls.stream()
                .map(UnmappedControlsResponse.ControlSummary::from)
                .toList();
        return new UnmappedControlsResponse(summaries);
    }

    /** GC-H006 — Threat model entries with no mapped controls. */
    @GetMapping("/unmapped-threats")
    public UnmappedThreatsResponse unmappedThreats(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var threats = coverageService.findUnmappedThreats(projectId);
        var summaries = threats.stream()
                .map(UnmappedThreatsResponse.ThreatSummary::from)
                .toList();
        return new UnmappedThreatsResponse(summaries);
    }

    /** GC-H006 — Controls not mapped to any threat model entry. */
    @GetMapping("/threat-unmapped-controls")
    public UnmappedControlsResponse threatUnmappedControls(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var controls = coverageService.findControlsUnmappedToThreats(projectId);
        var summaries = controls.stream()
                .map(UnmappedControlsResponse.ControlSummary::from)
                .toList();
        return new UnmappedControlsResponse(summaries);
    }
}
