package com.keplerops.groundcontrol.domain.riskcontrol.repository;

import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskControlMappingRepository extends JpaRepository<RiskControlMapping, UUID> {

    Optional<RiskControlMapping> findByIdAndProjectId(UUID id, UUID projectId);

    List<RiskControlMapping> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    // ---- C1 reverse lookups ----

    @Query("SELECT m FROM RiskControlMapping m WHERE m.project.id = :projectId AND m.riskScenario.id = :scenarioId")
    List<RiskControlMapping> findByProjectIdAndRiskScenarioId(
            @Param("projectId") UUID projectId, @Param("scenarioId") UUID scenarioId);

    @Query("SELECT m FROM RiskControlMapping m WHERE m.project.id = :projectId AND m.control.id = :controlId")
    List<RiskControlMapping> findByProjectIdAndControlId(
            @Param("projectId") UUID projectId, @Param("controlId") UUID controlId);

    @Query("SELECT m FROM RiskControlMapping m WHERE m.project.id = :projectId"
            + " AND m.scopedImplementation.id = :scopedId")
    List<RiskControlMapping> findByProjectIdAndScopedImplementationId(
            @Param("projectId") UUID projectId, @Param("scopedId") UUID scopedId);

    // ---- Uniqueness checks for service-level conflict detection ----

    @Query("SELECT (COUNT(m) > 0) FROM RiskControlMapping m"
            + " WHERE m.control.id = :controlId"
            + " AND m.riskScenario.id = :scenarioId"
            + " AND (:assetId IS NULL AND m.operationalAsset IS NULL"
            + "      OR m.operationalAsset.id = :assetId)")
    boolean existsByControlIdAndRiskScenarioIdAndOperationalAssetId(
            @Param("controlId") UUID controlId, @Param("scenarioId") UUID scenarioId, @Param("assetId") UUID assetId);

    @Query("SELECT (COUNT(m) > 0) FROM RiskControlMapping m"
            + " WHERE m.scopedImplementation.id = :scopedId"
            + " AND m.riskScenario.id = :scenarioId"
            + " AND (:assetId IS NULL AND m.operationalAsset IS NULL"
            + "      OR m.operationalAsset.id = :assetId)")
    boolean existsByScopedImplementationIdAndRiskScenarioIdAndOperationalAssetId(
            @Param("scopedId") UUID scopedId, @Param("scenarioId") UUID scenarioId, @Param("assetId") UUID assetId);

    // ---- C1 threat-model reverse lookup ----

    @Query("SELECT m FROM RiskControlMapping m WHERE m.project.id = :projectId AND m.threatModel.id = :threatModelId")
    List<RiskControlMapping> findByProjectIdAndThreatModelId(
            @Param("projectId") UUID projectId, @Param("threatModelId") UUID threatModelId);

    // ---- Uniqueness checks for threat endpoint ----

    @Query("SELECT (COUNT(m) > 0) FROM RiskControlMapping m"
            + " WHERE m.control.id = :controlId"
            + " AND m.threatModel.id = :threatModelId"
            + " AND (:assetId IS NULL AND m.operationalAsset IS NULL"
            + "      OR m.operationalAsset.id = :assetId)")
    boolean existsByControlIdAndThreatModelIdAndOperationalAssetId(
            @Param("controlId") UUID controlId,
            @Param("threatModelId") UUID threatModelId,
            @Param("assetId") UUID assetId);

    @Query("SELECT (COUNT(m) > 0) FROM RiskControlMapping m"
            + " WHERE m.scopedImplementation.id = :scopedId"
            + " AND m.threatModel.id = :threatModelId"
            + " AND (:assetId IS NULL AND m.operationalAsset IS NULL"
            + "      OR m.operationalAsset.id = :assetId)")
    boolean existsByScopedImplementationIdAndThreatModelIdAndOperationalAssetId(
            @Param("scopedId") UUID scopedId,
            @Param("threatModelId") UUID threatModelId,
            @Param("assetId") UUID assetId);

    // ---- C5-threat: Threat models with no mapped controls ----

    /**
     * Returns IDs of threat model entries in the given project that have no RiskControlMapping row
     * (either direct or via scoped implementation).
     */
    @Query(
            """
            SELECT t.id FROM ThreatModel t
            WHERE t.project.id = :projectId
              AND NOT EXISTS (
                SELECT 1 FROM RiskControlMapping m
                WHERE m.project.id = :projectId AND m.threatModel.id = t.id
              )
            """)
    List<UUID> findUnmappedThreatIds(@Param("projectId") UUID projectId);

    // ---- C6-threat: Controls not mapped to any threat ----

    /**
     * Returns IDs of catalog controls in the given project that have no RiskControlMapping
     * to a threat model (directly or via scoped implementations).
     */
    @Query(
            """
            SELECT c.id FROM Control c
            WHERE c.project.id = :projectId
              AND NOT EXISTS (
                SELECT 1 FROM RiskControlMapping m
                WHERE m.project.id = :projectId
                  AND m.control.id = c.id
                  AND m.threatModel IS NOT NULL
              )
              AND NOT EXISTS (
                SELECT 1 FROM ScopedControlImplementation sci
                WHERE sci.project.id = :projectId AND sci.control.id = c.id
                  AND EXISTS (
                    SELECT 1 FROM RiskControlMapping m2
                    WHERE m2.project.id = :projectId
                      AND m2.scopedImplementation.id = sci.id
                      AND m2.threatModel IS NOT NULL
                  )
              )
            """)
    List<UUID> findControlIdsUnmappedToThreats(@Param("projectId") UUID projectId);

    // ---- C5a: Scenarios with no mapped controls ----

    /**
     * Returns IDs of risk scenarios in the given project that have no RiskControlMapping row
     * (either direct or via scoped implementation).
     */
    @Query(
            """
            SELECT s.id FROM RiskScenario s
            WHERE s.project.id = :projectId
              AND NOT EXISTS (
                SELECT 1 FROM RiskControlMapping m
                WHERE m.project.id = :projectId AND m.riskScenario.id = s.id
              )
            """)
    List<UUID> findUnmappedScenarioIds(@Param("projectId") UUID projectId);

    // ---- C6: Controls not mapped to any relevant scenario ----

    /**
     * Returns IDs of catalog controls in the given project that have no RiskControlMapping
     * to a scenario (directly or via a scoped implementation).
     */
    @Query(
            """
            SELECT c.id FROM Control c
            WHERE c.project.id = :projectId
              AND NOT EXISTS (
                SELECT 1 FROM RiskControlMapping m
                WHERE m.project.id = :projectId
                  AND m.control.id = c.id
                  AND m.riskScenario IS NOT NULL
              )
              AND NOT EXISTS (
                SELECT 1 FROM ScopedControlImplementation sci
                WHERE sci.project.id = :projectId AND sci.control.id = c.id
                  AND EXISTS (
                    SELECT 1 FROM RiskControlMapping m2
                    WHERE m2.project.id = :projectId
                      AND m2.scopedImplementation.id = sci.id
                      AND m2.riskScenario IS NOT NULL
                  )
              )
            """)
    List<UUID> findUnmappedControlIds(@Param("projectId") UUID projectId);
}
