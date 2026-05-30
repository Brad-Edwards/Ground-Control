package com.keplerops.groundcontrol.domain.riskscenarios.repository;

import com.keplerops.groundcontrol.domain.riskscenarios.model.KeyRiskIndicator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KeyRiskIndicatorRepository extends JpaRepository<KeyRiskIndicator, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    Optional<KeyRiskIndicator> findByIdAndProjectId(UUID id, UUID projectId);

    List<KeyRiskIndicator> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<KeyRiskIndicator> findByProjectIdAndRiskRegisterRecordId(UUID projectId, UUID riskRegisterRecordId);

    List<KeyRiskIndicator> findByProjectIdAndRiskScenarioId(UUID projectId, UUID riskScenarioId);
}
