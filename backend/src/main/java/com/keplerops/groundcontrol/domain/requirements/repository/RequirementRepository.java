package com.keplerops.groundcontrol.domain.requirements.repository;

import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequirementRepository extends JpaRepository<Requirement, UUID>, JpaSpecificationExecutor<Requirement> {

    Optional<Requirement> findByProjectIdAndUid(UUID projectId, String uid);

    boolean existsByProjectIdAndUid(UUID projectId, String uid);

    Optional<Requirement> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByIdAndProjectId(UUID id, UUID projectId);

    @Query("SELECT r FROM Requirement r WHERE r.project.id = :projectId AND UPPER(r.uid) = UPPER(:uid)")
    Optional<Requirement> findByProjectIdAndUidIgnoreCase(@Param("projectId") UUID projectId, @Param("uid") String uid);

    @Query(
            "SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Requirement r WHERE r.project.id = :projectId AND UPPER(r.uid) = UPPER(:uid)")
    boolean existsByProjectIdAndUidIgnoreCase(@Param("projectId") UUID projectId, @Param("uid") String uid);

    @Query(
            value = "SELECT COALESCE(MAX(CAST(substring(uid from '[0-9]+$') AS bigint)), 0)"
                    + " FROM requirement WHERE project_id = :projectId AND uid ~ :pattern",
            nativeQuery = true)
    long findMaxUidSuffix(@Param("projectId") UUID projectId, @Param("pattern") String pattern);

    List<Requirement> findByProjectId(UUID projectId);

    List<Requirement> findByProjectIdAndArchivedAtIsNull(UUID projectId);

    List<Requirement> findByProjectIdAndStatusAndArchivedAtIsNull(UUID projectId, Status status);

    @Query("SELECT r.id FROM Requirement r WHERE r.project.id = :projectId AND r.archivedAt IS NULL")
    List<UUID> findIdsByProjectIdAndArchivedAtIsNull(@Param("projectId") UUID projectId);

    long countByProjectIdAndArchivedAtIsNull(UUID projectId);

    @Query("SELECT r FROM Requirement r WHERE r.project.id = :projectId AND r.archivedAt IS NULL"
            + " AND NOT EXISTS (SELECT rel FROM RequirementRelation rel WHERE rel.source = r OR rel.target = r)"
            + " AND NOT EXISTS (SELECT l FROM TraceabilityLink l WHERE l.requirement = r)")
    List<Requirement> findOrphansByProjectId(@Param("projectId") UUID projectId);

    @Query("SELECT r FROM Requirement r WHERE r.project.id = :projectId AND r.archivedAt IS NULL"
            + " AND NOT EXISTS (SELECT l FROM TraceabilityLink l WHERE l.requirement = r AND l.linkType = :linkType)")
    List<Requirement> findCoverageGapsByProjectIdAndLinkType(
            @Param("projectId") UUID projectId, @Param("linkType") LinkType linkType);

    @Query("SELECT COUNT(r) FROM Requirement r WHERE r.project.id = :projectId AND r.archivedAt IS NULL"
            + " AND (:scopeStatus IS NULL OR r.status = :scopeStatus)")
    long countByScope(@Param("projectId") UUID projectId, @Param("scopeStatus") Status scopeStatus);

    @Query("SELECT COUNT(DISTINCT r) FROM Requirement r"
            + " JOIN TraceabilityLink l ON l.requirement = r"
            + " WHERE r.project.id = :projectId AND r.archivedAt IS NULL"
            + " AND l.linkType = :linkType"
            + " AND (:scopeStatus IS NULL OR r.status = :scopeStatus)")
    long countCoveredByLinkType(
            @Param("projectId") UUID projectId,
            @Param("linkType") LinkType linkType,
            @Param("scopeStatus") Status scopeStatus);
}
