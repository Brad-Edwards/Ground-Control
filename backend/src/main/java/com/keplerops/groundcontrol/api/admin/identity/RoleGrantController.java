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
@RequestMapping("/api/v1/admin/identity/role-grants")
public class RoleGrantController {

    private final IdentityAdminService service;
    private final ProjectService projectService;

    public RoleGrantController(IdentityAdminService service, ProjectService projectService) {
        this.service = service;
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityApiModels.IdentityRoleGrantResponse create(
            @Valid @RequestBody IdentityApiModels.IdentityCreateRoleGrantRequest request,
            @RequestParam(required = false) String project) {
        UUID projectId = project == null ? null : projectService.requireProjectId(project);
        return IdentityApiModels.IdentityRoleGrantResponse.from(
                service.createRoleGrant(new IdentityCommands.CreateRoleGrant(
                        request.roleId(),
                        request.userId(),
                        request.groupId(),
                        projectId,
                        request.effectiveFrom(),
                        request.effectiveUntil())));
    }

    @GetMapping
    public Page<IdentityApiModels.IdentityRoleGrantResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        return service.listRoleGrants(pageable).map(IdentityApiModels.IdentityRoleGrantResponse::from);
    }

    @PostMapping("/{id}/revoke")
    public IdentityApiModels.IdentityRoleGrantResponse revoke(@PathVariable UUID id) {
        return IdentityApiModels.IdentityRoleGrantResponse.from(service.revokeRoleGrant(id));
    }
}
