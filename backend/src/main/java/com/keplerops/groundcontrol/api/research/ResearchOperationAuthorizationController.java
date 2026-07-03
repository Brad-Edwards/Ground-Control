package com.keplerops.groundcontrol.api.research;

import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.service.ResearchOperationAuthorizationService;
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
 * GC-RSCH-R005 / GC-RSCH-N005 / GC-RSCH-N006 / ADR-086 — REST surface for
 * research high-risk operation authorizations. Routes live under
 * {@code /api/v1/research-runs/{runId}/operation-authorizations/**}; the request
 * and list/get reads apply via the shared {@code /api/v1/**}
 * {@code .authenticated()} rule, while the decision route (an {@code AUTONOMOUS}
 * run cannot approve its own proposals, ADR-086 §3) and the consume route (only
 * the trusted executor/operator may spend a one-time-use approval) are both
 * admin-gated in {@code ApiPathMatrix}. The controller only resolves the project and forwards a
 * request DTO's {@code toCommand()} to the service; all write legality (run
 * scoping, default-deny egress evaluation, idempotency, state transitions,
 * content bounding) is owned by {@link ResearchOperationAuthorizationService}.
 * Cross-project / cross-run references are concealed as {@code 404}.
 */
@RestController
@RequestMapping("/api/v1/research-runs/{runId}/operation-authorizations")
public class ResearchOperationAuthorizationController {

    private final ResearchOperationAuthorizationService authorizationService;
    private final ProjectService projectService;

    public ResearchOperationAuthorizationController(
            ResearchOperationAuthorizationService authorizationService, ProjectService projectService) {
        this.authorizationService = authorizationService;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OperationAuthorizationResponse request(
            @PathVariable UUID runId,
            @Valid @RequestBody OperationAuthorizationRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return OperationAuthorizationResponse.from(
                authorizationService.requestAuthorization(projectId, runId, request.toCommand()));
    }

    @GetMapping
    public List<OperationAuthorizationResponse> list(
            @PathVariable UUID runId, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return authorizationService.listAuthorizations(projectId, runId).stream()
                .map(OperationAuthorizationResponse::from)
                .toList();
    }

    @GetMapping("/{authorizationId}")
    public OperationAuthorizationResponse get(
            @PathVariable UUID runId,
            @PathVariable UUID authorizationId,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return OperationAuthorizationResponse.from(
                authorizationService.getAuthorization(projectId, runId, authorizationId));
    }

    @PostMapping("/{authorizationId}/decision")
    public OperationAuthorizationResponse decide(
            @PathVariable UUID runId,
            @PathVariable UUID authorizationId,
            @Valid @RequestBody OperationAuthorizationDecisionRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return OperationAuthorizationResponse.from(
                authorizationService.decideAuthorization(projectId, runId, authorizationId, request.toCommand()));
    }

    @PostMapping("/{authorizationId}/consume")
    public OperationAuthorizationResponse consume(
            @PathVariable UUID runId,
            @PathVariable UUID authorizationId,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return OperationAuthorizationResponse.from(
                authorizationService.consumeAuthorization(projectId, runId, authorizationId));
    }
}
