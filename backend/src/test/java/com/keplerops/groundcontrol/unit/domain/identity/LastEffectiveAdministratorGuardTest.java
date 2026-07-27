package com.keplerops.groundcontrol.unit.domain.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.identity.repository.EffectiveAuthorizationRepository;
import com.keplerops.groundcontrol.domain.identity.service.LastEffectiveAdministratorGuard;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

class LastEffectiveAdministratorGuardTest {

    @Test
    void serializedPostMutationCheckRejectsRemovingTheLastAdministrator() {
        var repository = mock(EffectiveAuthorizationRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var entityManager = mock(EntityManager.class);
        var now = Instant.parse("2026-07-27T00:00:00Z");
        var guard =
                new LastEffectiveAdministratorGuard(repository, jdbc, entityManager, Clock.fixed(now, ZoneOffset.UTC));
        when(repository.hasAnyEffectiveGlobalPermission(
                        com.keplerops.groundcontrol.domain.identity.state.PermissionKey.IDENTITY_ADMIN, now))
                .thenReturn(true, false);
        Runnable mutation = mock(Runnable.class);

        assertThatThrownBy(() -> guard.protect(mutation))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("last effective identity administrator");

        verify(jdbc).execute(eq("SELECT pg_advisory_xact_lock(?)"), any(PreparedStatementCallback.class));
        var order = inOrder(repository, mutation, entityManager);
        order.verify(repository)
                .hasAnyEffectiveGlobalPermission(
                        com.keplerops.groundcontrol.domain.identity.state.PermissionKey.IDENTITY_ADMIN, now);
        order.verify(mutation).run();
        order.verify(entityManager).flush();
        order.verify(repository)
                .hasAnyEffectiveGlobalPermission(
                        com.keplerops.groundcontrol.domain.identity.state.PermissionKey.IDENTITY_ADMIN, now);
    }
}
