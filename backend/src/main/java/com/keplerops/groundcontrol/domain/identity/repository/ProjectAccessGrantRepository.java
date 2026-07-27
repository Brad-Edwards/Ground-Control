package com.keplerops.groundcontrol.domain.identity.repository;

import com.keplerops.groundcontrol.domain.identity.model.ProjectAccessGrant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectAccessGrantRepository extends JpaRepository<ProjectAccessGrant, UUID> {}
