package com.keplerops.groundcontrol.domain.riskscenarios.repository;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskAssessmentResultRepository extends JpaRepository<RiskAssessmentResult, UUID> {

    @Query("SELECT DISTINCT r FROM RiskAssessmentResult r"
            + " LEFT JOIN FETCH r.observations"
            + " WHERE r.id = :id AND r.project.id = :projectId")
    Optional<RiskAssessmentResult> findByIdAndProjectIdWithObservations(
            @Param("id") UUID id, @Param("projectId") UUID projectId);

    @Query("SELECT r FROM RiskAssessmentResult r WHERE r.id = :id AND r.project.id = :projectId")
    Optional<RiskAssessmentResult> findByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

    @Query("SELECT (COUNT(r) > 0) FROM RiskAssessmentResult r WHERE r.id = :id AND r.project.id = :projectId")
    boolean existsByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

    @Query("SELECT DISTINCT r FROM RiskAssessmentResult r"
            + " LEFT JOIN FETCH r.observations"
            + " WHERE r.project.id = :projectId ORDER BY r.createdAt DESC")
    List<RiskAssessmentResult> findByProjectIdWithObservationsOrderByCreatedAtDesc(@Param("projectId") UUID projectId);

    @Query("SELECT DISTINCT r FROM RiskAssessmentResult r"
            + " LEFT JOIN FETCH r.observations"
            + " WHERE r.project.id = :projectId AND r.riskScenario.id = :riskScenarioId ORDER BY r.createdAt DESC")
    List<RiskAssessmentResult> findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(
            @Param("projectId") UUID projectId, @Param("riskScenarioId") UUID riskScenarioId);

    @Query(
            "SELECT DISTINCT r FROM RiskAssessmentResult r"
                    + " LEFT JOIN FETCH r.observations"
                    + " WHERE r.project.id = :projectId AND r.riskRegisterRecord.id = :riskRegisterRecordId ORDER BY r.createdAt DESC")
    List<RiskAssessmentResult> findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(
            @Param("projectId") UUID projectId, @Param("riskRegisterRecordId") UUID riskRegisterRecordId);

    /**
     * Return the latest {@code RiskAssessmentResult} per {@code RiskScenario}
     * for the project — newest by {@code assessmentAt} (NULLs last), tiebreaker
     * {@code createdAt}. Used by the GC-T008 heat map, distribution, top-N, and
     * posture services so every aggregation operates on the same "current"
     * snapshot per scenario.
     *
     * <p>The selector uses a correlated NOT EXISTS to keep the result set to
     * one row per scenario without resorting to native window functions; this
     * is the precedent shape used elsewhere in the repository layer.
     */
    @Query("SELECT r FROM RiskAssessmentResult r"
            + " WHERE r.project.id = :projectId"
            + " AND NOT EXISTS ("
            + "   SELECT 1 FROM RiskAssessmentResult r2"
            + "   WHERE r2.project.id = :projectId"
            + "   AND r2.riskScenario.id = r.riskScenario.id"
            + "   AND ("
            + "     (r2.assessmentAt IS NOT NULL AND (r.assessmentAt IS NULL OR r2.assessmentAt > r.assessmentAt))"
            + "     OR (r2.assessmentAt = r.assessmentAt AND r2.createdAt > r.createdAt)"
            + "     OR (r.assessmentAt IS NULL AND r2.assessmentAt IS NULL AND r2.createdAt > r.createdAt)"
            + "   )"
            + " )"
            + " ORDER BY r.assessmentAt DESC NULLS LAST, r.createdAt DESC")
    List<RiskAssessmentResult> findLatestPerScenarioByProjectId(@Param("projectId") UUID projectId);
}
