package com.keplerops.groundcontrol.domain.assets.service;

import com.keplerops.groundcontrol.domain.assets.model.AssetExternalId;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * External-identifier operations for operational assets.
 *
 * Split out of {@link AssetService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class AssetExternalIdOperations {

    private final AssetExternalIdRepository externalIdRepository;
    private final AssetService service;

    AssetExternalIdOperations(AssetExternalIdRepository externalIdRepository, AssetService service) {
        this.externalIdRepository = externalIdRepository;
        this.service = service;
    }

    // --- External Identifiers (source provenance) ---

    AssetExternalId createExternalId(UUID projectId, UUID assetId, CreateAssetExternalIdCommand command) {
        var asset = service.getById(projectId, assetId);
        if (externalIdRepository.existsByAssetIdAndSourceSystemAndSourceId(
                assetId, command.sourceSystem(), command.sourceId())) {
            throw new ConflictException("External ID already exists: " + command.sourceSystem() + ":"
                    + command.sourceId() + " for asset " + assetId);
        }
        var extId = new AssetExternalId(asset, command.sourceSystem(), command.sourceId());
        applyProvenanceFields(extId, command.collectedAt(), command.confidence());
        return externalIdRepository.save(extId);
    }

    @Deprecated(forRemoval = false)
    AssetExternalId createExternalId(UUID assetId, CreateAssetExternalIdCommand command) {
        var asset = service.getById(assetId);
        if (externalIdRepository.existsByAssetIdAndSourceSystemAndSourceId(
                assetId, command.sourceSystem(), command.sourceId())) {
            throw new ConflictException("External ID already exists: " + command.sourceSystem() + ":"
                    + command.sourceId() + " for asset " + assetId);
        }
        var extId = new AssetExternalId(asset, command.sourceSystem(), command.sourceId());
        applyProvenanceFields(extId, command.collectedAt(), command.confidence());
        return externalIdRepository.save(extId);
    }

    AssetExternalId updateExternalId(UUID projectId, UUID assetId, UUID extIdId, UpdateAssetExternalIdCommand command) {
        var extId = getExternalIdBelongingTo(projectId, assetId, extIdId);
        applyProvenanceFields(extId, command.collectedAt(), command.confidence());
        return externalIdRepository.save(extId);
    }

    @Deprecated(forRemoval = false)
    AssetExternalId updateExternalId(UUID assetId, UUID extIdId, UpdateAssetExternalIdCommand command) {
        var extId = getLegacyExternalIdBelongingTo(assetId, extIdId);
        applyProvenanceFields(extId, command.collectedAt(), command.confidence());
        return externalIdRepository.save(extId);
    }

    List<AssetExternalId> getExternalIds(UUID projectId, UUID assetId) {
        service.getById(projectId, assetId);
        return externalIdRepository.findByAssetId(assetId);
    }

    @Deprecated(forRemoval = false)
    List<AssetExternalId> getExternalIds(UUID assetId) {
        service.getById(assetId);
        return externalIdRepository.findByAssetId(assetId);
    }

    List<AssetExternalId> getExternalIdsBySource(UUID projectId, UUID assetId, String sourceSystem) {
        service.getById(projectId, assetId);
        return externalIdRepository.findByAssetIdAndSourceSystem(assetId, sourceSystem);
    }

    @Deprecated(forRemoval = false)
    List<AssetExternalId> getExternalIdsBySource(UUID assetId, String sourceSystem) {
        service.getById(assetId);
        return externalIdRepository.findByAssetIdAndSourceSystem(assetId, sourceSystem);
    }

    List<AssetExternalId> findByExternalId(UUID projectId, String sourceSystem, String sourceId) {
        return externalIdRepository.findBySourceSystemAndSourceIdAndProjectId(sourceSystem, sourceId, projectId);
    }

    void deleteExternalId(UUID projectId, UUID assetId, UUID extIdId) {
        externalIdRepository.delete(getExternalIdBelongingTo(projectId, assetId, extIdId));
    }

    @Deprecated(forRemoval = false)
    void deleteExternalId(UUID assetId, UUID extIdId) {
        externalIdRepository.delete(getLegacyExternalIdBelongingTo(assetId, extIdId));
    }

    private AssetExternalId getExternalIdBelongingTo(UUID projectId, UUID assetId, UUID extIdId) {
        var extId = externalIdRepository
                .findByIdWithAssetAndProjectId(extIdId, projectId)
                .orElseThrow(() -> new NotFoundException("External ID not found: " + extIdId));
        if (!extId.getAsset().getId().equals(assetId)) {
            throw new NotFoundException("External ID " + extIdId + " does not belong to asset " + assetId);
        }
        return extId;
    }

    private AssetExternalId getLegacyExternalIdBelongingTo(UUID assetId, UUID extIdId) {
        var extId = externalIdRepository
                .findByIdWithAsset(extIdId)
                .orElseThrow(() -> new NotFoundException("External ID not found: " + extIdId));
        if (!extId.getAsset().getId().equals(assetId)) {
            throw new NotFoundException("External ID " + extIdId + " does not belong to asset " + assetId);
        }
        return extId;
    }

    private void applyProvenanceFields(AssetExternalId extId, Instant collectedAt, String confidence) {
        if (collectedAt != null) {
            extId.setCollectedAt(collectedAt);
        }
        if (confidence != null) {
            extId.setConfidence(confidence);
        }
    }
}
