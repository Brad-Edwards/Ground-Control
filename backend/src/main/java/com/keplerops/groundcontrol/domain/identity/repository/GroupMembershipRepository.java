package com.keplerops.groundcontrol.domain.identity.repository;

import com.keplerops.groundcontrol.domain.identity.model.GroupMembership;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, UUID> {}
