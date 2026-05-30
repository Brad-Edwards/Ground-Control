package com.keplerops.groundcontrol.domain.compliance.repository;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceDriftEvent;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComplianceDriftEventRepository extends JpaRepository<ComplianceDriftEvent, UUID> {

    @Query("SELECT e FROM ComplianceDriftEvent e WHERE e.id = :id AND e.project.id = :projectId")
    Optional<ComplianceDriftEvent> findByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

    @Query("SELECT e FROM ComplianceDriftEvent e WHERE e.project.id = :projectId ORDER BY e.detectedAt DESC")
    List<ComplianceDriftEvent> findByProjectIdOrderByDetectedAtDesc(@Param("projectId") UUID projectId);

    @Query("SELECT e FROM ComplianceDriftEvent e WHERE e.project.id = :projectId AND e.category = :category"
            + " ORDER BY e.detectedAt DESC")
    List<ComplianceDriftEvent> findByProjectIdAndCategoryOrderByDetectedAtDesc(
            @Param("projectId") UUID projectId, @Param("category") ComplianceDriftCategory category);

    @Query("SELECT e FROM ComplianceDriftEvent e WHERE e.project.id = :projectId AND e.acknowledgedAt IS NULL"
            + " ORDER BY e.detectedAt DESC")
    List<ComplianceDriftEvent> findUnacknowledgedByProjectId(@Param("projectId") UUID projectId);

    /**
     * Idempotency check for evidence-expiry events: an artifact that has
     * already produced an EVIDENCE_EXPIRED event should not produce a second
     * one on every sweep tick. Mirrors the supersede-once invariant pattern.
     */
    @Query("SELECT COUNT(e) > 0 FROM ComplianceDriftEvent e WHERE e.project.id = :projectId"
            + " AND e.category = :category"
            + " AND e.sourceEntityType = :sourceType AND e.sourceEntityId = :sourceId")
    boolean existsBySourceAndCategory(
            @Param("projectId") UUID projectId,
            @Param("category") ComplianceDriftCategory category,
            @Param("sourceType") String sourceEntityType,
            @Param("sourceId") UUID sourceEntityId);

    /**
     * Most-recent drift timestamp across all events for a project. Used by
     * the detector liveness endpoint so a stalled monitor cannot silently
     * report 'compliant' (security note in the cluster scope).
     */
    @Query("SELECT MAX(e.detectedAt) FROM ComplianceDriftEvent e WHERE e.project.id = :projectId")
    Optional<java.time.Instant> findLastDetectedAt(@Param("projectId") UUID projectId);

    /**
     * Conditional acknowledgement update: writes acknowledged_at only when it
     * is currently null. Returns affected row count so the service can
     * surface a conflict on a second acknowledgement.
     */
    @Modifying
    @Query("UPDATE ComplianceDriftEvent e SET e.acknowledgedAt = :ackAt, e.acknowledgedBy = :ackBy"
            + " WHERE e.id = :id AND e.project.id = :projectId AND e.acknowledgedAt IS NULL")
    int acknowledgeIfUnset(
            @Param("id") UUID id,
            @Param("projectId") UUID projectId,
            @Param("ackAt") java.time.Instant acknowledgedAt,
            @Param("ackBy") String acknowledgedBy);
}
