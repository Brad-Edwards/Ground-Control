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

    @Query("SELECT m FROM RiskControlMapping m WHERE m.project.id = :projectId"
            + " AND m.riskRegisterRecord.id = :recordId")
    List<RiskControlMapping> findByProjectIdAndRiskRegisterRecordId(
            @Param("projectId") UUID projectId, @Param("recordId") UUID recordId);

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
            + " WHERE m.control.id = :controlId"
            + " AND m.riskRegisterRecord.id = :recordId"
            + " AND (:assetId IS NULL AND m.operationalAsset IS NULL"
            + "      OR m.operationalAsset.id = :assetId)")
    boolean existsByControlIdAndRiskRegisterRecordIdAndOperationalAssetId(
            @Param("controlId") UUID controlId, @Param("recordId") UUID recordId, @Param("assetId") UUID assetId);

    @Query("SELECT (COUNT(m) > 0) FROM RiskControlMapping m"
            + " WHERE m.scopedImplementation.id = :scopedId"
            + " AND m.riskScenario.id = :scenarioId"
            + " AND (:assetId IS NULL AND m.operationalAsset IS NULL"
            + "      OR m.operationalAsset.id = :assetId)")
    boolean existsByScopedImplementationIdAndRiskScenarioIdAndOperationalAssetId(
            @Param("scopedId") UUID scopedId, @Param("scenarioId") UUID scenarioId, @Param("assetId") UUID assetId);

    @Query("SELECT (COUNT(m) > 0) FROM RiskControlMapping m"
            + " WHERE m.scopedImplementation.id = :scopedId"
            + " AND m.riskRegisterRecord.id = :recordId"
            + " AND (:assetId IS NULL AND m.operationalAsset IS NULL"
            + "      OR m.operationalAsset.id = :assetId)")
    boolean existsByScopedImplementationIdAndRiskRegisterRecordIdAndOperationalAssetId(
            @Param("scopedId") UUID scopedId, @Param("recordId") UUID recordId, @Param("assetId") UUID assetId);

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

    // ---- C5b: Records with no mapped controls (direct, no transitive) ----

    /**
     * Returns IDs of risk register records in the given project that have no direct
     * RiskControlMapping row.
     */
    @Query(
            """
            SELECT r.id FROM RiskRegisterRecord r
            WHERE r.project.id = :projectId
              AND NOT EXISTS (
                SELECT 1 FROM RiskControlMapping m
                WHERE m.project.id = :projectId AND m.riskRegisterRecord.id = r.id
              )
            """)
    List<UUID> findDirectlyUnmappedRecordIds(@Param("projectId") UUID projectId);

    // ---- C6: Controls not mapped to any relevant scenario (transitive-through-record) ----

    /**
     * Returns IDs of catalog controls in the given project that have no RiskControlMapping
     * to a scenario (directly or via a register record that owns ≥1 scenario).
     *
     * <p>A control is "covered" if it has at least one mapping to a scenario, OR at least
     * one mapping to a record that has ≥1 scenario in its riskScenarios set.
     * A catalog control is also covered if any of its scoped implementations is covered.
     */
    @Query(
            """
            SELECT c.id FROM Control c
            WHERE c.project.id = :projectId
              AND NOT EXISTS (
                SELECT 1 FROM RiskControlMapping m
                WHERE m.project.id = :projectId
                  AND m.control.id = c.id
                  AND (
                    m.riskScenario IS NOT NULL
                    OR (m.riskRegisterRecord IS NOT NULL AND EXISTS (
                      SELECT 1 FROM RiskRegisterRecord r JOIN r.riskScenarios s
                      WHERE r.id = m.riskRegisterRecord.id
                    ))
                  )
              )
              AND NOT EXISTS (
                SELECT 1 FROM ScopedControlImplementation sci
                WHERE sci.project.id = :projectId AND sci.control.id = c.id
                  AND EXISTS (
                    SELECT 1 FROM RiskControlMapping m2
                    WHERE m2.project.id = :projectId
                      AND m2.scopedImplementation.id = sci.id
                      AND (
                        m2.riskScenario IS NOT NULL
                        OR (m2.riskRegisterRecord IS NOT NULL AND EXISTS (
                          SELECT 1 FROM RiskRegisterRecord r2 JOIN r2.riskScenarios s2
                          WHERE r2.id = m2.riskRegisterRecord.id
                        ))
                      )
                  )
              )
            """)
    List<UUID> findUnmappedControlIds(@Param("projectId") UUID projectId);
}
