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
}
