package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.identity.model.IdentityUser;
import com.keplerops.groundcontrol.domain.identity.model.ProjectAccessGrant;
import com.keplerops.groundcontrol.domain.identity.repository.IdentityUserRepository;
import com.keplerops.groundcontrol.domain.identity.repository.ProjectAccessGrantRepository;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserState;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class IdentityAuditTrailIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IdentityUserRepository userRepository;

    @Autowired
    private ProjectAccessGrantRepository projectAccessGrantRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void identityLifecycleWritesRealEnversRevisions() {
        IdentityUser user = null;
        try {
            user = userRepository.save(
                    new IdentityUser("audit-" + UUID.randomUUID(), "Audit user", IdentityUserKind.SERVICE));
            TestTransaction.flagForCommit();
            TestTransaction.end();

            TestTransaction.start();
            var saved = userRepository.findById(user.getId()).orElseThrow();
            saved.transitionTo(IdentityUserState.SUSPENDED);
            userRepository.save(saved);
            TestTransaction.flagForCommit();
            TestTransaction.end();

            TestTransaction.start();
            assertThat(AuditReaderFactory.get(entityManager).getRevisions(IdentityUser.class, user.getId()))
                    .hasSize(2);
        } finally {
            if (user != null) {
                jdbcTemplate.update("DELETE FROM identity_user WHERE id = ?", user.getId());
                jdbcTemplate.update("DELETE FROM identity_user_audit WHERE id = ?", user.getId());
            }
            TestTransaction.flagForCommit();
            TestTransaction.end();
            TestTransaction.start();
        }
    }

    @Test
    void projectGrantAuditRetainsTheProjectIdentityWithoutAuditingProject() {
        IdentityUser user = null;
        ProjectAccessGrant grant = null;
        try {
            user = userRepository.save(new IdentityUser(
                    "project-audit-" + UUID.randomUUID(), "Project audit user", IdentityUserKind.HUMAN));
            var project = projectRepository.findByIdentifier("ground-control").orElseThrow();
            grant = projectAccessGrantRepository.save(ProjectAccessGrant.forUser(user, project, null, null));
            UUID expectedProjectId = project.getId();
            UUID grantId = grant.getId();
            TestTransaction.flagForCommit();
            TestTransaction.end();

            TestTransaction.start();
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT project_id FROM project_access_grant_audit WHERE id = ?", UUID.class, grantId))
                    .isEqualTo(expectedProjectId);
        } finally {
            if (grant != null) {
                jdbcTemplate.update("DELETE FROM project_access_grant WHERE id = ?", grant.getId());
                jdbcTemplate.update("DELETE FROM project_access_grant_audit WHERE id = ?", grant.getId());
            }
            if (user != null) {
                jdbcTemplate.update("DELETE FROM identity_user WHERE id = ?", user.getId());
                jdbcTemplate.update("DELETE FROM identity_user_audit WHERE id = ?", user.getId());
            }
            TestTransaction.flagForCommit();
            TestTransaction.end();
            TestTransaction.start();
        }
    }
}
