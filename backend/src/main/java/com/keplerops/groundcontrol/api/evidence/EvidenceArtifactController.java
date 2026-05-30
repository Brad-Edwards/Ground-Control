package com.keplerops.groundcontrol.api.evidence;

import com.keplerops.groundcontrol.domain.evidence.service.CreateEvidenceArtifactCommand;
import com.keplerops.groundcontrol.domain.evidence.service.EvidenceArtifactService;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
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
 * REST surface for the GC-M016 summarized-evidence layer (ADR-045).
 *
 * <p>The controller is intentionally append-only: there is no PUT, no DELETE,
 * and no mutation route other than {@code /supersede}, which creates a new
 * artifact and links the prior one as superseded exactly once. Append-only
 * enforcement is the API-boundary half of clause C2 of GC-M016 ("without
 * overwriting prior state"); the service layer is the second half.
 */
@RestController
@RequestMapping("/api/v1/evidence-artifacts")
public class EvidenceArtifactController {

    private final EvidenceArtifactService service;
    private final ProjectService projectService;

    public EvidenceArtifactController(EvidenceArtifactService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceArtifactResponse create(
            @Valid @RequestBody EvidenceArtifactRequest request, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceArtifactResponse.from(service.create(toCreateCommand(projectId, request)));
    }

    @GetMapping
    public List<EvidenceArtifactResponse> list(
            @RequestParam(required = false) EvidenceType evidenceType,
            @RequestParam(required = false, defaultValue = "false") boolean includeSuperseded,
            @RequestParam(required = false, defaultValue = "false") boolean expiredOnly,
            @RequestParam(required = false) Boolean expired,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        var now = java.time.Instant.now();
        // GC-I004: optional expiry filter. Pass expired=true to fetch only
        // already-expired artifacts (alias: expiredOnly), expired=false to
        // exclude them. Default returns the full set so existing callers see
        // unchanged behavior. The expiry decision lives in the service so the
        // api package does not depend on domain.model.* (ArchUnit boundary).
        Boolean expiredFlag = expired != null ? expired : (expiredOnly ? Boolean.TRUE : null);
        var rows = expiredFlag == null
                ? service.listByProject(projectId, evidenceType, includeSuperseded)
                : service.listByProjectFilteredByExpiry(projectId, evidenceType, includeSuperseded, now, expiredFlag);
        return rows.stream().map(a -> EvidenceArtifactResponse.from(a, now)).toList();
    }

    @GetMapping("/{id}")
    public EvidenceArtifactResponse getById(@PathVariable UUID id, @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceArtifactResponse.from(service.getById(projectId, id));
    }

    @PostMapping("/{id}/supersede")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceArtifactResponse supersede(
            @PathVariable UUID id,
            @Valid @RequestBody EvidenceArtifactRequest request,
            @RequestParam(required = false) String project) {
        var projectId = projectService.requireProjectId(project);
        return EvidenceArtifactResponse.from(service.supersede(projectId, id, toCreateCommand(projectId, request)));
    }

    private CreateEvidenceArtifactCommand toCreateCommand(UUID projectId, EvidenceArtifactRequest request) {
        var sources =
                request.sources().stream().map(EvidenceSourceRefDto::toDomain).toList();
        return new CreateEvidenceArtifactCommand(
                projectId,
                request.uid(),
                request.title(),
                request.summary(),
                request.evidenceType(),
                request.derivationMethod(),
                request.derivedAt(),
                request.assuranceLevel(),
                request.confidence(),
                request.notes(),
                sources,
                request.expiresAt(),
                request.validityWindowDays());
    }
}
