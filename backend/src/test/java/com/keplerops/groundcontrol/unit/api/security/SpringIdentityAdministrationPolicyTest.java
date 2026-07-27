package com.keplerops.groundcontrol.unit.api.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.AuthorizationException;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAuthorizationService;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.shared.security.IdentityPrincipal;
import com.keplerops.groundcontrol.shared.security.SecurityProperties;
import com.keplerops.groundcontrol.shared.security.SpringIdentityAdministrationPolicy;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SpringIdentityAdministrationPolicyTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Mock
    private IdentityAuthorizationService authorizationService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegationRequiresTheExactPermissionAtTheTargetProject() {
        authenticateIdentity();
        when(authorizationService.isAllowed(USER_ID, PermissionKey.PROJECT_WRITE, PROJECT_ID))
                .thenReturn(true);

        policy(true).requirePermissionDelegation(PermissionKey.PROJECT_WRITE, PROJECT_ID);

        verify(authorizationService).isAllowed(USER_ID, PermissionKey.PROJECT_WRITE, PROJECT_ID);
    }

    @Test
    void projectAdmissionDelegationRequiresProjectAccessAdmin() {
        authenticateIdentity();
        when(authorizationService.isAllowed(USER_ID, PermissionKey.PROJECT_ACCESS_ADMIN, PROJECT_ID))
                .thenReturn(false);
        var policy = policy(true);

        assertThatThrownBy(() -> policy.requireProjectAccessDelegation(PROJECT_ID))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void missingIdentityAndLegacyAdminRoleIsDenied() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("legacy-user", null, "ROLE_USER"));
        var policy = policy(true);

        assertThatThrownBy(policy::requireIdentityAdministration).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void disabledSecurityPreservesTheExistingPermitAllDevelopmentMode() {
        var policy = policy(false);

        assertThatCode(policy::requireIdentityAdministration).doesNotThrowAnyException();
    }

    private static void authenticateIdentity() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new TestingAuthenticationToken(new IdentityPrincipal(USER_ID, "alice"), null, "ROLE_USER"));
    }

    private SpringIdentityAdministrationPolicy policy(boolean enabled) {
        var properties = new SecurityProperties();
        properties.setEnabled(enabled);
        return new SpringIdentityAdministrationPolicy(authorizationService, properties);
    }
}
