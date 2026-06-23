package com.keplerops.groundcontrol.domain.requirements.repository;

import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TraceabilityLinkRepository extends JpaRepository<TraceabilityLink, UUID> {

    List<TraceabilityLink> findByRequirementId(UUID requirementId);

    List<TraceabilityLink> findByRequirementIdIn(Collection<UUID> requirementIds);

    List<TraceabilityLink> findByArtifactType(ArtifactType artifactType);

    boolean existsByRequirementIdAndArtifactTypeAndArtifactIdentifierAndLinkType(
            UUID requirementId, ArtifactType artifactType, String artifactIdentifier, LinkType linkType);

    boolean existsByRequirementId(UUID requirementId);

    boolean existsByRequirementIdAndLinkType(UUID requirementId, LinkType linkType);

    @Query("SELECT DISTINCT l.requirement.id FROM TraceabilityLink l"
            + " WHERE l.requirement.id IN :requirementIds AND l.linkType = :linkType")
    Set<UUID> findRequirementIdsWithLinkType(
            @Param("requirementIds") Collection<UUID> requirementIds, @Param("linkType") LinkType linkType);

    @Query("SELECT l FROM TraceabilityLink l JOIN FETCH l.requirement"
            + " WHERE l.artifactType = :artifactType AND l.artifactIdentifier = :artifactIdentifier")
    List<TraceabilityLink> findByArtifactTypeAndArtifactIdentifierWithRequirement(
            @Param("artifactType") ArtifactType artifactType, @Param("artifactIdentifier") String artifactIdentifier);

    @Query("SELECT l FROM TraceabilityLink l JOIN FETCH l.requirement r"
            + " WHERE l.artifactType = :artifactType AND l.artifactIdentifier = :artifactIdentifier"
            + " AND r.project.id = :projectId")
    List<TraceabilityLink> findByArtifactTypeAndArtifactIdentifierAndProjectIdWithRequirement(
            @Param("artifactType") ArtifactType artifactType,
            @Param("artifactIdentifier") String artifactIdentifier,
            @Param("projectId") UUID projectId);
}
