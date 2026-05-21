package com.keplerops.groundcontrol.api.riskcontrol;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlCoverageService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingFeedService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis/risk-control")
public class RiskControlAnalysisController {

    private final RiskControlCoverageService coverageService;
    private final RiskControlMappingFeedService feedService;
    private final ProjectService projectService;

    public RiskControlAnalysisController(
            RiskControlCoverageService coverageService,
            RiskControlMappingFeedService feedService,
            ProjectService projectService) {
        this.coverageService = coverageService;
        this.feedService = feedService;
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

    /** C5b — Records with no mapped controls (direct + optional transitive form). */
    @GetMapping("/unmapped-records")
    public UnmappedRecordsResponse unmappedRecords(
            @RequestParam(defaultValue = "true") boolean transitive, @RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var records = coverageService.findUnmappedRecords(projectId, transitive);
        var summaries = records.stream()
                .map(UnmappedRecordsResponse.RecordSummary::from)
                .toList();
        return new UnmappedRecordsResponse(summaries);
    }

    /** C6 — Controls not mapped to any relevant scenario (transitive-through-record). */
    @GetMapping("/unmapped-controls")
    public UnmappedControlsResponse unmappedControls(@RequestParam(required = false) String project) {
        var projectId = projectService.resolveProjectId(project);
        var controls = coverageService.findUnmappedControls(projectId);
        var summaries = controls.stream()
                .map(UnmappedControlsResponse.ControlSummary::from)
                .toList();
        return new UnmappedControlsResponse(summaries);
    }

    /** C7/C8 — Feed for a risk assessment result (effectiveness + observation/evidence inputs). */
    @GetMapping("/assessment-feed/{assessmentResultId}")
    public AssessmentFeedResponse assessmentFeed(
            @PathVariable UUID assessmentResultId, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return AssessmentFeedResponse.from(feedService.feedForAssessment(projectId, assessmentResultId));
    }
}
