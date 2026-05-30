package com.keplerops.groundcontrol.domain.riskscenarios.repository;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAppetiteProfileRepository extends JpaRepository<RiskAppetiteProfile, UUID> {

    boolean existsByProjectIdAndProfileKeyAndVersion(UUID projectId, String profileKey, String version);

    Optional<RiskAppetiteProfile> findByIdAndProjectId(UUID id, UUID projectId);

    List<RiskAppetiteProfile> findByProjectIdOrderByProfileKeyAscVersionDesc(UUID projectId);

    List<RiskAppetiteProfile> findByProjectIdAndProfileKeyOrderByVersionDesc(UUID projectId, String profileKey);

    List<RiskAppetiteProfile> findByProjectIdAndProfileKeyAndActiveTrue(UUID projectId, String profileKey);
}
