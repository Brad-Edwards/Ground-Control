package com.keplerops.groundcontrol.domain.riskappetite.repository;

import com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAppetiteProfileRepository extends JpaRepository<RiskAppetiteProfile, UUID> {

    boolean existsByProjectIdAndAppetiteKeyAndVersion(UUID projectId, String appetiteKey, String version);

    Optional<RiskAppetiteProfile> findByIdAndProjectId(UUID id, UUID projectId);

    List<RiskAppetiteProfile> findByProjectIdOrderByNameAscVersionDesc(UUID projectId);

    List<RiskAppetiteProfile> findByProjectIdAndAppetiteKeyAndStatus(
            UUID projectId, String appetiteKey, RiskAppetiteProfileStatus status);
}
