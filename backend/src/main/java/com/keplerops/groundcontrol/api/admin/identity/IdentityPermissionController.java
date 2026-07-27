package com.keplerops.groundcontrol.api.admin.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identity/permissions")
public class IdentityPermissionController {

    @GetMapping
    public IdentityApiModels.IdentityPermissionCatalogResponse list() {
        return IdentityApiModels.IdentityPermissionCatalogResponse.current();
    }
}
