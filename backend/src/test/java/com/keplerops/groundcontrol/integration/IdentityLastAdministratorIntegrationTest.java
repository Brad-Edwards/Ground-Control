package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.identity.model.IdentityUser;
import com.keplerops.groundcontrol.domain.identity.model.RoleGrant;
import com.keplerops.groundcontrol.domain.identity.repository.IdentityRoleRepository;
import com.keplerops.groundcontrol.domain.identity.repository.IdentityUserRepository;
import com.keplerops.groundcontrol.domain.identity.repository.RoleGrantRepository;
import com.keplerops.groundcontrol.domain.identity.service.LastEffectiveAdministratorGuard;
import com.keplerops.groundcontrol.domain.identity.state.IdentityUserKind;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class IdentityLastAdministratorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IdentityUserRepository userRepository;

    @Autowired
    private IdentityRoleRepository roleRepository;

    @Autowired
    private RoleGrantRepository roleGrantRepository;

    @Autowired
    private LastEffectiveAdministratorGuard guard;

    @Autowired
    private EntityManager entityManager;

    @Test
    void actualDatabaseGuardRejectsRevokingTheOnlyEffectiveAdministrator() {
        var user = userRepository.save(
                new IdentityUser("sole-admin-" + UUID.randomUUID(), "Sole administrator", IdentityUserKind.HUMAN));
        var adminRole = roleRepository.findByKey("ADMIN").orElseThrow();
        var grant = roleGrantRepository.save(RoleGrant.forUser(adminRole, user, null, null, null));
        entityManager.flush();

        assertThatThrownBy(() -> guard.protect(() -> {
                    grant.revoke();
                    roleGrantRepository.save(grant);
                }))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode")
                .isEqualTo("last_identity_administrator");
    }
}
