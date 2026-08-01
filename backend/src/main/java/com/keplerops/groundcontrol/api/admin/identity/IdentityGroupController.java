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
@RequestMapping("/api/v1/admin/identity/groups")
public class IdentityGroupController {

    private final IdentityAdminService service;

    public IdentityGroupController(IdentityAdminService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityApiModels.IdentityGroupResponse create(
            @Valid @RequestBody IdentityApiModels.IdentityCreateGroupRequest request) {
        return IdentityApiModels.IdentityGroupResponse.from(
                service.createGroup(new IdentityCommands.CreateGroup(request.name(), request.displayName())));
    }

    @GetMapping
    public Page<IdentityApiModels.IdentityGroupResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        return service.listGroups(pageable).map(IdentityApiModels.IdentityGroupResponse::from);
    }

    @GetMapping("/{id}")
    public IdentityApiModels.IdentityGroupResponse get(@PathVariable UUID id) {
        return IdentityApiModels.IdentityGroupResponse.from(service.getGroup(id));
    }

    @PatchMapping("/{id}")
    public IdentityApiModels.IdentityGroupResponse update(
            @PathVariable UUID id, @Valid @RequestBody IdentityApiModels.IdentityUpdateGroupRequest request) {
        return IdentityApiModels.IdentityGroupResponse.from(
                service.updateGroup(id, new IdentityCommands.UpdateGroup(request.displayName(), request.state())));
    }
}
