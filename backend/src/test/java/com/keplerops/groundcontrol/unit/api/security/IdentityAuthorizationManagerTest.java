package com.keplerops.groundcontrol.unit.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.identity.service.IdentityAuthorizationService;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.shared.security.IdentityAuthorizationManager;
import com.keplerops.groundcontrol.shared.security.IdentityPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class IdentityAuthorizationManagerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Mock
    private IdentityAuthorizationService authorizationService;

    @Test
    void identityPrincipalUsesClosedCatalogPermission() {
        var authentication = new TestingAuthenticationToken(new IdentityPrincipal(USER_ID, "alice"), null, "ROLE_USER");
        when(authorizationService.isAllowed(USER_ID, PermissionKey.IDENTITY_ADMIN, null))
                .thenReturn(true);

        var decision = new IdentityAuthorizationManager(authorizationService).check(() -> authentication, null);

        assertThat(decision.isGranted()).isTrue();
        verify(authorizationService).isAllowed(USER_ID, PermissionKey.IDENTITY_ADMIN, null);
    }

    @Test
    void legacyAdminBridgeIsAcceptedWithoutDomainIdentity() {
        var authentication =
                new TestingAuthenticationToken("legacy-admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        var decision = new IdentityAuthorizationManager(authorizationService).check(() -> authentication, null);

        assertThat(decision.isGranted()).isTrue();
    }

    @Test
    void identityPrincipalWithoutPermissionIsDeniedByDefault() {
        var authentication = new TestingAuthenticationToken(new IdentityPrincipal(USER_ID, "alice"), null, "ROLE_USER");

        var decision = new IdentityAuthorizationManager(authorizationService).check(() -> authentication, null);

        assertThat(decision.isGranted()).isFalse();
        verify(authorizationService).isAllowed(USER_ID, PermissionKey.IDENTITY_ADMIN, null);
    }

    @Test
    void legacyUserWithoutDomainIdentityIsDenied() {
        var authentication = new TestingAuthenticationToken("legacy-user", null, "ROLE_USER");

        var decision = new IdentityAuthorizationManager(authorizationService).check(() -> authentication, null);

        assertThat(decision.isGranted()).isFalse();
    }
}
