package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TraceabilityService {

    private final RequirementRepository requirementRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;

    public TraceabilityService(
            RequirementRepository requirementRepository, TraceabilityLinkRepository traceabilityLinkRepository) {
        this.requirementRepository = requirementRepository;
        this.traceabilityLinkRepository = traceabilityLinkRepository;
    }

    /**
     * Creates a traceability link, enforcing that IMPLEMENTS links require ACTIVE status.
     * Used by the REST API (user-initiated link creation).
     */
    public TraceabilityLink createLink(UUID requirementId, CreateTraceabilityLinkCommand command) {
        return createLink(requirementId, command, true);
    }

    /**
     * Creates a traceability link without enforcing the ACTIVE status check.
     * For internal operations (import, GitHub issue sync) where the link is part
     * of a planned data load, not retroactive justification.
     */
    public TraceabilityLink createLinkUnchecked(UUID requirementId, CreateTraceabilityLinkCommand command) {
        return createLink(requirementId, command, false);
    }

    private TraceabilityLink createLink(
            UUID requirementId, CreateTraceabilityLinkCommand command, boolean enforceActiveCheck) {
        var requirement = requirementRepository
                .findById(requirementId)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + requirementId));

        if (enforceActiveCheck
                && command.linkType() == LinkType.IMPLEMENTS
                && requirement.getStatus() != Status.ACTIVE) {
            throw new DomainValidationException(
                    "IMPLEMENTS links require the requirement to be in ACTIVE status, but it is "
                            + requirement.getStatus(),
                    "requirement_not_active",
                    Map.of(
                            "requirementId",
                            requirementId.toString(),
                            "status",
                            requirement.getStatus().name()));
        }

        var link = new TraceabilityLink(
                requirement, command.artifactType(), command.artifactIdentifier(), command.linkType());
        if (command.artifactUrl() != null) {
            link.setArtifactUrl(command.artifactUrl());
        }
        if (command.artifactTitle() != null) {
            link.setArtifactTitle(command.artifactTitle());
        }
        return traceabilityLinkRepository.save(link);
    }

    @Transactional(readOnly = true)
    public List<TraceabilityLink> getLinksForRequirement(UUID requirementId) {
        if (!requirementRepository.existsById(requirementId)) {
            throw new NotFoundException("Requirement not found: " + requirementId);
        }
        return traceabilityLinkRepository.findByRequirementId(requirementId);
    }

    @Transactional(readOnly = true)
    public List<TraceabilityLink> findByArtifact(ArtifactType artifactType, String artifactIdentifier, UUID projectId) {
        if (projectId != null) {
            return traceabilityLinkRepository.findByArtifactTypeAndArtifactIdentifierAndProjectIdWithRequirement(
                    artifactType, artifactIdentifier, projectId);
        }
        return traceabilityLinkRepository.findByArtifactTypeAndArtifactIdentifierWithRequirement(
                artifactType, artifactIdentifier);
    }

    public void deleteLink(UUID requirementId, UUID linkId) {
        var link = traceabilityLinkRepository
                .findById(linkId)
                .orElseThrow(() -> new NotFoundException("Traceability link not found: " + linkId));
        if (!requirementId.equals(link.getRequirement().getId())) {
            throw new NotFoundException("Traceability link not found: " + linkId);
        }
        traceabilityLinkRepository.delete(link);
    }
}
