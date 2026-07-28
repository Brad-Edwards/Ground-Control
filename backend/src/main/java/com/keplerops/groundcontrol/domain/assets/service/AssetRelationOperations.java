package com.keplerops.groundcontrol.domain.assets.service;

import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Asset-to-asset relation operations.
 *
 * Split out of {@link AssetService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class AssetRelationOperations {

    private final AssetRelationRepository relationRepository;
    private final AssetService service;

    AssetRelationOperations(AssetRelationRepository relationRepository, AssetService service) {
        this.relationRepository = relationRepository;
        this.service = service;
    }

    AssetRelation createRelation(UUID projectId, UUID sourceId, UUID targetId, AssetRelationType relationType) {
        return createRelation(
                projectId,
                new CreateAssetRelationCommand(targetId, relationType, null, null, null, null, null, null),
                sourceId);
    }

    AssetRelation createRelation(UUID sourceId, UUID targetId, AssetRelationType relationType) {
        return createRelation(
                new CreateAssetRelationCommand(targetId, relationType, null, null, null, null, null, null), sourceId);
    }

    AssetRelation createRelation(UUID projectId, CreateAssetRelationCommand command, UUID sourceId) {
        if (sourceId.equals(command.targetId())) {
            throw new DomainValidationException("An asset cannot relate to itself");
        }
        if (relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                sourceId, command.targetId(), command.relationType())) {
            throw new ConflictException("Relation " + command.relationType() + " already exists between " + sourceId
                    + " and " + command.targetId());
        }
        var source = service.getById(projectId, sourceId);
        var target = service.getById(projectId, command.targetId());
        var relation = new AssetRelation(source, target, command.relationType());
        applyRelationMetadata(
                relation,
                command.description(),
                command.sourceSystem(),
                command.externalSourceId(),
                command.collectedAt(),
                command.confidence(),
                command.knowledgeState());
        return relationRepository.save(relation);
    }

    AssetRelation createRelation(CreateAssetRelationCommand command, UUID sourceId) {
        if (sourceId.equals(command.targetId())) {
            throw new DomainValidationException("An asset cannot relate to itself");
        }
        if (relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                sourceId, command.targetId(), command.relationType())) {
            throw new ConflictException("Relation " + command.relationType() + " already exists between " + sourceId
                    + " and " + command.targetId());
        }
        var source = service.getById(sourceId);
        var target = service.getById(command.targetId());
        validateSameProject(source, target);
        var relation = new AssetRelation(source, target, command.relationType());
        applyRelationMetadata(
                relation,
                command.description(),
                command.sourceSystem(),
                command.externalSourceId(),
                command.collectedAt(),
                command.confidence(),
                command.knowledgeState());
        return relationRepository.save(relation);
    }

    AssetRelation updateRelation(UUID projectId, UUID assetId, UUID relationId, UpdateAssetRelationCommand command) {
        var relation = getRelationBelongingTo(projectId, assetId, relationId);
        applyRelationMetadata(
                relation,
                command.description(),
                command.sourceSystem(),
                command.externalSourceId(),
                command.collectedAt(),
                command.confidence(),
                command.knowledgeState());
        return relationRepository.save(relation);
    }

    AssetRelation updateRelation(UUID assetId, UUID relationId, UpdateAssetRelationCommand command) {
        var relation = getLegacyRelationBelongingTo(assetId, relationId);
        applyRelationMetadata(
                relation,
                command.description(),
                command.sourceSystem(),
                command.externalSourceId(),
                command.collectedAt(),
                command.confidence(),
                command.knowledgeState());
        return relationRepository.save(relation);
    }

    List<AssetRelation> getRelations(UUID projectId, UUID assetId) {
        service.getById(projectId, assetId);
        var outgoing = relationRepository.findBySourceIdWithEntities(assetId);
        var incoming = relationRepository.findByTargetIdWithEntities(assetId);
        var combined = new ArrayList<AssetRelation>(outgoing);
        combined.addAll(incoming);
        return combined;
    }

    List<AssetRelation> getRelations(UUID assetId) {
        service.getById(assetId);
        var outgoing = relationRepository.findBySourceIdWithEntities(assetId);
        var incoming = relationRepository.findByTargetIdWithEntities(assetId);
        var combined = new ArrayList<AssetRelation>(outgoing);
        combined.addAll(incoming);
        return combined;
    }

    void deleteRelation(UUID projectId, UUID assetId, UUID relationId) {
        relationRepository.delete(getRelationBelongingTo(projectId, assetId, relationId));
    }

    void deleteRelation(UUID assetId, UUID relationId) {
        relationRepository.delete(getLegacyRelationBelongingTo(assetId, relationId));
    }

    private AssetRelation getRelationBelongingTo(UUID projectId, UUID assetId, UUID relationId) {
        var relation = relationRepository
                .findByIdWithEntitiesAndProjectId(relationId, projectId)
                .orElseThrow(() -> new NotFoundException("Relation not found: " + relationId));
        if (!relation.getSource().getId().equals(assetId)
                && !relation.getTarget().getId().equals(assetId)) {
            throw new NotFoundException("Relation " + relationId + " does not belong to asset " + assetId);
        }
        return relation;
    }

    private AssetRelation getLegacyRelationBelongingTo(UUID assetId, UUID relationId) {
        var relation = relationRepository
                .findByIdWithEntities(relationId)
                .orElseThrow(() -> new NotFoundException("Relation not found: " + relationId));
        if (!relation.getSource().getId().equals(assetId)
                && !relation.getTarget().getId().equals(assetId)) {
            throw new NotFoundException("Relation " + relationId + " does not belong to asset " + assetId);
        }
        return relation;
    }

    private void validateSameProject(OperationalAsset source, OperationalAsset target) {
        if (!source.getProject().getId().equals(target.getProject().getId())) {
            throw new DomainValidationException("Assets cannot relate across different projects");
        }
    }

    @SuppressWarnings("java:S107") // applyRelationMetadata bundles the relation's optional payload fields.
    private void applyRelationMetadata(
            AssetRelation relation,
            String description,
            String sourceSystem,
            String externalSourceId,
            Instant collectedAt,
            String confidence,
            com.keplerops.groundcontrol.domain.assets.state.KnowledgeState knowledgeState) {
        if (description != null) {
            relation.setDescription(description);
        }
        if (sourceSystem != null) {
            relation.setSourceSystem(sourceSystem);
        }
        if (externalSourceId != null) {
            relation.setExternalSourceId(externalSourceId);
        }
        if (collectedAt != null) {
            relation.setCollectedAt(collectedAt);
        }
        if (confidence != null) {
            relation.setConfidence(confidence);
        }
        if (knowledgeState != null) {
            relation.setKnowledgeState(knowledgeState);
        }
    }
}
