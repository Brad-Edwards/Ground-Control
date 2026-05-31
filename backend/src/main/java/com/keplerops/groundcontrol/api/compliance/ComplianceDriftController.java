package com.keplerops.groundcontrol.api.compliance;

import com.keplerops.groundcontrol.domain.compliance.service.ComplianceDriftDetectorService;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftCategory;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the GC-I004 compliance drift signal stream.
 *
 * <p>Drift events are append-only — there is no PUT, no DELETE. The only
 * mutating route is {@code POST /{id}/acknowledge}, which sets
 * {@code acknowledgedAt} exactly once so dashboards can hide noisy
 * acknowledged events without losing the row.
 *
 * <p>{@code GET /api/v1/compliance-drift-events} is the durable read of the
 * signal stream. {@code GET /api/v1/compliance-drift-events/liveness} is the
 * detector liveness probe — surfacing {@code lastDetectedAt},
 * {@code lastSweepAt}, and the unacknowledged-event count so a stalled
 * monitor cannot silently report 'compliant' (security note in the cluster
 * scope). The MCP surface for drift events is read-only via
 * {@code gc_query}; no new top-level MCP tool ships with this controller.
 */
@RestController
@RequestMapping("/api/v1/compliance-drift-events")
public class ComplianceDriftController {

    private final ComplianceDriftDetectorService service;
    private final ProjectService projectService;

    public ComplianceDriftController(ComplianceDriftDetectorService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @GetMapping
    public List<ComplianceDriftEventResponse> list(
            @RequestParam(required = false) ComplianceDriftCategory category,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return service.listByProject(projectId, category).stream()
                .map(ComplianceDriftEventResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ComplianceDriftEventResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ComplianceDriftEventResponse.from(service.getById(projectId, id));
    }

    @PostMapping("/{id}/acknowledge")
    public ComplianceDriftEventResponse acknowledge(
            @PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ComplianceDriftEventResponse.from(service.acknowledge(projectId, id));
    }

    @GetMapping("/liveness")
    public ComplianceDriftLivenessResponse liveness(@RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        var liveness = service.liveness(projectId);
        return ComplianceDriftLivenessResponse.from(liveness);
    }
}
