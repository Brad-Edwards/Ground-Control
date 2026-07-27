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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identity/memberships")
public class GroupMembershipController {

    private final IdentityAdminService service;

    public GroupMembershipController(IdentityAdminService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityApiModels.IdentityMembershipResponse create(
            @Valid @RequestBody IdentityApiModels.IdentityCreateMembershipRequest request) {
        return IdentityApiModels.IdentityMembershipResponse.from(
                service.createMembership(new IdentityCommands.CreateMembership(
                        request.userId(), request.groupId(), request.effectiveFrom(), request.effectiveUntil())));
    }

    @GetMapping
    public Page<IdentityApiModels.IdentityMembershipResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        return service.listMemberships(pageable).map(IdentityApiModels.IdentityMembershipResponse::from);
    }

    @PostMapping("/{id}/revoke")
    public IdentityApiModels.IdentityMembershipResponse revoke(@PathVariable UUID id) {
        return IdentityApiModels.IdentityMembershipResponse.from(service.revokeMembership(id));
    }
}
