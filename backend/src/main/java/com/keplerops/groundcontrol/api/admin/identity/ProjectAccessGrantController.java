package com.keplerops.groundcontrol.api.admin.identity;

import com.keplerops.groundcontrol.domain.identity.service.IdentityAdminService;
import com.keplerops.groundcontrol.domain.identity.service.IdentityCommands;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identity/project-access-grants")
public class ProjectAccessGrantController {

    private final IdentityAdminService service;
    private final ProjectService projectService;

    public ProjectAccessGrantController(IdentityAdminService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityApiModels.IdentityProjectAccessGrantResponse create(
            @Valid @RequestBody IdentityApiModels.IdentityCreateProjectAccessGrantRequest request,
            @RequestParam String project) {
        UUID projectId = projectService.requireProjectId(project);
        return IdentityApiModels.IdentityProjectAccessGrantResponse.from(
                service.createProjectAccessGrant(new IdentityCommands.CreateProjectAccessGrant(
                        request.userId(),
                        request.groupId(),
                        projectId,
                        request.effectiveFrom(),
                        request.effectiveUntil())));
    }

    @GetMapping
    public Page<IdentityApiModels.IdentityProjectAccessGrantResponse> list(
            @PageableDefault(size = 50) Pageable pageable) {
        return service.listProjectAccessGrants(pageable)
                .map(IdentityApiModels.IdentityProjectAccessGrantResponse::from);
    }

    @PostMapping("/{id}/revoke")
    public IdentityApiModels.IdentityProjectAccessGrantResponse revoke(@PathVariable UUID id) {
        return IdentityApiModels.IdentityProjectAccessGrantResponse.from(service.revokeProjectAccessGrant(id));
    }
}
