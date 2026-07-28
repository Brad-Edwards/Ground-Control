package com.keplerops.groundcontrol.domain.assets.service;

import static com.keplerops.groundcontrol.domain.assets.service.AssetService.DETAIL_FIELD;
import static com.keplerops.groundcontrol.domain.assets.service.AssetService.DETAIL_LIMIT;
import static com.keplerops.groundcontrol.domain.assets.service.AssetService.DETAIL_REASON;
import static com.keplerops.groundcontrol.domain.assets.service.AssetService.FIELD_OWNER;
import static com.keplerops.groundcontrol.domain.assets.service.AssetService.FIELD_STEWARD;
import static com.keplerops.groundcontrol.domain.assets.service.AssetService.FIELD_SUBTYPE;
import static com.keplerops.groundcontrol.domain.assets.service.AssetService.applyClearOrSet;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService.OperationalAssetBounds;
import com.keplerops.groundcontrol.domain.assets.state.AssetSubtypeSchemaStatus;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import java.util.Map;
import java.util.UUID;

/**
 * Creation and partial update of operational assets.
 *
 * Split out of {@link AssetService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class AssetWriteOperations {

    private final OperationalAssetRepository assetRepository;
    private final ProjectRepository projectRepository;
    private final AssetSubtypeSchemaRepository subtypeSchemaRepository;
    private final AssetSubtypeValidator subtypeValidator;
    private final AssetService service;

    AssetWriteOperations(
            OperationalAssetRepository assetRepository,
            ProjectRepository projectRepository,
            AssetSubtypeSchemaRepository subtypeSchemaRepository,
            AssetSubtypeValidator subtypeValidator,
            AssetService service) {
        this.assetRepository = assetRepository;
        this.projectRepository = projectRepository;
        this.subtypeSchemaRepository = subtypeSchemaRepository;
        this.subtypeValidator = subtypeValidator;
        this.service = service;
    }

    OperationalAsset create(CreateAssetCommand command) {
        // Enforce bounded-string contracts at the service boundary rather
        // than relying on DTO `@Size` annotations. Non-controller callers
        // (or any other entry point that bypasses Bean Validation) would
        // otherwise leak a 500 from a VARCHAR overflow at save time
        // (codex cycle-4 finding 1). Cheap-fail before the project lookup.
        bounded("uid", command.uid(), OperationalAssetBounds.UID);
        bounded("name", command.name(), OperationalAssetBounds.NAME);
        bounded(FIELD_OWNER, command.owner(), OperationalAssetBounds.OWNER);
        bounded(FIELD_STEWARD, command.steward(), OperationalAssetBounds.STEWARD);
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

    private void bounded(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "Asset " + field + " exceeds maximum length of " + max + " characters",
                    "asset_field_invalid",
                    Map.of(DETAIL_REASON, "field_too_long", DETAIL_FIELD, field, DETAIL_LIMIT, max));
        }
    }

    OperationalAsset update(UUID projectId, UUID id, UpdateAssetCommand command) {
        var asset = service.getById(projectId, id);
        applyAssetUpdates(asset, command);
        return assetRepository.save(asset);
    }

    @Deprecated(forRemoval = false)
    OperationalAsset update(UUID id, UpdateAssetCommand command) {
        var asset = assetRepository.findById(id).orElseThrow(() -> new NotFoundException("Asset not found: " + id));
        applyAssetUpdates(asset, command);
        return assetRepository.save(asset);
    }

    private void applyAssetUpdates(OperationalAsset asset, UpdateAssetCommand command) {
        // Service-layer bounded-string checks (mirror create-path enforcement).
        // Non-controller callers must hit the same envelope as DTO-validated ones.
        bounded("name", command.name(), OperationalAssetBounds.NAME);
        if (!command.clearOwner()) {
            bounded(FIELD_OWNER, command.owner(), OperationalAssetBounds.OWNER);
        }
        if (!command.clearSteward()) {
            bounded(FIELD_STEWARD, command.steward(), OperationalAssetBounds.STEWARD);
        }
        if (!command.clearSubtype()) {
            bounded(FIELD_SUBTYPE, command.subtype(), OperationalAssetBounds.SUBTYPE);
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
}
