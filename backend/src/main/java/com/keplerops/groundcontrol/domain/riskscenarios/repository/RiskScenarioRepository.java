package com.keplerops.groundcontrol.domain.riskscenarios.repository;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskScenarioRepository extends JpaRepository<RiskScenario, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    boolean existsByIdAndProjectId(UUID id, UUID projectId);

    Optional<RiskScenario> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<RiskScenario> findByProjectIdAndUid(UUID projectId, String uid);

    List<RiskScenario> findByProjectIdAndUidIn(UUID projectId, Collection<String> uids);

    List<RiskScenario> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<RiskScenario> findByIdInAndProjectId(Collection<UUID> ids, UUID projectId);

    @Query("SELECT s.id FROM RiskScenario s WHERE s.project.id = :projectId AND s.status <> :status")
    List<UUID> findIdsByProjectIdAndStatusNot(
            @Param("projectId") UUID projectId, @Param("status") RiskScenarioStatus status);
}
