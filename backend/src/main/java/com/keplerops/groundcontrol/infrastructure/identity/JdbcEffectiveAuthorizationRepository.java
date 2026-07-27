package com.keplerops.groundcontrol.infrastructure.identity;

import com.keplerops.groundcontrol.domain.identity.repository.EffectiveAuthorizationRepository;
import com.keplerops.groundcontrol.domain.identity.state.PermissionKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEffectiveAuthorizationRepository implements EffectiveAuthorizationRepository {

    private static final String EFFECTIVE_PERMISSION_SQL =
            """
            SELECT EXISTS (
              SELECT 1
              FROM identity_user u
              JOIN role_grant rg ON (
                rg.user_id = u.id
                OR (
                  rg.group_id IS NOT NULL
                  AND EXISTS (
                    SELECT 1
                    FROM group_membership gm
                    JOIN identity_group ig ON ig.id = gm.group_id
                    WHERE gm.user_id = u.id
                      AND gm.group_id = rg.group_id
                      AND gm.state = 'ACTIVE'
                      AND ig.state = 'ACTIVE'
                      AND (gm.effective_from IS NULL OR gm.effective_from <= ?)
                      AND (gm.effective_until IS NULL OR gm.effective_until > ?)
                  )
                )
              )
              JOIN identity_role ir ON ir.id = rg.role_id
              JOIN role_permission_assignment rpa ON rpa.role_id = ir.id
              WHERE u.id = ?
                AND u.state = 'ACTIVE'
                AND ir.state = 'ACTIVE'
                AND rg.state = 'ACTIVE'
                AND rpa.state = 'ACTIVE'
                AND rpa.permission = ?
                AND (rg.effective_from IS NULL OR rg.effective_from <= ?)
                AND (rg.effective_until IS NULL OR rg.effective_until > ?)
                AND (
                  (?::uuid IS NULL AND rg.project_id IS NULL)
                  OR (?::uuid IS NOT NULL AND (rg.project_id IS NULL OR rg.project_id = ?::uuid))
                )
            )
            """;

    private static final String EFFECTIVE_PROJECT_ACCESS_SQL =
            """
            SELECT EXISTS (
              SELECT 1
              FROM identity_user u
              JOIN project_access_grant pag ON (
                pag.user_id = u.id
                OR (
                  pag.group_id IS NOT NULL
                  AND EXISTS (
                    SELECT 1
                    FROM group_membership gm
                    JOIN identity_group ig ON ig.id = gm.group_id
                    WHERE gm.user_id = u.id
                      AND gm.group_id = pag.group_id
                      AND gm.state = 'ACTIVE'
                      AND ig.state = 'ACTIVE'
                      AND (gm.effective_from IS NULL OR gm.effective_from <= ?)
                      AND (gm.effective_until IS NULL OR gm.effective_until > ?)
                  )
                )
              )
              WHERE u.id = ?
                AND u.state = 'ACTIVE'
                AND pag.project_id = ?
                AND pag.state = 'ACTIVE'
                AND (pag.effective_from IS NULL OR pag.effective_from <= ?)
                AND (pag.effective_until IS NULL OR pag.effective_until > ?)
            )
            """;

    private static final String ANY_EFFECTIVE_GLOBAL_PERMISSION_SQL =
            """
            SELECT EXISTS (
              SELECT 1
              FROM identity_user u
              JOIN role_grant rg ON (
                rg.user_id = u.id
                OR (
                  rg.group_id IS NOT NULL
                  AND EXISTS (
                    SELECT 1
                    FROM group_membership gm
                    JOIN identity_group ig ON ig.id = gm.group_id
                    WHERE gm.user_id = u.id
                      AND gm.group_id = rg.group_id
                      AND gm.state = 'ACTIVE'
                      AND ig.state = 'ACTIVE'
                      AND (gm.effective_from IS NULL OR gm.effective_from <= ?)
                      AND (gm.effective_until IS NULL OR gm.effective_until > ?)
                  )
                )
              )
              JOIN identity_role ir ON ir.id = rg.role_id
              JOIN role_permission_assignment rpa ON rpa.role_id = ir.id
              WHERE u.state = 'ACTIVE'
                AND ir.state = 'ACTIVE'
                AND rg.state = 'ACTIVE'
                AND rg.project_id IS NULL
                AND rpa.state = 'ACTIVE'
                AND rpa.permission = ?
                AND (rg.effective_from IS NULL OR rg.effective_from <= ?)
                AND (rg.effective_until IS NULL OR rg.effective_until > ?)
            )
            """;

    private final JdbcTemplate jdbc;

    public JdbcEffectiveAuthorizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasEffectivePermission(UUID userId, PermissionKey permission, UUID projectId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        Boolean result = jdbc.queryForObject(
                EFFECTIVE_PERMISSION_SQL,
                Boolean.class,
                timestamp,
                timestamp,
                userId,
                permission.name(),
                timestamp,
                timestamp,
                projectId,
                projectId,
                projectId);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean hasEffectiveProjectAccess(UUID userId, UUID projectId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        Boolean result = jdbc.queryForObject(
                EFFECTIVE_PROJECT_ACCESS_SQL,
                Boolean.class,
                timestamp,
                timestamp,
                userId,
                projectId,
                timestamp,
                timestamp);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean hasAnyEffectiveGlobalPermission(PermissionKey permission, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        Boolean result = jdbc.queryForObject(
                ANY_EFFECTIVE_GLOBAL_PERMISSION_SQL,
                Boolean.class,
                timestamp,
                timestamp,
                permission.name(),
                timestamp,
                timestamp);
        return Boolean.TRUE.equals(result);
    }
}
