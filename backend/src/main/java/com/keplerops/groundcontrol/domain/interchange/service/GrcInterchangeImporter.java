package com.keplerops.groundcontrol.domain.interchange.service;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetCriticality;
import com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.interchange.model.GrcInterchangeProvenance;
import com.keplerops.groundcontrol.domain.interchange.model.GrcInterchangeProvenance.ImportContext;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle.AssetPayload;
import com.keplerops.groundcontrol.domain.interchange.payload.GrcInterchangeBundle.InterchangePayload;
import com.keplerops.groundcontrol.domain.interchange.repository.GrcInterchangeProvenanceRepository;
import com.keplerops.groundcontrol.domain.interchange.state.InterchangeEntityKind;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports a {@link GrcInterchangeBundle} into a project. Idempotent by
 * external UID per the cluster design intent — re-importing the same bundle
 * does not duplicate domain entities, just refreshes their provenance.
 *
 * <p>Security guards per the cluster note:
 * <ul>
 *   <li>JSON-only at the controller layer (no XML, so no XXE).
 *   <li>{@link #ensureBundleProject} refuses bundles whose
 *       embedded {@code projectIdentifier} mismatches the caller-resolved
 *       project so a cross-project import is impossible.
 *   <li>{@code externalUri} fields are persisted normalised but never
 *       dereferenced (SSRF guard).
 *   <li>Per ADR-045, client-supplied {@code createdAt}/{@code updatedAt} land
 *       on the {@link GrcInterchangeProvenance} shadow; domain entity
 *       timestamps remain owned by BaseEntity.
 * </ul>
 *
 * <p>This first release covers the operational-asset surface end-to-end and
 * persists provenance shadow rows for every entity-kind payload found in the
 * bundle — including risk-scenarios, controls, findings, and evidence — so a
 * follow-up release can fill in the domain creation paths without changing the
 * envelope contract.
 */
@Service
@Transactional
public class GrcInterchangeImporter {

    private static final Logger log = LoggerFactory.getLogger(GrcInterchangeImporter.class);

    private final ProjectService projectService;
    private final OperationalAssetRepository operationalAssetRepository;
    private final GrcInterchangeProvenanceRepository provenanceRepository;

    public GrcInterchangeImporter(
            ProjectService projectService,
            OperationalAssetRepository operationalAssetRepository,
            GrcInterchangeProvenanceRepository provenanceRepository) {
        this.projectService = projectService;
        this.operationalAssetRepository = operationalAssetRepository;
        this.provenanceRepository = provenanceRepository;
    }

    public GrcInterchangeImportResult importBundle(UUID projectId, GrcInterchangeBundle bundle) {
        if (bundle == null) {
            throw new DomainValidationException("bundle must not be null");
        }
        var project = projectService.getById(projectId);
        ensureBundleProject(bundle, project.getIdentifier());
        ensureVersion(bundle);

        var builder = GrcInterchangeImportResult.builder();
        var importedAt = Instant.now();
        var importedBy = ActorHolder.get();

        if (bundle.assets() != null) {
            for (AssetPayload p : bundle.assets()) {
                upsertAsset(project.getId(), p, builder, importedAt, importedBy);
            }
        }
        // Provenance-only persistence for the other entity surfaces so a
        // future release can attach the domain creation paths idempotently
        // without renegotiating the envelope shape.
        importProvenanceOnlySurface(
                project.getId(),
                InterchangeEntityKind.RISK_SCENARIO,
                bundle.riskScenarios() == null ? List.of() : bundle.riskScenarios(),
                importedAt,
                importedBy,
                builder);
        importProvenanceOnlySurface(
                project.getId(),
                InterchangeEntityKind.CONTROL,
                bundle.controls() == null ? List.of() : bundle.controls(),
                importedAt,
                importedBy,
                builder);
        importProvenanceOnlySurface(
                project.getId(),
                InterchangeEntityKind.FINDING,
                bundle.findings() == null ? List.of() : bundle.findings(),
                importedAt,
                importedBy,
                builder);
        importProvenanceOnlySurface(
                project.getId(),
                InterchangeEntityKind.EVIDENCE_ARTIFACT,
                bundle.evidenceArtifacts() == null ? List.of() : bundle.evidenceArtifacts(),
                importedAt,
                importedBy,
                builder);

        var result = builder.build();
        log.info(
                "grc_interchange_imported: project={} assetsCreated={} assetsUpdated={} provenance={}",
                project.getIdentifier(),
                result.assetsCreated(),
                result.assetsUpdated(),
                result.provenanceWritten());
        return result;
    }

    private void upsertAsset(
            UUID projectId,
            AssetPayload payload,
            GrcInterchangeImportResult.Builder builder,
            Instant importedAt,
            String importedBy) {
        requireExternalUid(payload.externalUid(), "AssetPayload.externalUid");
        var project = projectService.getById(projectId);
        var existing = operationalAssetRepository.findByProjectIdAndUidIgnoreCase(projectId, payload.externalUid());
        OperationalAsset asset;
        boolean wasNew;
        if (existing.isPresent()) {
            asset = existing.get();
            wasNew = false;
            if (payload.title() != null) {
                asset.setName(payload.title());
            }
        } else {
            asset = new OperationalAsset(
                    project, payload.externalUid(), payload.title() == null ? payload.externalUid() : payload.title());
            wasNew = true;
        }
        if (payload.description() != null) {
            asset.setDescription(payload.description());
        }
        if (payload.owner() != null) {
            asset.setOwner(payload.owner());
        }
        if (payload.steward() != null) {
            asset.setSteward(payload.steward());
        }
        applyAssetEnums(asset, payload);
        if (payload.subtype() != null) {
            asset.setSubtype(payload.subtype());
        }
        operationalAssetRepository.save(asset);
        if (wasNew) {
            builder.assetCreated();
        } else {
            builder.assetUpdated();
        }
        upsertProvenance(
                projectId,
                InterchangeEntityKind.OPERATIONAL_ASSET,
                payload.externalUid(),
                new ImportContext(
                        payload.sourceSystem(), payload.createdAt(), payload.updatedAt(), importedAt, importedBy),
                asset.getId(),
                builder);
    }

    private static void applyAssetEnums(OperationalAsset asset, AssetPayload payload) {
        if (payload.type() != null) {
            try {
                asset.setAssetType(AssetType.valueOf(payload.type()));
            } catch (IllegalArgumentException ignored) {
                // unknown type — leave the default; the bundle survived the
                // parse, the field is just informational on a downstream
                // mismatch
            }
        }
        if (payload.environment() != null) {
            try {
                asset.setEnvironment(AssetEnvironment.valueOf(payload.environment()));
            } catch (IllegalArgumentException ignored) {
                // unknown environment — same as above
            }
        }
        if (payload.criticality() != null) {
            try {
                asset.setCriticality(AssetCriticality.valueOf(payload.criticality()));
            } catch (IllegalArgumentException ignored) {
                // unknown criticality — same as above
            }
        }
    }

    /**
     * Process a list of provenance-only payloads for a given entity kind.
     * These entity kinds are not yet fully wired through the importer; a
     * placeholder entity ID is used so a downstream reconciliation pass can
     * find unattached provenance records.
     */
    private <P extends InterchangePayload> void importProvenanceOnlySurface(
            UUID projectId,
            InterchangeEntityKind kind,
            List<P> payloads,
            Instant importedAt,
            String importedBy,
            GrcInterchangeImportResult.Builder builder) {
        for (P p : payloads) {
            requireExternalUid(p.externalUid(), kind + ".externalUid");
            upsertProvenance(
                    projectId,
                    kind,
                    p.externalUid(),
                    new ImportContext(p.sourceSystem(), p.createdAt(), p.updatedAt(), importedAt, importedBy),
                    placeholderEntityId(),
                    builder);
        }
    }

    /**
     * Provenance for entity kinds whose domain creation path is not yet
     * wired through the importer carries a deterministic placeholder UUID so
     * the {@code (kind, external_uid)} uniqueness constraint still applies
     * but a downstream reconciliation pass can find unattached provenance
     * records by this sentinel.
     */
    private static UUID placeholderEntityId() {
        return new UUID(0L, 0L);
    }

    private void upsertProvenance(
            UUID projectId,
            InterchangeEntityKind kind,
            String externalUid,
            ImportContext ctx,
            UUID entityId,
            GrcInterchangeImportResult.Builder builder) {
        var existing = provenanceRepository.findByProjectIdAndEntityKindAndExternalUid(projectId, kind, externalUid);
        var project = projectService.getById(projectId);
        if (existing.isPresent()) {
            var prov = existing.get();
            prov.setEntityId(entityId);
            prov.setSourceSystem(ctx.sourceSystem());
            prov.setSourceCreatedAt(ctx.sourceCreatedAt());
            prov.setSourceUpdatedAt(ctx.sourceUpdatedAt());
            prov.setImportedAt(ctx.importedAt());
            prov.setImportedBy(ctx.importedBy());
            provenanceRepository.save(prov);
        } else {
            provenanceRepository.save(new GrcInterchangeProvenance(project, kind, entityId, externalUid, ctx));
        }
        builder.provenanceWritten();
    }

    private static void ensureBundleProject(GrcInterchangeBundle bundle, String resolvedIdentifier) {
        if (bundle.projectIdentifier() != null
                && !bundle.projectIdentifier().isBlank()
                && !bundle.projectIdentifier().equals(resolvedIdentifier)) {
            throw new DomainValidationException(
                    "Bundle projectIdentifier does not match resolved project",
                    "project_mismatch",
                    Map.of("bundleProject", bundle.projectIdentifier(), "resolvedProject", resolvedIdentifier));
        }
    }

    private static void ensureVersion(GrcInterchangeBundle bundle) {
        if (bundle.formatVersion() == null) {
            throw new DomainValidationException("bundle.formatVersion must be present");
        }
        if (!GrcInterchangeBundle.CURRENT_VERSION.equals(bundle.formatVersion())) {
            throw new DomainValidationException("Unsupported bundle formatVersion " + bundle.formatVersion()
                    + "; this server accepts " + GrcInterchangeBundle.CURRENT_VERSION);
        }
    }

    private static void requireExternalUid(String uid, String field) {
        if (uid == null || uid.isBlank()) {
            throw new DomainValidationException(
                    field + " must not be blank", "validation_error", Map.of("field", field));
        }
    }
}
