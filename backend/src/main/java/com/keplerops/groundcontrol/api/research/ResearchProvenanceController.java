package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.service.ResearchProvenanceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * GC-RSCH-R004 / GC-RSCH-N002 / GC-RSCH-N004 / ADR-069 — REST surface for the
 * research provenance ledger. Routes live under
 * {@code /api/v1/research-runs/{runId}/provenance/**} so the shared auth +
 * actor-filter chains apply via the {@code /api/v1/**} {@code .authenticated()}
 * rule in {@code ApiPathMatrix}. The controller only resolves the project and
 * forwards a request DTO's {@code toCommand()} to the service; all write legality
 * (run scoping, self-edge/cycle rejection, idempotency, supersession, content
 * bounding) and read traversal are owned by {@link ResearchProvenanceService}.
 * Cross-project / cross-run references are concealed as {@code 404}.
 */
@RestController
@RequestMapping("/api/v1/research-runs/{runId}/provenance")
public class ResearchProvenanceController {

    private final ResearchProvenanceService provenanceService;
    private final ProjectService projectService;

    public ResearchProvenanceController(ResearchProvenanceService provenanceService, ProjectService projectService) {
        this.provenanceService = provenanceService;
        this.projectService = projectService;
    }

    @PostMapping("/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    public ProvenanceNodeResponse recordNode(
            @PathVariable UUID runId,
            @Valid @RequestBody ProvenanceNodeRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ProvenanceNodeResponse.from(provenanceService.recordNode(projectId, runId, request.toCommand()));
    }

    @GetMapping("/nodes")
    public List<ProvenanceNodeResponse> listNodes(
            @PathVariable UUID runId, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return provenanceService.listNodes(projectId, runId).stream()
                .map(ProvenanceNodeResponse::from)
                .toList();
    }

    @PostMapping("/edges")
    @ResponseStatus(HttpStatus.CREATED)
    public ProvenanceEdgeResponse recordEdge(
            @PathVariable UUID runId,
            @Valid @RequestBody ProvenanceEdgeRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ProvenanceEdgeResponse.from(provenanceService.recordEdge(projectId, runId, request.toCommand()));
    }

    @GetMapping("/edges")
    public List<ProvenanceEdgeResponse> listEdges(
            @PathVariable UUID runId, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return provenanceService.listEdges(projectId, runId).stream()
                .map(ProvenanceEdgeResponse::from)
                .toList();
    }

    @GetMapping("/nodes/{nodeId}/chain")
    public ProvenanceChainResponse chain(
            @PathVariable UUID runId,
            @PathVariable UUID nodeId,
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return ProvenanceChainResponse.from(provenanceService.getProvenanceChain(projectId, runId, nodeId, depth));
    }
}
