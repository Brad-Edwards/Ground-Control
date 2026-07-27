package com.keplerops.groundcontrol.domain.identity.repository;

import com.keplerops.groundcontrol.domain.identity.model.IdentityGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityGroupRepository extends JpaRepository<IdentityGroup, UUID> {
    boolean existsByName(String name);
}
