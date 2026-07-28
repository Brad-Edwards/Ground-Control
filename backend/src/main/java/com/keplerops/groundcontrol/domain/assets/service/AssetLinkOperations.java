package com.keplerops.groundcontrol.domain.assets.service;

import com.keplerops.groundcontrol.domain.assets.model.AssetLink;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import java.util.List;
import java.util.UUID;

/**
 * Link operations binding assets to requirements and other targets.
 *
 * Split out of {@link AssetService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class AssetLinkOperations {

    private final AssetLinkRepository linkRepository;
    private final GraphTargetResolverService graphTargetResolverService;
    private final AssetService service;

    AssetLinkOperations(
            AssetLinkRepository linkRepository,
            GraphTargetResolverService graphTargetResolverService,
            AssetService service) {
        this.linkRepository = linkRepository;
        this.graphTargetResolverService = graphTargetResolverService;
        this.service = service;
    }

    // --- Asset Links (cross-entity linking) ---

    AssetLink createLink(UUID projectId, UUID assetId, CreateAssetLinkCommand command) {
        var asset = service.getById(projectId, assetId);
        var target = graphTargetResolverService.validateAssetTarget(
                projectId, command.targetType(), command.targetEntityId(), command.targetIdentifier());
        boolean exists = target.internal()
                ? linkRepository.existsByAssetIdAndTargetTypeAndTargetEntityIdAndLinkType(
                        assetId, command.targetType(), target.targetEntityId(), command.linkType())
                : linkRepository.existsByAssetIdAndTargetTypeAndTargetIdentifierAndLinkType(
                        assetId, command.targetType(), target.targetIdentifier(), command.linkType());
        if (exists) {
            throw new ConflictException("Link already exists: " + command.linkType() + " -> " + command.targetType()
                    + ":" + (target.internal() ? target.targetEntityId() : target.targetIdentifier()));
        }
        var link = new AssetLink(
                asset, command.targetType(), target.targetEntityId(), target.targetIdentifier(), command.linkType());
        if (command.targetUrl() != null) {
            link.setTargetUrl(command.targetUrl());
        }
        if (command.targetTitle() != null) {
            link.setTargetTitle(command.targetTitle());
        }
        return linkRepository.save(link);
    }

    @Deprecated(forRemoval = false)
    AssetLink createLink(UUID assetId, CreateAssetLinkCommand command) {
        var asset = service.getById(assetId);
        var target = graphTargetResolverService.validateAssetTarget(
                asset.getProject().getId(), command.targetType(), command.targetEntityId(), command.targetIdentifier());
        boolean exists = target.internal()
                ? linkRepository.existsByAssetIdAndTargetTypeAndTargetEntityIdAndLinkType(
                        assetId, command.targetType(), target.targetEntityId(), command.linkType())
                : linkRepository.existsByAssetIdAndTargetTypeAndTargetIdentifierAndLinkType(
                        assetId, command.targetType(), target.targetIdentifier(), command.linkType());
        if (exists) {
            throw new ConflictException("Link already exists: " + command.linkType() + " -> " + command.targetType()
                    + ":" + (target.internal() ? target.targetEntityId() : target.targetIdentifier()));
        }
        var link = new AssetLink(
                asset, command.targetType(), target.targetEntityId(), target.targetIdentifier(), command.linkType());
        if (command.targetUrl() != null) {
            link.setTargetUrl(command.targetUrl());
        }
        if (command.targetTitle() != null) {
            link.setTargetTitle(command.targetTitle());
        }
        return linkRepository.save(link);
    }

    List<AssetLink> getLinksForAsset(UUID projectId, UUID assetId) {
        service.getById(projectId, assetId);
        return linkRepository.findByAssetId(assetId);
    }

    @Deprecated(forRemoval = false)
    List<AssetLink> getLinksForAsset(UUID assetId) {
        service.getById(assetId);
        return linkRepository.findByAssetId(assetId);
    }

    List<AssetLink> getLinksForAssetByTargetType(UUID projectId, UUID assetId, AssetLinkTargetType targetType) {
        service.getById(projectId, assetId);
        return linkRepository.findByAssetIdAndTargetType(assetId, targetType);
    }

    @Deprecated(forRemoval = false)
    List<AssetLink> getLinksForAssetByTargetType(UUID assetId, AssetLinkTargetType targetType) {
        service.getById(assetId);
        return linkRepository.findByAssetIdAndTargetType(assetId, targetType);
    }

    List<AssetLink> getLinksByTarget(
            UUID projectId, AssetLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        if (targetEntityId != null) {
            return linkRepository.findByTargetTypeAndTargetEntityIdAndProjectId(targetType, targetEntityId, projectId);
        }
        return linkRepository.findByTargetTypeAndTargetIdentifierAndProjectId(targetType, targetIdentifier, projectId);
    }

    void deleteLink(UUID projectId, UUID assetId, UUID linkId) {
        var link = linkRepository
                .findByIdWithAssetAndProjectId(linkId, projectId)
                .orElseThrow(() -> new NotFoundException("Link not found: " + linkId));
        if (!link.getAsset().getId().equals(assetId)) {
            throw new NotFoundException("Link " + linkId + " does not belong to asset " + assetId);
        }
        linkRepository.delete(link);
    }

    @Deprecated(forRemoval = false)
    void deleteLink(UUID assetId, UUID linkId) {
        var link =
                linkRepository.findById(linkId).orElseThrow(() -> new NotFoundException("Link not found: " + linkId));
        if (!link.getAsset().getId().equals(assetId)) {
            throw new NotFoundException("Link " + linkId + " does not belong to asset " + assetId);
        }
        linkRepository.delete(link);
    }
}
