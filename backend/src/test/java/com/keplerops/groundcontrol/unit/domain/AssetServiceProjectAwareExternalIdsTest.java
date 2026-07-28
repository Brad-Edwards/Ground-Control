package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.AssetExternalId;
import com.keplerops.groundcontrol.domain.assets.model.AssetSubtypeSchema;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetExternalIdCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetExternalIdCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetSubtypeSchemaStatus;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from AssetServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class AssetServiceProjectAwareExternalIdsTest {
    @Mock
    private OperationalAssetRepository assetRepository;

    @Mock
    private AssetRelationRepository relationRepository;

    @Mock
    private AssetLinkRepository linkRepository;

    @Mock
    private AssetExternalIdRepository externalIdRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository findingLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository auditLinkRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private GraphTargetResolverService graphTargetResolverService;

    @Mock
    private AssetSubtypeSchemaRepository subtypeSchemaRepository;

    @org.mockito.Spy
    @SuppressWarnings("UnusedVariable") // Injected into AssetService via @InjectMocks; errorprone misses the wire.
    private AssetSubtypeValidator subtypeValidator = new AssetSubtypeValidator();

    @InjectMocks
    private AssetService assetService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private OperationalAsset createAsset(String uid, String name) {
        var asset = new OperationalAsset(project, uid, name);
        setField(asset, "id", UUID.randomUUID());
        return asset;
    }

    @Nested
    class ProjectAwareExternalIds {

        @Test
        void createExternalIdWithProjectIdSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(externalIdRepository.existsByAssetIdAndSourceSystemAndSourceId(asset.getId(), "AWS", "i-abc"))
                    .thenReturn(false);
            when(externalIdRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetExternalId.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var now = Instant.now();
            var command = new CreateAssetExternalIdCommand("AWS", "i-abc", now, "HIGH");
            var result = assetService.createExternalId(projectId, asset.getId(), command);

            assertThat(result.getSourceSystem()).isEqualTo("AWS");
            assertThat(result.getSourceId()).isEqualTo("i-abc");
            assertThat(result.getCollectedAt()).isEqualTo(now);
            assertThat(result.getConfidence()).isEqualTo("HIGH");
        }

        @Test
        void createExternalIdWithProjectIdDuplicateThrowsConflict() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(externalIdRepository.existsByAssetIdAndSourceSystemAndSourceId(asset.getId(), "AWS", "i-abc"))
                    .thenReturn(true);

            var command = new CreateAssetExternalIdCommand("AWS", "i-abc", null, null);

            var assetId = asset.getId();
            assertThatThrownBy(() -> assetService.createExternalId(projectId, assetId, command))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void updateExternalIdWithProjectIdSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "AWS", "i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(externalIdRepository.findByIdWithAssetAndProjectId(extId.getId(), projectId))
                    .thenReturn(Optional.of(extId));
            when(externalIdRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var now = Instant.now();
            var command = new UpdateAssetExternalIdCommand(now, "LOW");
            var result = assetService.updateExternalId(projectId, asset.getId(), extId.getId(), command);

            assertThat(result.getCollectedAt()).isEqualTo(now);
            assertThat(result.getConfidence()).isEqualTo("LOW");
        }

        @Test
        void updateExternalIdWithProjectIdNotBelongingThrows() {
            var asset = createAsset("ASSET-001", "Web Server");
            var other = createAsset("ASSET-002", "Other");
            var extId = new AssetExternalId(asset, "AWS", "i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(externalIdRepository.findByIdWithAssetAndProjectId(extId.getId(), projectId))
                    .thenReturn(Optional.of(extId));

            var otherId = other.getId();
            var extIdId = extId.getId();
            var command = new UpdateAssetExternalIdCommand(null, "LOW");
            assertThatThrownBy(() -> assetService.updateExternalId(projectId, otherId, extIdId, command))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void getExternalIdsWithProjectIdReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "AWS", "i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(externalIdRepository.findByAssetId(asset.getId())).thenReturn(List.of(extId));

            var result = assetService.getExternalIds(projectId, asset.getId());

            assertThat(result).hasSize(1);
        }

        @Test
        void getExternalIdsBySourceWithProjectIdReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "AWS", "i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(externalIdRepository.findByAssetIdAndSourceSystem(asset.getId(), "AWS"))
                    .thenReturn(List.of(extId));

            var result = assetService.getExternalIdsBySource(projectId, asset.getId(), "AWS");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSourceSystem()).isEqualTo("AWS");
        }

        @Test
        void getExternalIdsBySourceLegacyReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "GCP", "proj/inst");
            setField(extId, "id", UUID.randomUUID());

            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(externalIdRepository.findByAssetIdAndSourceSystem(asset.getId(), "GCP"))
                    .thenReturn(List.of(extId));

            var result = assetService.getExternalIdsBySource(asset.getId(), "GCP");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSourceSystem()).isEqualTo("GCP");
        }

        @Test
        void findByExternalIdReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "AWS", "i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(externalIdRepository.findBySourceSystemAndSourceIdAndProjectId("AWS", "i-abc", projectId))
                    .thenReturn(List.of(extId));

            var result = assetService.findByExternalId(projectId, "AWS", "i-abc");

            assertThat(result).hasSize(1);
        }

        @Test
        void deleteExternalIdWithProjectIdSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "AWS", "i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(externalIdRepository.findByIdWithAssetAndProjectId(extId.getId(), projectId))
                    .thenReturn(Optional.of(extId));

            assetService.deleteExternalId(projectId, asset.getId(), extId.getId());
            verify(externalIdRepository).delete(extId);
        }

        @Test
        void deleteExternalIdWithProjectIdNotBelongingThrows() {
            var asset = createAsset("ASSET-001", "Web Server");
            var other = createAsset("ASSET-002", "Other");
            var extId = new AssetExternalId(asset, "AWS", "i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(externalIdRepository.findByIdWithAssetAndProjectId(extId.getId(), projectId))
                    .thenReturn(Optional.of(extId));

            var otherId = other.getId();
            var extIdId = extId.getId();
            assertThatThrownBy(() -> assetService.deleteExternalId(projectId, otherId, extIdId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void deleteExternalIdWithProjectIdNotFoundThrows() {
            var extIdId = UUID.randomUUID();
            when(externalIdRepository.findByIdWithAssetAndProjectId(extIdId, projectId))
                    .thenReturn(Optional.empty());

            var unknownId = UUID.randomUUID();
            assertThatThrownBy(() -> assetService.deleteExternalId(projectId, unknownId, extIdId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class SubtypeAndMetadata {

        @Test
        void createCarriesSubtypeAndMetadata() {
            var metadata = new java.util.LinkedHashMap<String, Object>();
            metadata.put("cloud_account_id", "123456");
            metadata.put("region", "us-west-2");
            var command = new CreateAssetCommand(
                    projectId,
                    "ASSET-101",
                    "EC2 worker",
                    null,
                    AssetType.WORKLOAD,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "aws_ec2",
                    metadata);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-101"))
                    .thenReturn(false);
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.WORKLOAD, "aws_ec2", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(assetRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, OperationalAsset.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var result = assetService.create(command);

            assertThat(result.getSubtype()).isEqualTo("aws_ec2");
            assertThat(result.getMetadata()).containsEntry("cloud_account_id", "123456");
        }

        @Test
        void createRejectsMetadataExceedingBounds() {
            var metadata = new java.util.LinkedHashMap<String, Object>();
            for (int i = 0; i < AssetSubtypeValidator.MAX_METADATA_KEYS + 1; i++) {
                metadata.put("k" + i, "v");
            }
            var command = new CreateAssetCommand(
                    projectId,
                    "ASSET-102",
                    "Asset",
                    null,
                    AssetType.SERVICE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    metadata);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-102"))
                    .thenReturn(false);

            assertThatThrownBy(() -> assetService.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("too_many_keys");
        }

        @Test
        void createEnforcesActiveSchema() {
            Map<String, Object> schemaBody = Map.of(
                    "fields",
                    Map.of(
                            "cloud_account_id",
                            Map.of("type", "STRING", "required", true, "maxLength", 50),
                            "region",
                            Map.of("type", "STRING", "required", true)));
            var schema = new AssetSubtypeSchema(project, AssetType.WORKLOAD, "aws_ec2", "v1", schemaBody);
            setField(schema, "id", UUID.randomUUID());

            // Missing required "region" must be rejected.
            var command = new CreateAssetCommand(
                    projectId,
                    "ASSET-103",
                    "EC2 worker",
                    null,
                    AssetType.WORKLOAD,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "aws_ec2",
                    Map.of("cloud_account_id", "123"));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-103"))
                    .thenReturn(false);
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.WORKLOAD, "aws_ec2", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.of(schema));

            assertThatThrownBy(() -> assetService.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("required_field_missing");
        }

        @Test
        void createRejectsOversizedSubtypeAtServiceBoundary() {
            // Codex cycle-4 finding 1: bounded asset string fields must be
            // enforced at the service layer so non-controller callers can't
            // trip a 500 from a VARCHAR overflow.
            String oversize = "x".repeat(101);
            var command = new CreateAssetCommand(
                    projectId,
                    "ASSET-106",
                    "Asset",
                    null,
                    AssetType.SERVICE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    oversize,
                    null);

            assertThatThrownBy(() -> assetService.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getErrorCode())
                    .isEqualTo("asset_field_invalid");
        }

        @Test
        void createRejectsBlankSubtype() {
            // Codex over-cap finding 4: blank/whitespace subtype creates a
            // second invalid namespace that can never match a registered
            // schema. The schema registry rejects blank subtype keys; assets
            // must use the same rule.
            var command = new CreateAssetCommand(
                    projectId,
                    "ASSET-105",
                    "Asset",
                    null,
                    AssetType.SERVICE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "   ",
                    null);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-105"))
                    .thenReturn(false);

            assertThatThrownBy(() -> assetService.create(command))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getErrorCode())
                    .isEqualTo("asset_subtype_invalid");
        }

        @Test
        void updateClearsSubtypeAndMetadataWhenClearFlagsSet() {
            var asset = createAsset("ASSET-104", "Endpoint");
            asset.setSubtype("user_account");
            asset.setMetadata(Map.of("user_id", "u-1"));
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetCommand(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    /* clearSubtype */ true,
                    /* clearMetadata */ true);

            var result = assetService.update(projectId, asset.getId(), command);

            assertThat(result.getSubtype()).isNull();
            assertThat(result.getMetadata()).isNull();
        }
    }
}
