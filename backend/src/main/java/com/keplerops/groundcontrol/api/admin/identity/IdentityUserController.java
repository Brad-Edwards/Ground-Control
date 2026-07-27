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
@RequestMapping("/api/v1/admin/identity/users")
public class IdentityUserController {

    private final IdentityAdminService service;

    public IdentityUserController(IdentityAdminService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityApiModels.IdentityUserResponse create(
            @Valid @RequestBody IdentityApiModels.IdentityCreateUserRequest request) {
        return IdentityApiModels.IdentityUserResponse.from(service.createUser(
                new IdentityCommands.CreateUser(request.loginName(), request.displayName(), request.kind())));
    }

    @GetMapping
    public Page<IdentityApiModels.IdentityUserResponse> list(@PageableDefault(size = 50) Pageable pageable) {
        return service.listUsers(pageable).map(IdentityApiModels.IdentityUserResponse::from);
    }

    @GetMapping("/{id}")
    public IdentityApiModels.IdentityUserResponse get(@PathVariable UUID id) {
        return IdentityApiModels.IdentityUserResponse.from(service.getUser(id));
    }

    @PatchMapping("/{id}")
    public IdentityApiModels.IdentityUserResponse update(
            @PathVariable UUID id, @Valid @RequestBody IdentityApiModels.IdentityUpdateUserRequest request) {
        return IdentityApiModels.IdentityUserResponse.from(
                service.updateUser(id, new IdentityCommands.UpdateUser(request.displayName(), request.state())));
    }
}
