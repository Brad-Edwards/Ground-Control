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
import com.keplerops.groundcontrol.domain.assets.state.AssetSubtypeSchemaStatus;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository;
import com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.events.AssetStateChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSignal;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSourceEntityType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AssetService {

    // Constants for repeated detail-map / field-name literals (Sonar S1192).
    private static final String FIELD_SUBTYPE = "subtype";
    private static final String DETAIL_REASON = "reason";
    private static final String DETAIL_FIELD = "field";
    private static final String DETAIL_LIMIT = "limit";

    /**
     * Risk-bearing asset fields tracked by GC-T004 / C8 (#863) — a change in any of these on
     * {@code update} fires an {@link AssetStateChangedEvent} so the reassessment listener can
     * route on which fields moved. Drawn from the preflight: asset type, environment,
     * criticality, scope, business context, ownership, stewardship, and knowledge state.
     */
    private static final Set<String> RISK_BEARING_FIELDS = Set.of(
            "assetType",
            "environment",
            "criticality",
            "scopeDesignation",
            "businessContext",
            "owner",
            "steward",
            "knowledgeState");

    private final OperationalAssetRepository assetRepository;
    private final AssetRelationRepository relationRepository;
    private final AssetLinkRepository linkRepository;
    private final AssetExternalIdRepository externalIdRepository;
    private final FindingLinkRepository findingLinkRepository;
    private final AuditLinkRepository auditLinkRepository;
    private final ProjectRepository projectRepository;
    private final GraphTargetResolverService graphTargetResolverService;
    private final AssetSubtypeSchemaRepository subtypeSchemaRepository;
    private final AssetSubtypeValidator subtypeValidator;
    private final ApplicationEventPublisher eventPublisher;

    @SuppressWarnings("java:S107") // service aggregates eleven collaborators from the constructor on purpose
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
            AssetSubtypeValidator subtypeValidator,
            ApplicationEventPublisher eventPublisher) {
        this.assetRepository = assetRepository;
        this.relationRepository = relationRepository;
        this.linkRepository = linkRepository;
        this.externalIdRepository = externalIdRepository;
        this.findingLinkRepository = findingLinkRepository;
        this.auditLinkRepository = auditLinkRepository;
        this.projectRepository = projectRepository;
        this.graphTargetResolverService = graphTargetResolverService;
        this.subtypeSchemaRepository = subtypeSchemaRepository;
        this.subtypeValidator = subtypeValidator;
        this.eventPublisher = eventPublisher;
    }

    public OperationalAsset create(CreateAssetCommand command) {
        // Enforce bounded-string contracts at the service boundary rather
        // than relying on DTO `@Size` annotations. Non-controller callers
        // (or any other entry point that bypasses Bean Validation) would
        // otherwise leak a 500 from a VARCHAR overflow at save time
        // (codex cycle-4 finding 1). Cheap-fail before the project lookup.
        bounded("uid", command.uid(), OperationalAssetBounds.UID);
        bounded("name", command.name(), OperationalAssetBounds.NAME);
        bounded("owner", command.owner(), OperationalAssetBounds.OWNER);
        bounded("steward", command.steward(), OperationalAssetBounds.STEWARD);
        bounded(FIELD_SUBTYPE, command.subtype(), OperationalAssetBounds.SUBTYPE);

        var project = projectRepository
                .findById(command.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + command.projectId()));

        String normalizedUid = command.uid().toUpperCase(java.util.Locale.ROOT);
        if (assetRepository.existsByProjectIdAndUidIgnoreCase(command.projectId(), normalizedUid)) {
            throw new ConflictException("Asset with UID " + normalizedUid + " already exists in project");
        }

        var asset = new OperationalAsset(project, normalizedUid, command.name());
        if (command.description() != null) {
            asset.setDescription(command.description());
        }
        if (command.assetType() != null) {
            asset.setAssetType(command.assetType());
        }
        if (command.owner() != null) {
            asset.setOwner(command.owner());
        }
        if (command.steward() != null) {
            asset.setSteward(command.steward());
        }
        if (command.environment() != null) {
            asset.setEnvironment(command.environment());
        }
        if (command.criticality() != null) {
            asset.setCriticality(command.criticality());
        }
        if (command.businessContext() != null) {
            asset.setBusinessContext(command.businessContext());
        }
        if (command.scopeDesignation() != null) {
            asset.setScopeDesignation(command.scopeDesignation());
        }
        if (command.subtype() != null) {
            asset.setSubtype(command.subtype());
        }
        if (command.metadata() != null) {
            asset.setMetadata(command.metadata());
        }
        if (command.knowledgeState() != null) {
            asset.setKnowledgeState(command.knowledgeState());
        }
        validateAssetMetadata(asset);
        return assetRepository.save(asset);
    }

    /**
     * Max-length contracts for bounded {@code OperationalAsset} string fields.
     * Mirror the {@code @Column(length=...)} declarations on the entity and
     * the {@code @Size} declarations on the API DTOs; enforced at the
     * service layer so callers cannot bypass the contract by going around
     * Bean Validation (codex cycle-4 finding 1).
     */
    private static final class OperationalAssetBounds {
        static final int UID = 50;
        static final int NAME = 200;
        static final int OWNER = 200;
        static final int STEWARD = 200;
        static final int SUBTYPE = 100;
    }

    private void bounded(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "Asset " + field + " exceeds maximum length of " + max + " characters",
                    "asset_field_invalid",
                    Map.of(DETAIL_REASON, "field_too_long", DETAIL_FIELD, field, DETAIL_LIMIT, max));
        }
    }

    public OperationalAsset update(UUID projectId, UUID id, UpdateAssetCommand command) {
        var asset = getById(projectId, id);
        var oldSnapshot = riskBearingSnapshot(asset);
        applyAssetUpdates(asset, command);
        var saved = assetRepository.save(asset);
        publishStateChangeIfRiskFieldsMoved(saved, oldSnapshot);
        return saved;
    }

    @Deprecated(forRemoval = false)
    public OperationalAsset update(UUID id, UpdateAssetCommand command) {
        var asset = assetRepository.findById(id).orElseThrow(() -> new NotFoundException("Asset not found: " + id));
        var oldSnapshot = riskBearingSnapshot(asset);
        applyAssetUpdates(asset, command);
        var saved = assetRepository.save(asset);
        // The deprecated UUID-only overload still funnels through the same publisher path;
        // we resolve project id from the loaded aggregate so reassessment routing is not
        // silently skipped on legacy call sites (GC-T004 / C8, #863).
        publishStateChangeIfRiskFieldsMoved(saved, oldSnapshot);
        return saved;
    }

    @Transactional(readOnly = true)
    public OperationalAsset getById(UUID projectId, UUID id) {
        return assetRepository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Asset not found: " + id));
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public OperationalAsset getById(UUID id) {
        return assetRepository.findById(id).orElseThrow(() -> new NotFoundException("Asset not found: " + id));
    }

    @Transactional(readOnly = true)
    public OperationalAsset getByUid(UUID projectId, String uid) {
        return assetRepository
                .findByProjectIdAndUidIgnoreCase(projectId, uid)
                .orElseThrow(() -> new NotFoundException("Asset not found: " + uid));
    }

    @Transactional(readOnly = true)
    public List<OperationalAsset> listByProject(UUID projectId) {
        return assetRepository.findByProjectIdAndArchivedAtIsNull(projectId);
    }

    @SuppressWarnings("java:S107") // JPA @Query needs each @Param explicit; reflected on the public method
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
        return assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
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
        return assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                projectId, assetType, owner, steward, environment, criticality, scopeDesignation, subtype, null);
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
        // Call the repository directly rather than self-invoking the new
        // overload via `this.` — the @Transactional proxy would be bypassed
        // and Sonar S6809 flags the pattern. The repository call is itself
        // already covered by the class-level @Transactional.
        return assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                projectId, assetType, owner, steward, environment, criticality, scopeDesignation, null, null);
    }

    @Transactional(readOnly = true)
    public List<OperationalAsset> listByProjectAndType(UUID projectId, AssetType assetType) {
        return assetRepository.findByProjectIdAndAssetTypeAndArchivedAtIsNull(projectId, assetType);
    }

    public OperationalAsset archive(UUID projectId, UUID id) {
        var asset = getById(projectId, id);
        asset.archive();
        var saved = assetRepository.save(asset);
        publishArchive(saved);
        return saved;
    }

    @Deprecated(forRemoval = false)
    public OperationalAsset archive(UUID id) {
        var asset = getById(id);
        asset.archive();
        var saved = assetRepository.save(asset);
        publishArchive(saved);
        return saved;
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
        var asset = assetRepository.findById(id).orElseThrow(() -> new NotFoundException("Asset not found: " + id));
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

    public AssetRelation createRelation(UUID projectId, UUID sourceId, UUID targetId, AssetRelationType relationType) {
        return createRelation(
                projectId,
                new CreateAssetRelationCommand(targetId, relationType, null, null, null, null, null, null),
                sourceId);
    }

    @Deprecated(forRemoval = false)
    public AssetRelation createRelation(UUID sourceId, UUID targetId, AssetRelationType relationType) {
        return createRelation(
                new CreateAssetRelationCommand(targetId, relationType, null, null, null, null, null, null), sourceId);
    }

    public AssetRelation createRelation(UUID projectId, CreateAssetRelationCommand command, UUID sourceId) {
        if (sourceId.equals(command.targetId())) {
            throw new DomainValidationException("An asset cannot relate to itself");
        }
        if (relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                sourceId, command.targetId(), command.relationType())) {
            throw new ConflictException("Relation " + command.relationType() + " already exists between " + sourceId
                    + " and " + command.targetId());
        }
        var source = getById(projectId, sourceId);
        var target = getById(projectId, command.targetId());
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

    @Deprecated(forRemoval = false)
    public AssetRelation createRelation(CreateAssetRelationCommand command, UUID sourceId) {
        if (sourceId.equals(command.targetId())) {
            throw new DomainValidationException("An asset cannot relate to itself");
        }
        if (relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                sourceId, command.targetId(), command.relationType())) {
            throw new ConflictException("Relation " + command.relationType() + " already exists between " + sourceId
                    + " and " + command.targetId());
        }
        var source = getById(sourceId);
        var target = getById(command.targetId());
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

    public AssetRelation updateRelation(
            UUID projectId, UUID assetId, UUID relationId, UpdateAssetRelationCommand command) {
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

    @Deprecated(forRemoval = false)
    public AssetRelation updateRelation(UUID assetId, UUID relationId, UpdateAssetRelationCommand command) {
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

    @Transactional(readOnly = true)
    public List<AssetRelation> getRelations(UUID projectId, UUID assetId) {
        getById(projectId, assetId);
        var outgoing = relationRepository.findBySourceIdWithEntities(assetId);
        var incoming = relationRepository.findByTargetIdWithEntities(assetId);
        var combined = new ArrayList<AssetRelation>(outgoing);
        combined.addAll(incoming);
        return combined;
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetRelation> getRelations(UUID assetId) {
        getById(assetId);
        var outgoing = relationRepository.findBySourceIdWithEntities(assetId);
        var incoming = relationRepository.findByTargetIdWithEntities(assetId);
        var combined = new ArrayList<AssetRelation>(outgoing);
        combined.addAll(incoming);
        return combined;
    }

    public void deleteRelation(UUID projectId, UUID assetId, UUID relationId) {
        relationRepository.delete(getRelationBelongingTo(projectId, assetId, relationId));
    }

    @Deprecated(forRemoval = false)
    public void deleteRelation(UUID assetId, UUID relationId) {
        relationRepository.delete(getLegacyRelationBelongingTo(assetId, relationId));
    }

    // --- Asset Links (cross-entity linking) ---

    public AssetLink createLink(UUID projectId, UUID assetId, CreateAssetLinkCommand command) {
        var asset = getById(projectId, assetId);
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
    public AssetLink createLink(UUID assetId, CreateAssetLinkCommand command) {
        var asset = getById(assetId);
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

    @Transactional(readOnly = true)
    public List<AssetLink> getLinksForAsset(UUID projectId, UUID assetId) {
        getById(projectId, assetId);
        return linkRepository.findByAssetId(assetId);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetLink> getLinksForAsset(UUID assetId) {
        getById(assetId);
        return linkRepository.findByAssetId(assetId);
    }

    @Transactional(readOnly = true)
    public List<AssetLink> getLinksForAssetByTargetType(UUID projectId, UUID assetId, AssetLinkTargetType targetType) {
        getById(projectId, assetId);
        return linkRepository.findByAssetIdAndTargetType(assetId, targetType);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetLink> getLinksForAssetByTargetType(UUID assetId, AssetLinkTargetType targetType) {
        getById(assetId);
        return linkRepository.findByAssetIdAndTargetType(assetId, targetType);
    }

    @Transactional(readOnly = true)
    public List<AssetLink> getLinksByTarget(
            UUID projectId, AssetLinkTargetType targetType, UUID targetEntityId, String targetIdentifier) {
        if (targetEntityId != null) {
            return linkRepository.findByTargetTypeAndTargetEntityIdAndProjectId(targetType, targetEntityId, projectId);
        }
        return linkRepository.findByTargetTypeAndTargetIdentifierAndProjectId(targetType, targetIdentifier, projectId);
    }

    public void deleteLink(UUID projectId, UUID assetId, UUID linkId) {
        var link = linkRepository
                .findByIdWithAssetAndProjectId(linkId, projectId)
                .orElseThrow(() -> new NotFoundException("Link not found: " + linkId));
        if (!link.getAsset().getId().equals(assetId)) {
            throw new NotFoundException("Link " + linkId + " does not belong to asset " + assetId);
        }
        linkRepository.delete(link);
    }

    @Deprecated(forRemoval = false)
    public void deleteLink(UUID assetId, UUID linkId) {
        var link =
                linkRepository.findById(linkId).orElseThrow(() -> new NotFoundException("Link not found: " + linkId));
        if (!link.getAsset().getId().equals(assetId)) {
            throw new NotFoundException("Link " + linkId + " does not belong to asset " + assetId);
        }
        linkRepository.delete(link);
    }

    // --- External Identifiers (source provenance) ---

    public AssetExternalId createExternalId(UUID projectId, UUID assetId, CreateAssetExternalIdCommand command) {
        var asset = getById(projectId, assetId);
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
    public AssetExternalId createExternalId(UUID assetId, CreateAssetExternalIdCommand command) {
        var asset = getById(assetId);
        if (externalIdRepository.existsByAssetIdAndSourceSystemAndSourceId(
                assetId, command.sourceSystem(), command.sourceId())) {
            throw new ConflictException("External ID already exists: " + command.sourceSystem() + ":"
                    + command.sourceId() + " for asset " + assetId);
        }
        var extId = new AssetExternalId(asset, command.sourceSystem(), command.sourceId());
        applyProvenanceFields(extId, command.collectedAt(), command.confidence());
        return externalIdRepository.save(extId);
    }

    public AssetExternalId updateExternalId(
            UUID projectId, UUID assetId, UUID extIdId, UpdateAssetExternalIdCommand command) {
        var extId = getExternalIdBelongingTo(projectId, assetId, extIdId);
        applyProvenanceFields(extId, command.collectedAt(), command.confidence());
        return externalIdRepository.save(extId);
    }

    @Deprecated(forRemoval = false)
    public AssetExternalId updateExternalId(UUID assetId, UUID extIdId, UpdateAssetExternalIdCommand command) {
        var extId = getLegacyExternalIdBelongingTo(assetId, extIdId);
        applyProvenanceFields(extId, command.collectedAt(), command.confidence());
        return externalIdRepository.save(extId);
    }

    @Transactional(readOnly = true)
    public List<AssetExternalId> getExternalIds(UUID projectId, UUID assetId) {
        getById(projectId, assetId);
        return externalIdRepository.findByAssetId(assetId);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetExternalId> getExternalIds(UUID assetId) {
        getById(assetId);
        return externalIdRepository.findByAssetId(assetId);
    }

    @Transactional(readOnly = true)
    public List<AssetExternalId> getExternalIdsBySource(UUID projectId, UUID assetId, String sourceSystem) {
        getById(projectId, assetId);
        return externalIdRepository.findByAssetIdAndSourceSystem(assetId, sourceSystem);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<AssetExternalId> getExternalIdsBySource(UUID assetId, String sourceSystem) {
        getById(assetId);
        return externalIdRepository.findByAssetIdAndSourceSystem(assetId, sourceSystem);
    }

    @Transactional(readOnly = true)
    public List<AssetExternalId> findByExternalId(UUID projectId, String sourceSystem, String sourceId) {
        return externalIdRepository.findBySourceSystemAndSourceIdAndProjectId(sourceSystem, sourceId, projectId);
    }

    public void deleteExternalId(UUID projectId, UUID assetId, UUID extIdId) {
        externalIdRepository.delete(getExternalIdBelongingTo(projectId, assetId, extIdId));
    }

    @Deprecated(forRemoval = false)
    public void deleteExternalId(UUID assetId, UUID extIdId) {
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

    private void applyAssetUpdates(OperationalAsset asset, UpdateAssetCommand command) {
        // Service-layer bounded-string checks (mirror create-path enforcement).
        // Non-controller callers must hit the same envelope as DTO-validated ones.
        bounded("name", command.name(), OperationalAssetBounds.NAME);
        if (!command.clearOwner()) {
            bounded("owner", command.owner(), OperationalAssetBounds.OWNER);
        }
        if (!command.clearSteward()) {
            bounded("steward", command.steward(), OperationalAssetBounds.STEWARD);
        }
        if (!command.clearSubtype()) {
            bounded("subtype", command.subtype(), OperationalAssetBounds.SUBTYPE);
        }
        applyCoreFieldUpdates(asset, command);
        applyMetadataUpdates(asset, command);
        applySubtypeUpdates(asset, command);
        validateAssetMetadata(asset);
    }

    private void applyCoreFieldUpdates(OperationalAsset asset, UpdateAssetCommand command) {
        if (command.name() != null) {
            if (command.name().isBlank()) {
                throw new DomainValidationException("Asset name must not be blank");
            }
            asset.setName(command.name());
        }
        if (command.description() != null) {
            asset.setDescription(command.description());
        }
        if (command.assetType() != null) {
            asset.setAssetType(command.assetType());
        }
        if (command.knowledgeState() != null) {
            asset.setKnowledgeState(command.knowledgeState());
        }
    }

    private void applyMetadataUpdates(OperationalAsset asset, UpdateAssetCommand command) {
        // GC-M012 nullable metadata: clear flag wins over assign so a caller
        // can re-undesignate a field that was previously set. Without this,
        // NULL ("not designated") would be unreachable after first assignment
        // because enum binding cannot accept blank strings.
        applyClearOrSet(command.clearOwner(), command.owner(), asset::setOwner);
        applyClearOrSet(command.clearSteward(), command.steward(), asset::setSteward);
        applyClearOrSet(command.clearEnvironment(), command.environment(), asset::setEnvironment);
        applyClearOrSet(command.clearCriticality(), command.criticality(), asset::setCriticality);
        applyClearOrSet(command.clearBusinessContext(), command.businessContext(), asset::setBusinessContext);
        applyClearOrSet(command.clearScopeDesignation(), command.scopeDesignation(), asset::setScopeDesignation);
    }

    private <T> void applyClearOrSet(boolean clear, T newValue, java.util.function.Consumer<T> setter) {
        if (clear) {
            setter.accept(null);
        } else if (newValue != null) {
            setter.accept(newValue);
        }
    }

    private void applySubtypeUpdates(OperationalAsset asset, UpdateAssetCommand command) {
        applyClearOrSet(command.clearSubtype(), command.subtype(), asset::setSubtype);
        applyClearOrSet(command.clearMetadata(), command.metadata(), asset::setMetadata);
    }

    private void validateAssetMetadata(OperationalAsset asset) {
        String subtype = asset.getSubtype();
        if (subtype != null && subtype.isBlank()) {
            // The schema registry rejects blank subtype keys (validateSubtype
            // SchemaPayload); accepting blank subtypes on assets would create
            // a second invalid namespace that can never match a registered
            // schema (codex over-cap finding 4 on #722). Reject in the same
            // direction.
            throw new DomainValidationException(
                    "Asset subtype must not be blank", "asset_subtype_invalid", Map.of("reason", "blank_subtype"));
        }
        if (subtype == null) {
            // No subtype: bounds only on metadata; schemas key off subtype.
            subtypeValidator.validateMetadataBounds(asset.getMetadata());
            return;
        }
        var activeSchema = subtypeSchemaRepository
                .findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                        asset.getProject().getId(),
                        asset.getAssetType(),
                        asset.getSubtype(),
                        AssetSubtypeSchemaStatus.ACTIVE)
                .orElse(null);
        if (activeSchema == null) {
            subtypeValidator.validateMetadataBounds(asset.getMetadata());
            return;
        }
        subtypeValidator.validateAgainstSchema(asset.getMetadata(), activeSchema.getSchemaBody());
    }

    // --- Subtype schema registry (GC-M011) ---

    public AssetSubtypeSchema registerSubtypeSchema(CreateAssetSubtypeSchemaCommand command) {
        validateSubtypeSchemaPayload(command.assetType(), command.subtype(), command.schemaVersion());
        // Validate schema-body shape BEFORE deprecating the prior ACTIVE row,
        // so a malformed body cannot leave the registry without an ACTIVE
        // entry. ACTIVE registry rows MUST declare at least one field — an
        // empty schema body advertises "schema layering" while enforcing
        // nothing (codex over-cap finding on #722).
        subtypeValidator.validateSchemaBody(command.schemaBody(), /* requireFields */ true);
        var project = projectRepository
                .findById(command.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + command.projectId()));
        if (subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                command.projectId(), command.assetType(), command.subtype(), command.schemaVersion())) {
            throw new ConflictException("Subtype schema version " + command.schemaVersion() + " already exists for "
                    + command.assetType() + ":" + command.subtype());
        }
        subtypeSchemaRepository
                .findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                        command.projectId(), command.assetType(), command.subtype(), AssetSubtypeSchemaStatus.ACTIVE)
                .ifPresent(existing -> {
                    existing.deprecate();
                    // Flush the deprecation UPDATE before issuing the new
                    // ACTIVE INSERT — Hibernate's default action ordering
                    // flushes INSERTs before UPDATEs in the same session,
                    // which would trip uk_asset_subtype_schema_active (the
                    // partial unique index from V075) against the
                    // still-ACTIVE prior row.
                    subtypeSchemaRepository.saveAndFlush(existing);
                });
        var schema = new AssetSubtypeSchema(
                project, command.assetType(), command.subtype(), command.schemaVersion(), command.schemaBody());
        if (command.description() != null) {
            schema.setDescription(command.description());
        }
        try {
            return subtypeSchemaRepository.saveAndFlush(schema);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // The partial unique index on (project, asset_type, subtype) WHERE
            // status='ACTIVE' (V075) is the safety net for a concurrent race
            // past the service-layer existence check. Translate to a Conflict
            // so callers see the existing error envelope rather than a 500.
            throw new ConflictException(
                    "Concurrent registration: another ACTIVE schema for " + command.assetType() + ":"
                            + command.subtype() + " was committed first",
                    "asset_subtype_schema_active_conflict",
                    Map.of(
                            "assetType", command.assetType().name(),
                            "subtype", command.subtype()));
        }
    }

    public AssetSubtypeSchema updateSubtypeSchema(UUID projectId, UUID id, UpdateAssetSubtypeSchemaCommand command) {
        var schema = loadSubtypeSchema(projectId, id);
        boolean active = schema.getStatus() == AssetSubtypeSchemaStatus.ACTIVE;
        // ACTIVE rows MUST keep an enforceable schema body. Reject
        // clearSchemaBody on ACTIVE rows so callers cannot null out the
        // contract via update; deprecate the row first if that's the intent.
        if (command.clearSchemaBody() && active) {
            throw new DomainValidationException(
                    "Cannot clear schemaBody on an ACTIVE subtype schema; deprecate first",
                    "asset_subtype_schema_active_body_required",
                    Map.of("reason", "schema_body_required"));
        }
        if (!command.clearSchemaBody() && command.schemaBody() != null) {
            subtypeValidator.validateSchemaBody(command.schemaBody(), /* requireFields */ active);
        }
        applyClearOrSet(command.clearDescription(), command.description(), schema::setDescription);
        applyClearOrSet(command.clearSchemaBody(), command.schemaBody(), schema::setSchemaBody);
        return subtypeSchemaRepository.save(schema);
    }

    public AssetSubtypeSchema deprecateSubtypeSchema(UUID projectId, UUID id) {
        var schema = loadSubtypeSchema(projectId, id);
        if (schema.getStatus() != AssetSubtypeSchemaStatus.DEPRECATED) {
            schema.deprecate();
            subtypeSchemaRepository.save(schema);
        }
        return schema;
    }

    @Transactional(readOnly = true)
    public AssetSubtypeSchema getSubtypeSchema(UUID projectId, UUID id) {
        return loadSubtypeSchema(projectId, id);
    }

    /**
     * Internal lookup shared with the {@code update*} / {@code deprecate*}
     * paths. Bypassing the {@code public getSubtypeSchema} method avoids the
     * Sonar S6809 self-invocation pattern (calling a {@code @Transactional}
     * method via {@code this} skips the proxy).
     */
    private AssetSubtypeSchema loadSubtypeSchema(UUID projectId, UUID id) {
        return subtypeSchemaRepository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Asset subtype schema not found: " + id));
    }

    @Transactional(readOnly = true)
    public AssetSubtypeSchema getActiveSubtypeSchema(UUID projectId, AssetType assetType, String subtype) {
        return subtypeSchemaRepository
                .findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                        projectId, assetType, subtype, AssetSubtypeSchemaStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No active subtype schema for " + assetType + ":" + subtype));
    }

    @Transactional(readOnly = true)
    public List<AssetSubtypeSchema> listSubtypeSchemas(UUID projectId, AssetType assetType, String subtype) {
        if (assetType == null && subtype == null) {
            return subtypeSchemaRepository.findByProjectId(projectId);
        }
        if (assetType != null && subtype == null) {
            return subtypeSchemaRepository.findByProjectIdAndAssetType(projectId, assetType);
        }
        if (assetType == null) {
            // Subtype without assetType is ambiguous: same subtype string may
            // legitimately exist under different AssetType buckets. Require
            // both or neither — saves callers a silent merge that loses the
            // top-level classification distinction.
            throw new DomainValidationException(
                    "Listing by subtype requires assetType",
                    "asset_subtype_schema_filter_invalid",
                    Map.of("reason", "subtype_without_asset_type"));
        }
        return subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtype(projectId, assetType, subtype);
    }

    private void validateSubtypeSchemaPayload(AssetType assetType, String subtype, String schemaVersion) {
        if (assetType == null) {
            throw new DomainValidationException("assetType is required");
        }
        if (subtype == null || subtype.isBlank()) {
            throw new DomainValidationException("subtype must not be blank");
        }
        if (subtype.length() > 100) {
            throw new DomainValidationException("subtype must not exceed 100 characters");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new DomainValidationException("schemaVersion must not be blank");
        }
        if (schemaVersion.length() > 50) {
            throw new DomainValidationException("schemaVersion must not exceed 50 characters");
        }
    }

    private void validateSameProject(OperationalAsset source, OperationalAsset target) {
        if (!source.getProject().getId().equals(target.getProject().getId())) {
            throw new DomainValidationException("Assets cannot relate across different projects");
        }
    }

    private void applyProvenanceFields(AssetExternalId extId, Instant collectedAt, String confidence) {
        if (collectedAt != null) {
            extId.setCollectedAt(collectedAt);
        }
        if (confidence != null) {
            extId.setConfidence(confidence);
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

    // ----- GC-T004 / C8 (#863): reassessment publisher -----

    /**
     * Snapshot of the asset's risk-bearing fields immediately before mutation. Compared
     * to the post-save state to decide whether to publish {@link AssetStateChangedEvent}.
     * The snapshot uses raw field references (not getters that synthesize state) so
     * publish-on-no-op-write is impossible.
     */
    private Map<String, Object> riskBearingSnapshot(OperationalAsset asset) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("assetType", asset.getAssetType());
        snap.put("environment", asset.getEnvironment());
        snap.put("criticality", asset.getCriticality());
        snap.put("scopeDesignation", asset.getScopeDesignation());
        snap.put("businessContext", asset.getBusinessContext());
        snap.put("owner", asset.getOwner());
        snap.put("steward", asset.getSteward());
        snap.put("knowledgeState", asset.getKnowledgeState());
        return snap;
    }

    private void publishStateChangeIfRiskFieldsMoved(OperationalAsset saved, Map<String, Object> oldSnap) {
        var newSnap = riskBearingSnapshot(saved);
        Set<String> changed = new HashSet<>();
        Map<String, Object> oldVals = new HashMap<>();
        Map<String, Object> newVals = new HashMap<>();
        for (String field : RISK_BEARING_FIELDS) {
            var oldVal = oldSnap.get(field);
            var newVal = newSnap.get(field);
            if (!java.util.Objects.equals(oldVal, newVal)) {
                changed.add(field);
                oldVals.put(field, oldVal);
                newVals.put(field, newVal);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        eventPublisher.publishEvent(new AssetStateChangedEvent(new ReassessmentSignal(
                saved.getProject().getId(),
                ReassessmentTriggerCategory.ASSET_STATE_CHANGED,
                ReassessmentSourceEntityType.ASSET,
                saved.getId(),
                changed,
                oldVals,
                newVals,
                Instant.now())));
    }

    private void publishArchive(OperationalAsset saved) {
        // Archive is intrinsically a transition to "archived" — the listener treats it as
        // a risk-bearing change regardless of which fields moved. archivedAt is the
        // only field carried in the changed-field set so consumers can route on it.
        eventPublisher.publishEvent(new AssetStateChangedEvent(new ReassessmentSignal(
                saved.getProject().getId(),
                ReassessmentTriggerCategory.ASSET_STATE_CHANGED,
                ReassessmentSourceEntityType.ASSET,
                saved.getId(),
                Set.of("archivedAt"),
                Map.of(),
                Map.of("archivedAt", saved.getArchivedAt()),
                Instant.now())));
    }
}
