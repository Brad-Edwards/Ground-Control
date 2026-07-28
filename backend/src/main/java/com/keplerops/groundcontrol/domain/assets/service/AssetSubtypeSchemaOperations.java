package com.keplerops.groundcontrol.domain.assets.service;

import static com.keplerops.groundcontrol.domain.assets.service.AssetService.FIELD_ASSET_TYPE;
import static com.keplerops.groundcontrol.domain.assets.service.AssetService.FIELD_SUBTYPE;
import static com.keplerops.groundcontrol.domain.assets.service.AssetService.applyClearOrSet;

import com.keplerops.groundcontrol.domain.assets.model.AssetSubtypeSchema;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetSubtypeSchemaStatus;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registry operations for asset subtype schemas.
 *
 * Split out of {@link AssetService} under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). The service keeps the public API, the
 * transaction boundary and its constructor; the bodies here are unchanged,
 * so the split is invisible to callers and to existing tests.
 */
final class AssetSubtypeSchemaOperations {

    private final ProjectRepository projectRepository;
    private final AssetSubtypeSchemaRepository subtypeSchemaRepository;
    private final AssetSubtypeValidator subtypeValidator;

    AssetSubtypeSchemaOperations(
            ProjectRepository projectRepository,
            AssetSubtypeSchemaRepository subtypeSchemaRepository,
            AssetSubtypeValidator subtypeValidator) {
        this.projectRepository = projectRepository;
        this.subtypeSchemaRepository = subtypeSchemaRepository;
        this.subtypeValidator = subtypeValidator;
    }

    // --- Subtype schema registry (GC-M011) ---

    AssetSubtypeSchema registerSubtypeSchema(CreateAssetSubtypeSchemaCommand command) {
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
                            FIELD_ASSET_TYPE, command.assetType().name(),
                            FIELD_SUBTYPE, command.subtype()));
        }
    }

    AssetSubtypeSchema updateSubtypeSchema(UUID projectId, UUID id, UpdateAssetSubtypeSchemaCommand command) {
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

    AssetSubtypeSchema deprecateSubtypeSchema(UUID projectId, UUID id) {
        var schema = loadSubtypeSchema(projectId, id);
        if (schema.getStatus() != AssetSubtypeSchemaStatus.DEPRECATED) {
            schema.deprecate();
            subtypeSchemaRepository.save(schema);
        }
        return schema;
    }

    AssetSubtypeSchema getSubtypeSchema(UUID projectId, UUID id) {
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

    AssetSubtypeSchema getActiveSubtypeSchema(UUID projectId, AssetType assetType, String subtype) {
        return subtypeSchemaRepository
                .findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                        projectId, assetType, subtype, AssetSubtypeSchemaStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("No active subtype schema for " + assetType + ":" + subtype));
    }

    List<AssetSubtypeSchema> listSubtypeSchemas(UUID projectId, AssetType assetType, String subtype) {
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
}
