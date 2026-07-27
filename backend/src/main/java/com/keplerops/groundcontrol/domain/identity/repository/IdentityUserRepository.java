package com.keplerops.groundcontrol.domain.identity.repository;

import com.keplerops.groundcontrol.domain.identity.model.IdentityUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityUserRepository extends JpaRepository<IdentityUser, UUID> {
    Optional<IdentityUser> findByLoginName(String loginName);

    boolean existsByLoginName(String loginName);
}
