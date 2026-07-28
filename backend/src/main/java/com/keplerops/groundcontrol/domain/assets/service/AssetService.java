package com.keplerops.groundcontrol.domain.assets.service;

import com.keplerops.groundcontrol.domain.assets.model.AssetExternalId;
import com.keplerops.groundcontrol.domain.assets.model.AssetLink;
import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.AssetSubtypeSchema;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository;
import com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AssetService {

    // Constants for repeated detail-map / field-name literals (Sonar S1192).
    static final String FIELD_SUBTYPE = "subtype";
    static final String DETAIL_REASON = "reason";
    static final String DETAIL_FIELD = "field";
    static final String DETAIL_LIMIT = "limit";

    static final String FIELD_ASSET_TYPE = "assetType";
    static final String FIELD_OWNER = "owner";
    static final String FIELD_STEWARD = "steward";

    private final OperationalAssetRepository assetRepository;
    private final AssetLinkRepository linkRepository;
    private final FindingLinkRepository findingLinkRepository;
    private final AuditLinkRepository auditLinkRepository;
    private final AssetSubtypeSchemaOperations assetSubtypeSchemaOperations;
    private final AssetExternalIdOperations assetExternalIdOperations;
    private final AssetLinkOperations assetLinkOperations;
    private final AssetRelationOperations assetRelationOperations;
    private final AssetQueryOperations assetQueryOperations;
    private final AssetWriteOperations assetWriteOperations;

    @SuppressWarnings("java:S107") // service aggregates ten collaborators from the constructor on purpose
    public AssetService(
            OperationalAssetRepository assetRepository,
            AssetRelationRepository relationRepository,
            AssetLinkRepository linkRepository,
            AssetExternalIdRepository externalIdRepository,
            FindingLinkRepository findingLinkRepository,
            AuditLinkRepository auditLinkRepository,
            ProjectRepository projectRepository,
            GraphTargetResolverService graphTargetResolverService,
            AssetSubtypeSchemaRepository subtypeSchemaRepository,
            AssetSubtypeValidator subtypeValidator) {
        this.assetRepository = assetRepository;
        this.linkRepository = linkRepository;
        this.findingLinkRepository = findingLinkRepository;
        this.auditLinkRepository = auditLinkRepository;

        this.assetSubtypeSchemaOperations =
                new AssetSubtypeSchemaOperations(projectRepository, subtypeSchemaRepository, subtypeValidator);

        this.assetExternalIdOperations = new AssetExternalIdOperations(externalIdRepository, this);

        this.assetLinkOperations = new AssetLinkOperations(linkRepository, graphTargetResolverService, this);

        this.assetRelationOperations = new AssetRelationOperations(relationRepository, this);

        this.assetQueryOperations = new AssetQueryOperations(assetRepository);

        this.assetWriteOperations = new AssetWriteOperations(
                assetRepository, projectRepository, subtypeSchemaRepository, subtypeValidator, this);
    }

    /**
     * Max-length contracts for bounded {@code OperationalAsset} string fields.
     * Mirror the {@code @Column(length=...)} declarations on the entity and
     * the {@code @Size} declarations on the API DTOs; enforced at the
     * service layer so callers cannot bypass the contract by going around
     * Bean Validation (codex cycle-4 finding 1).
     */
    static final String ASSET_NOT_FOUND = "Asset not found: ";

    static final class OperationalAssetBounds {

        private OperationalAssetBounds() {}

        static final int UID = 50;
        static final int NAME = 200;
        static final int OWNER = 200;
        static final int STEWARD = 200;
        static final int SUBTYPE = 100;
    }

    @Transactional(readOnly = true)
    public OperationalAsset getById(UUID projectId, UUID id) {
        return assetRepository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException(ASSET_NOT_FOUND + id));
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public OperationalAsset getById(UUID id) {
        return assetRepository.findById(id).orElseThrow(() -> new NotFoundException(ASSET_NOT_FOUND + id));
    }

    @Transactional(readOnly = true)
    public OperationalAsset getByUid(UUID projectId, String uid) {
        return assetRepository
                .findByProjectIdAndUidIgnoreCase(projectId, uid)
                .orElseThrow(() -> new NotFoundException(ASSET_NOT_FOUND + uid));
    }

    public OperationalAsset archive(UUID projectId, UUID id) {
        var asset = getById(projectId, id);
        asset.archive();
        return assetRepository.save(asset);
    }

    @Deprecated(forRemoval = false)
    public OperationalAsset archive(UUID id) {
        var asset = getById(id);
        asset.archive();
        return assetRepository.save(asset);
    }

    public void delete(UUID projectId, UUID id) {
        var asset = getById(projectId, id);
        rejectIfInboundFindingLinksReferenceAsset(projectId, id, asset.getUid());
        // Delete outbound links through the repository before the parent so Envers
        // writes delete revisions for each AssetLink. The migration's FK has
        // ON DELETE CASCADE only as a defense-in-depth fallback; relying on it
        // would bypass Hibernate and leave asset_link_audit incomplete for the
        // parent-delete path.
        var outboundLinks = linkRepository.findByAssetId(id);
        linkRepository.deleteAll(outboundLinks);
        assetRepository.delete(asset);
    }

    @Deprecated(forRemoval = false)
    public void delete(UUID id) {
        // Resolve the asset directly via the repository rather than via getById(id):
        // Sonar S6809 flags self-invocation of @Transactional methods because the
        // proxy is bypassed and any per-method tx semantics would be lost. Both
        // getById overloads share the class-default tx, so behavior is unchanged.
        var asset = assetRepository.findById(id).orElseThrow(() -> new NotFoundException(ASSET_NOT_FOUND + id));
        rejectIfInboundFindingLinksReferenceAsset(asset.getProject().getId(), id, asset.getUid());
        // Mirror the project-scoped overload's link-then-parent ordering so the
        // deprecated path also fires Envers delete revisions for each AssetLink
        // (see the project-scoped delete javadoc).
        var outboundLinks = linkRepository.findByAssetId(id);
        linkRepository.deleteAll(outboundLinks);
        assetRepository.delete(asset);
    }

    private void rejectIfInboundFindingLinksReferenceAsset(UUID projectId, UUID assetId, String assetUid) {
        // FindingLink.targetEntityId is not an FK, so a delete here would leave
        // dangling rows that FindingLinkController.list and the graph projection
        // would happily surface (ADR-038 / cycle-3 codex review on issue #279).
        var inboundFindingUids = findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                FindingLinkTargetType.ASSET, assetId, projectId);
        if (!inboundFindingUids.isEmpty()) {
            Map<String, Serializable> detail = new LinkedHashMap<>();
            detail.put("assetUid", assetUid);
            detail.put("findingCount", inboundFindingUids.size());
            detail.put("findingUids", new ArrayList<>(inboundFindingUids));
            throw new ConflictException(
                    "Asset " + assetUid
                            + " cannot be deleted while inbound FindingLink references exist. Remove the"
                            + " FindingLink references first, then retry.",
                    "asset_referenced",
                    detail);
        }
        var inboundAuditUids = auditLinkRepository.findAuditUidsByTargetTypeAndTargetEntityIdAndProjectId(
                AuditLinkTargetType.ASSET, assetId, projectId);
        if (!inboundAuditUids.isEmpty()) {
            Map<String, Serializable> detail = new LinkedHashMap<>();
            detail.put("assetUid", assetUid);
            detail.put("auditCount", inboundAuditUids.size());
            detail.put("auditUids", new ArrayList<>(inboundAuditUids));
            throw new ConflictException(
                    "Asset " + assetUid
                            + " cannot be deleted while inbound AuditLink references exist. Remove the"
                            + " AuditLink references first, then retry.",
                    "asset_referenced",
                    detail);
        }
    }

    static <T> void applyClearOrSet(boolean clear, T newValue, java.util.function.Consumer<T> setter) {
        if (clear) {
            setter.accept(null);
        } else if (newValue != null) {
            setter.accept(newValue);
        }
    }

    public AssetSubtypeSchema registerSubtypeSchema(CreateAssetSubtypeSchemaCommand command) {
        return assetSubtypeSchemaOperations.registerSubtypeSchema(command);
    }

    public AssetSubtypeSchema updateSubtypeSchema(UUID projectId, UUID id, UpdateAssetSubtypeSchemaCommand command) {
        return assetSubtypeSchemaOperations.updateSubtypeSchema(projectId, id, command);
    }

    public AssetSubtypeSchema deprecateSubtypeSchema(UUID projectId, UUID id) {
        return assetSubtypeSchemaOperations.deprecateSubtypeSchema(projectId, id);
    }

    @Transactional(readOnly = true)
    public AssetSubtypeSchema getSubtypeSchema(UUID projectId, UUID id) {
        return assetSubtypeSchemaOperations.getSubtypeSchema(projectId, id);
    }

    @Transactional(readOnly = true)
    public AssetSubtypeSchema getActiveSubtypeSchema(UUID projectId, AssetType assetType, String subtype) {
        return assetSubtypeSchemaOperations.getActiveSubtypeSchema(projectId, assetType, subtype);
    }

    @Transactional(readOnly = true)
    public List<AssetSubtypeSchema> listSubtypeSchemas(UUID projectId, AssetType assetType, String subtype) {
        return assetSubtypeSchemaOperations.listSubtypeSchemas(projectId, assetType, subtype);
    }

    public AssetExternalId createExternalId(UUID projectId, UUID assetId, CreateAssetExternalIdCommand command) {
        return assetExternalIdOperations.createExternalId(projectId, assetId, command);
    }

    @Deprecated(forRemoval = false)
    public AssetExternalId createExternalId(UUID assetId, CreateAssetExternalIdCommand command) {
        return assetExternalIdOperations.createExternalId(assetId, command);
    }

    public AssetExternalId updateExternalId(
            UUID projectId, UUID assetId, UUID extIdId, UpdateAssetExternalIdCommand command) {
        return assetExternalIdOperations.updateExternalId(projectId, assetId, extIdId, command);
    }

    @Deprecated(forRemoval = false)
    public AssetExternalId updateExternalId(UUID assetId, UUID extIdId, UpdateAssetExternalIdCommand command) {
        return assetExternalIdOperations.updateExternalId(assetId, extIdId, command);
    }

    @Transactional(readOnly = true)
    public List<AssetExternalId> getExternalIds(UUID projectId, UUID assetId) {
        return assetExternalIdOperations.getExternalIds(projectId, assetId);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetExternalId> getExternalIds(UUID assetId) {
        return assetExternalIdOperations.getExternalIds(assetId);
    }

    @Transactional(readOnly = true)
    public List<AssetExternalId> getExternalIdsBySource(UUID projectId, UUID assetId, String sourceSystem) {
        return assetExternalIdOperations.getExternalIdsBySource(projectId, assetId, sourceSystem);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetExternalId> getExternalIdsBySource(UUID assetId, String sourceSystem) {
        return assetExternalIdOperations.getExternalIdsBySource(assetId, sourceSystem);
    }

    @Transactional(readOnly = true)
    public List<AssetExternalId> findByExternalId(UUID projectId, String sourceSystem, String sourceId) {
        return assetExternalIdOperations.findByExternalId(projectId, sourceSystem, sourceId);
    }

    public void deleteExternalId(UUID projectId, UUID assetId, UUID extIdId) {
        assetExternalIdOperations.deleteExternalId(projectId, assetId, extIdId);
    }

    @Deprecated(forRemoval = false)
    public void deleteExternalId(UUID assetId, UUID extIdId) {
        assetExternalIdOperations.deleteExternalId(assetId, extIdId);
    }

    public AssetLink createLink(UUID projectId, UUID assetId, CreateAssetLinkCommand command) {
        return assetLinkOperations.createLink(projectId, assetId, command);
    }

    @Deprecated(forRemoval = false)
    public AssetLink createLink(UUID assetId, CreateAssetLinkCommand command) {
        return assetLinkOperations.createLink(assetId, command);
    }

    @Transactional(readOnly = true)
    public List<AssetLink> getLinksForAsset(UUID projectId, UUID assetId) {
        return assetLinkOperations.getLinksForAsset(projectId, assetId);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetLink> getLinksForAsset(UUID assetId) {
        return assetLinkOperations.getLinksForAsset(assetId);
    }

    @Transactional(readOnly = true)
    public List<AssetLink> getLinksForAssetByTargetType(UUID projectId, UUID assetId, AssetLinkTargetType targetType) {
        return assetLinkOperations.getLinksForAssetByTargetType(projectId, assetId, targetType);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetLink> getLinksForAssetByTargetType(UUID assetId, AssetLinkTargetType targetType) {
        return assetLinkOperations.getLinksForAssetByTargetType(assetId, targetType);
    }

    @Transactional(readOnly = true)
    public List<AssetLink> getLinksByTarget(
            UUID projectId, AssetLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        return assetLinkOperations.getLinksByTarget(projectId, targetType, targetEntityId, targetIdentifier);
    }

    public void deleteLink(UUID projectId, UUID assetId, UUID linkId) {
        assetLinkOperations.deleteLink(projectId, assetId, linkId);
    }

    @Deprecated(forRemoval = false)
    public void deleteLink(UUID assetId, UUID linkId) {
        assetLinkOperations.deleteLink(assetId, linkId);
    }

    public AssetRelation createRelation(UUID projectId, UUID sourceId, UUID targetId, AssetRelationType relationType) {
        return assetRelationOperations.createRelation(projectId, sourceId, targetId, relationType);
    }

    @Deprecated(forRemoval = false)
    public AssetRelation createRelation(UUID sourceId, UUID targetId, AssetRelationType relationType) {
        return assetRelationOperations.createRelation(sourceId, targetId, relationType);
    }

    public AssetRelation createRelation(UUID projectId, CreateAssetRelationCommand command, UUID sourceId) {
        return assetRelationOperations.createRelation(projectId, command, sourceId);
    }

    @Deprecated(forRemoval = false)
    public AssetRelation createRelation(CreateAssetRelationCommand command, UUID sourceId) {
        return assetRelationOperations.createRelation(command, sourceId);
    }

    public AssetRelation updateRelation(
            UUID projectId, UUID assetId, UUID relationId, UpdateAssetRelationCommand command) {
        return assetRelationOperations.updateRelation(projectId, assetId, relationId, command);
    }

    @Deprecated(forRemoval = false)
    public AssetRelation updateRelation(UUID assetId, UUID relationId, UpdateAssetRelationCommand command) {
        return assetRelationOperations.updateRelation(assetId, relationId, command);
    }

    @Transactional(readOnly = true)
    public List<AssetRelation> getRelations(UUID projectId, UUID assetId) {
        return assetRelationOperations.getRelations(projectId, assetId);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetRelation> getRelations(UUID assetId) {
        return assetRelationOperations.getRelations(assetId);
    }

    public void deleteRelation(UUID projectId, UUID assetId, UUID relationId) {
        assetRelationOperations.deleteRelation(projectId, assetId, relationId);
    }

    @Deprecated(forRemoval = false)
    public void deleteRelation(UUID assetId, UUID relationId) {
        assetRelationOperations.deleteRelation(assetId, relationId);
    }

    @Transactional(readOnly = true)
    public List<OperationalAsset> listByProject(UUID projectId) {
        return assetQueryOperations.listByProject(projectId);
    }

    @SuppressWarnings({"java:S107", "java:S1133"})
    @Transactional(readOnly = true)
    public List<OperationalAsset> listByProjectAndFilters(
            UUID projectId,
            AssetType assetType,
            String owner,
            String steward,
            com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment environment,
            com.keplerops.groundcontrol.domain.assets.state.AssetCriticality criticality,
            com.keplerops.groundcontrol.domain.assets.state.AssetScope scopeDesignation,
            String subtype,
            com.keplerops.groundcontrol.domain.assets.state.KnowledgeState knowledgeState) {
        return assetQueryOperations.listByProjectAndFilters(
                projectId,
                assetType,
                owner,
                steward,
                environment,
                criticality,
                scopeDesignation,
                subtype,
                knowledgeState);
    }

    /**
     * @deprecated GC-M018 added the {@code knowledgeState} filter facet.
     *     Callers should adopt the 9-arg overload so the knowledgeState query
     *     parameter is honored. Retained for source compatibility with
     *     pre-GC-M018 callers. Suppressed: S1133 (don't forget to remove
     *     deprecated code) — removal is tied to all callers migrating off
     *     this overload, which we are explicitly NOT requiring in this PR.
     */
    @SuppressWarnings({"java:S107", "java:S1133"})
    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<OperationalAsset> listByProjectAndFilters(
            UUID projectId,
            AssetType assetType,
            String owner,
            String steward,
            com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment environment,
            com.keplerops.groundcontrol.domain.assets.state.AssetCriticality criticality,
            com.keplerops.groundcontrol.domain.assets.state.AssetScope scopeDesignation,
            String subtype) {
        return assetQueryOperations.listByProjectAndFilters(
                projectId, assetType, owner, steward, environment, criticality, scopeDesignation, subtype);
    }

    /**
     * @deprecated GC-M011 added the {@code subtype} filter facet. Callers
     *     should adopt the 9-arg overload so the subtype and knowledgeState
     *     query parameters are honored. Retained for source compatibility
     *     with pre-GC-M011 callers.
     */
    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<OperationalAsset> listByProjectAndFilters(
            UUID projectId,
            AssetType assetType,
            String owner,
            String steward,
            com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment environment,
            com.keplerops.groundcontrol.domain.assets.state.AssetCriticality criticality,
            com.keplerops.groundcontrol.domain.assets.state.AssetScope scopeDesignation) {
        return assetQueryOperations.listByProjectAndFilters(
                projectId, assetType, owner, steward, environment, criticality, scopeDesignation);
    }

    @Transactional(readOnly = true)
    public List<OperationalAsset> listByProjectAndType(UUID projectId, AssetType assetType) {
        return assetQueryOperations.listByProjectAndType(projectId, assetType);
    }

    public OperationalAsset create(CreateAssetCommand command) {
        return assetWriteOperations.create(command);
    }

    public OperationalAsset update(UUID projectId, UUID id, UpdateAssetCommand command) {
        return assetWriteOperations.update(projectId, id, command);
    }

    @Deprecated(forRemoval = false)
    public OperationalAsset update(UUID id, UpdateAssetCommand command) {
        return assetWriteOperations.update(id, command);
    }
}
