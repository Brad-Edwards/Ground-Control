package com.keplerops.groundcontrol.unit.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import com.keplerops.groundcontrol.infrastructure.identity.JdbcEffectiveAuthorizationRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcEffectiveAuthorizationRepositoryTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    @Test
    void convertsNullableDatabaseExistenceResultsToAuthorizationDecisions() {
        var jdbc = mock(JdbcTemplate.class);
        var repository = new JdbcEffectiveAuthorizationRepository(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true, null, false);

        assertThat(repository.hasEffectivePermission(USER_ID, PermissionKey.PROJECT_READ, PROJECT_ID, NOW))
                .isTrue();
        assertThat(repository.hasEffectiveProjectAccess(USER_ID, PROJECT_ID, NOW))
                .isFalse();
        assertThat(repository.hasAnyEffectiveGlobalPermission(PermissionKey.IDENTITY_ADMIN, NOW))
                .isFalse();
    }
}
