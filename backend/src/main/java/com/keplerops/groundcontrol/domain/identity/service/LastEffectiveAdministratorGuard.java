package com.keplerops.groundcontrol.domain.identity.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.identity.repository.EffectiveAuthorizationRepository;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LastEffectiveAdministratorGuard {

    private static final long IDENTITY_ADMIN_MUTATION_LOCK_KEY = 0x4743_4944_4144_4D4EL; // GCI DADMN
    private static final String ADVISORY_LOCK_SQL = "SELECT pg_advisory_xact_lock(?)";

    private final EffectiveAuthorizationRepository repository;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public LastEffectiveAdministratorGuard(
            EffectiveAuthorizationRepository repository, JdbcTemplate jdbc, EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void protect(Runnable mutation) {
        jdbc.execute(ADVISORY_LOCK_SQL, (PreparedStatement statement) -> {
            statement.setLong(1, IDENTITY_ADMIN_MUTATION_LOCK_KEY);
            statement.execute();
            return null;
        });
        Instant now = clock.instant();
        boolean hadAdministrator = repository.hasAnyEffectiveGlobalPermission(PermissionKey.IDENTITY_ADMIN, now);
        mutation.run();
        entityManager.flush();
        if (hadAdministrator && !repository.hasAnyEffectiveGlobalPermission(PermissionKey.IDENTITY_ADMIN, now)) {
            throw new ConflictException(
                    "Cannot remove the last effective identity administrator", "last_identity_administrator", Map.of());
        }
    }
}
