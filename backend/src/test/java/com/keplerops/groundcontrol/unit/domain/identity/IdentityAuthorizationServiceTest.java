package com.keplerops.groundcontrol.unit.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.identity.repository.EffectiveAuthorizationRepository;
import com.keplerops.groundcontrol.domain.identity.service.IdentityAuthorizationService;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityAuthorizationServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    @Mock
    private EffectiveAuthorizationRepository repository;

    private IdentityAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new IdentityAuthorizationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void globalPermissionDoesNotImplicitlyRequireOrGrantProjectAccess() {
        when(repository.hasEffectivePermission(USER_ID, PermissionKey.IDENTITY_ADMIN, null, NOW))
                .thenReturn(true);

        assertThat(service.isAllowed(USER_ID, PermissionKey.IDENTITY_ADMIN, null))
                .isTrue();

        verify(repository, never()).hasEffectiveProjectAccess(USER_ID, PROJECT_ID, NOW);
    }

    @Test
    void projectPermissionRequiresRolePathAndIndependentProjectAdmission() {
        when(repository.hasEffectivePermission(USER_ID, PermissionKey.PROJECT_READ, PROJECT_ID, NOW))
                .thenReturn(true);
        when(repository.hasEffectiveProjectAccess(USER_ID, PROJECT_ID, NOW)).thenReturn(false);

        assertThat(service.isAllowed(USER_ID, PermissionKey.PROJECT_READ, PROJECT_ID))
                .isFalse();
    }

    @Test
    void projectPermissionAllowsOnlyWhenBothConjunctsAreEffective() {
        when(repository.hasEffectivePermission(USER_ID, PermissionKey.PROJECT_WRITE, PROJECT_ID, NOW))
                .thenReturn(true);
        when(repository.hasEffectiveProjectAccess(USER_ID, PROJECT_ID, NOW)).thenReturn(true);

        assertThat(service.isAllowed(USER_ID, PermissionKey.PROJECT_WRITE, PROJECT_ID))
                .isTrue();
    }

    @Test
    void absentRolePathDeniesWithoutConsultingProjectAdmission() {
        when(repository.hasEffectivePermission(USER_ID, PermissionKey.PROJECT_READ, PROJECT_ID, NOW))
                .thenReturn(false);

        assertThat(service.isAllowed(USER_ID, PermissionKey.PROJECT_READ, PROJECT_ID))
                .isFalse();

        verify(repository, never()).hasEffectiveProjectAccess(USER_ID, PROJECT_ID, NOW);
    }
}
