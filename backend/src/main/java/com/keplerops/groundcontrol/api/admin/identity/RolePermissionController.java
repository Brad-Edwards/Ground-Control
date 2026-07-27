package com.keplerops.groundcontrol.api.admin.identity;

import com.keplerops.groundcontrol.domain.identity.service.IdentityAdminService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identity/role-permissions")
public class RolePermissionController {

    private final IdentityAdminService service;

    public RolePermissionController(IdentityAdminService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityApiModels.IdentityRolePermissionResponse assign(
            @Valid @RequestBody IdentityApiModels.IdentityAssignPermissionRequest request) {
        return IdentityApiModels.IdentityRolePermissionResponse.from(
                service.assignPermission(request.roleId(), request.permission()));
    }

    @GetMapping
    public Page<IdentityApiModels.IdentityRolePermissionResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        return service.listPermissions(pageable).map(IdentityApiModels.IdentityRolePermissionResponse::from);
    }

    @PostMapping("/{id}/revoke")
    public IdentityApiModels.IdentityRolePermissionResponse revoke(@PathVariable UUID id) {
        return IdentityApiModels.IdentityRolePermissionResponse.from(service.revokePermission(id));
    }
}
