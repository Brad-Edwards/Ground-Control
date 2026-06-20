package com.keplerops.groundcontrol.domain.riskscenarios.repository;

import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import java.time.Instant;
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

    @Query("SELECT r FROM RiskAssessmentResult r JOIN FETCH r.riskScenario"
            + " WHERE r.project.id = :projectId AND r.reassessmentRequiredAt IS NOT NULL"
            + " AND r.reassessmentRequiredAt >= :lookbackStart AND r.reassessmentRequiredAt <= :effectiveAsOf"
            + " ORDER BY r.reassessmentRequiredAt DESC")
    List<RiskAssessmentResult> findByProjectIdWithReassessmentRequiredInWindowOrderByReassessmentRequiredAtDesc(
            @Param("projectId") UUID projectId,
            @Param("lookbackStart") Instant lookbackStart,
            @Param("effectiveAsOf") Instant effectiveAsOf);
}
