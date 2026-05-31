package com.keplerops.groundcontrol.domain.evidence.repository;

import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvidenceArtifactRepository extends JpaRepository<EvidenceArtifact, UUID> {

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    @Query("SELECT e FROM EvidenceArtifact e WHERE e.id = :id AND e.project.id = :projectId")
    Optional<EvidenceArtifact> findByIdAndProjectId(@Param("id") UUID id, @Param("projectId") UUID projectId);

    @Query("SELECT e FROM EvidenceArtifact e WHERE e.project.id = :projectId ORDER BY e.derivedAt DESC, e.uid ASC")
    List<EvidenceArtifact> findByProjectIdOrderByDerivedAtDesc(@Param("projectId") UUID projectId);

    /**
     * Project-scoped artifacts whose {@code derivedAt <= :asOf}. Used by
     * historical-as-of analyses so future artifacts do not leak into the
     * result (GC-L007 finding #2).
     */
    @Query("SELECT e FROM EvidenceArtifact e WHERE e.project.id = :projectId AND e.derivedAt <= :asOf "
            + "ORDER BY e.derivedAt DESC, e.uid ASC")
    List<EvidenceArtifact> findByProjectIdAndDerivedAtLessThanEqualOrderByDerivedAtDesc(
            @Param("projectId") UUID projectId, @Param("asOf") java.time.Instant asOf);

    @Query("SELECT e FROM EvidenceArtifact e WHERE e.project.id = :projectId AND e.evidenceType = :evidenceType"
            + " ORDER BY e.derivedAt DESC, e.uid ASC")
    List<EvidenceArtifact> findByProjectIdAndEvidenceTypeOrderByDerivedAtDesc(
            @Param("projectId") UUID projectId, @Param("evidenceType") EvidenceType evidenceType);

    /**
     * Race-safe supersede write: sets {@code supersededByArtifactId} only when it
     * is currently null, returning the number of affected rows. Two concurrent
     * supersede requests both read the prior artifact as unsuperseded, but only
     * one of their conditional updates affects a row — the loser observes 0
     * affected rows and surfaces a conflict. Mirrors the optimistic-update
     * pattern used elsewhere for write-once invariants.
     */
    @Modifying
    @Query("UPDATE EvidenceArtifact e SET e.supersededByArtifactId = :replacementId"
            + " WHERE e.id = :id AND e.project.id = :projectId AND e.supersededByArtifactId IS NULL")
    int markSupersededIfUnset(
            @Param("id") UUID id, @Param("projectId") UUID projectId, @Param("replacementId") UUID replacementId);

    /**
     * GC-I004 sweep query: artifacts with a non-null {@code expiresAt} that has
     * elapsed at or before {@code asOf}. Append-only is preserved — this read
     * does not mutate any rows; the caller decides whether an expiry event has
     * already been emitted for the artifact (idempotency check on
     * compliance_drift_event).
     */
    @Query("SELECT e FROM EvidenceArtifact e WHERE e.expiresAt IS NOT NULL AND e.expiresAt <= :asOf"
            + " ORDER BY e.expiresAt ASC, e.uid ASC")
    List<EvidenceArtifact> findExpiredAsOf(@Param("asOf") java.time.Instant asOf);
}
