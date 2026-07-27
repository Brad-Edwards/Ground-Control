package com.keplerops.groundcontrol.api.admin.identity;

import com.keplerops.groundcontrol.domain.identity.service.IdentityAdminService;
import com.keplerops.groundcontrol.domain.identity.service.IdentityCommands;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identity/roles")
public class IdentityRoleController {

    private final IdentityAdminService service;

    public IdentityRoleController(IdentityAdminService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityApiModels.IdentityRoleResponse create(
            @Valid @RequestBody IdentityApiModels.IdentityCreateRoleRequest request) {
        return IdentityApiModels.IdentityRoleResponse.from(service.createRole(
                new IdentityCommands.CreateRole(request.key(), request.displayName(), request.description())));
    }

    @GetMapping
    public Page<IdentityApiModels.IdentityRoleResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        return service.listRoles(pageable).map(IdentityApiModels.IdentityRoleResponse::from);
    }

    @GetMapping("/{id}")
    public IdentityApiModels.IdentityRoleResponse get(@PathVariable UUID id) {
        return IdentityApiModels.IdentityRoleResponse.from(service.getRole(id));
    }

    @PatchMapping("/{id}")
    public IdentityApiModels.IdentityRoleResponse update(
            @PathVariable UUID id, @Valid @RequestBody IdentityApiModels.IdentityUpdateRoleRequest request) {
        return IdentityApiModels.IdentityRoleResponse.from(service.updateRole(
                id, new IdentityCommands.UpdateRole(request.displayName(), request.description(), request.state())));
    }
}
