package com.keplerops.groundcontrol.domain.identity.repository;

import com.keplerops.groundcontrol.domain.identity.model.IdentityRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityRoleRepository extends JpaRepository<IdentityRole, UUID> {
    Optional<IdentityRole> findByKey(String key);

    boolean existsByKey(String key);
}
