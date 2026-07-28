package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.AssetExternalId;
import com.keplerops.groundcontrol.domain.assets.model.AssetLink;
import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetExternalIdCommand;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetLinkCommand;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetRelationCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetExternalIdCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkType;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import java.time.Instant;
import java.util.List;
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
class AssetServiceLinksTest {
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
    class Links {

        private AssetLink makeLink(OperationalAsset asset) {
            var link = new AssetLink(asset, AssetLinkTargetType.REQUIREMENT, null, "GC-M010", AssetLinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            setField(link, "createdAt", Instant.now());
            setField(link, "updatedAt", Instant.now());
            return link;
        }

        @Test
        void createLinkSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(linkRepository.existsByAssetIdAndTargetTypeAndTargetIdentifierAndLinkType(
                            asset.getId(), AssetLinkTargetType.REQUIREMENT, "GC-M010", AssetLinkType.IMPLEMENTS))
                    .thenReturn(false);
            when(graphTargetResolverService.validateAssetTarget(
                            projectId, AssetLinkTargetType.REQUIREMENT, null, "GC-M010"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(null, "GC-M010", false));
            when(linkRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetLink.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var command = new CreateAssetLinkCommand(
                    AssetLinkTargetType.REQUIREMENT, null, "GC-M010", AssetLinkType.IMPLEMENTS, null, null);
            var result = assetService.createLink(asset.getId(), command);

            assertThat(result.getTargetType()).isEqualTo(AssetLinkTargetType.REQUIREMENT);
            assertThat(result.getTargetIdentifier()).isEqualTo("GC-M010");
            assertThat(result.getLinkType()).isEqualTo(AssetLinkType.IMPLEMENTS);
        }

        @Test
        void createLinkWithOptionalFields() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(linkRepository.existsByAssetIdAndTargetTypeAndTargetIdentifierAndLinkType(
                            asset.getId(), AssetLinkTargetType.EXTERNAL, "jira-123", AssetLinkType.DEPENDS_ON))
                    .thenReturn(false);
            when(graphTargetResolverService.validateAssetTarget(
                            projectId, AssetLinkTargetType.EXTERNAL, null, "jira-123"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(null, "jira-123", false));
            when(linkRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetLink.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var command = new CreateAssetLinkCommand(
                    AssetLinkTargetType.EXTERNAL,
                    null,
                    "jira-123",
                    AssetLinkType.DEPENDS_ON,
                    "https://jira.example.com/123",
                    "External Dependency");
            var result = assetService.createLink(asset.getId(), command);

            assertThat(result.getTargetUrl()).isEqualTo("https://jira.example.com/123");
            assertThat(result.getTargetTitle()).isEqualTo("External Dependency");
        }

        @Test
        void createLinkDuplicateThrowsConflict() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(linkRepository.existsByAssetIdAndTargetTypeAndTargetIdentifierAndLinkType(
                            asset.getId(), AssetLinkTargetType.REQUIREMENT, "GC-M010", AssetLinkType.IMPLEMENTS))
                    .thenReturn(true);
            when(graphTargetResolverService.validateAssetTarget(
                            projectId, AssetLinkTargetType.REQUIREMENT, null, "GC-M010"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(null, "GC-M010", false));

            var command = new CreateAssetLinkCommand(
                    AssetLinkTargetType.REQUIREMENT, null, "GC-M010", AssetLinkType.IMPLEMENTS, null, null);

            assertThatThrownBy(() -> assetService.createLink(asset.getId(), command))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void createLinkAssetNotFoundThrows() {
            var id = UUID.randomUUID();
            when(assetRepository.findById(id)).thenReturn(Optional.empty());

            var command = new CreateAssetLinkCommand(
                    AssetLinkTargetType.REQUIREMENT, null, "GC-M010", AssetLinkType.IMPLEMENTS, null, null);

            assertThatThrownBy(() -> assetService.createLink(id, command)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void getLinksForAssetReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(linkRepository.findByAssetId(asset.getId())).thenReturn(List.of(makeLink(asset)));

            var result = assetService.getLinksForAsset(asset.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTargetIdentifier()).isEqualTo("GC-M010");
        }

        @Test
        void deleteLinkSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            var link = makeLink(asset);
            when(linkRepository.findById(link.getId())).thenReturn(Optional.of(link));

            assetService.deleteLink(asset.getId(), link.getId());
            verify(linkRepository).delete(link);
        }

        @Test
        void deleteLinkNotBelongingThrows() {
            var asset = createAsset("ASSET-001", "Web Server");
            var other = createAsset("ASSET-002", "Other");
            var link = makeLink(asset);
            when(linkRepository.findById(link.getId())).thenReturn(Optional.of(link));

            assertThatThrownBy(() -> assetService.deleteLink(other.getId(), link.getId()))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void getLinksByTargetReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(linkRepository.findByTargetTypeAndTargetIdentifierAndProjectId(
                            AssetLinkTargetType.REQUIREMENT, "GC-M010", projectId))
                    .thenReturn(List.of(makeLink(asset)));

            var result = assetService.getLinksByTarget(projectId, AssetLinkTargetType.REQUIREMENT, null, "GC-M010");

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    class ExternalIds {

        @Test
        void createExternalIdSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(externalIdRepository.existsByAssetIdAndSourceSystemAndSourceId(
                            asset.getId(), "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc"))
                    .thenReturn(false);
            when(externalIdRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetExternalId.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var command = new CreateAssetExternalIdCommand(
                    "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc", Instant.now(), "HIGH");
            var result = assetService.createExternalId(asset.getId(), command);

            assertThat(result.getSourceSystem()).isEqualTo("AWS");
            assertThat(result.getSourceId()).isEqualTo("arn:aws:ec2:us-east-1:123:instance/i-abc");
            assertThat(result.getConfidence()).isEqualTo("HIGH");
        }

        @Test
        void createExternalIdDuplicateThrowsConflict() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(externalIdRepository.existsByAssetIdAndSourceSystemAndSourceId(
                            asset.getId(), "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc"))
                    .thenReturn(true);

            var command =
                    new CreateAssetExternalIdCommand("AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc", null, null);

            assertThatThrownBy(() -> assetService.createExternalId(asset.getId(), command))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void updateExternalIdSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(externalIdRepository.findByIdWithAsset(extId.getId())).thenReturn(Optional.of(extId));
            when(externalIdRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var now = Instant.now();
            var command = new UpdateAssetExternalIdCommand(now, "MEDIUM");
            var result = assetService.updateExternalId(asset.getId(), extId.getId(), command);

            assertThat(result.getCollectedAt()).isEqualTo(now);
            assertThat(result.getConfidence()).isEqualTo("MEDIUM");
        }

        @Test
        void deleteExternalIdNotBelongingThrows() {
            var asset = createAsset("ASSET-001", "Web Server");
            var other = createAsset("ASSET-002", "Other");
            var extId = new AssetExternalId(asset, "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(externalIdRepository.findByIdWithAsset(extId.getId())).thenReturn(Optional.of(extId));

            assertThatThrownBy(() -> assetService.deleteExternalId(other.getId(), extId.getId()))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void getExternalIdsReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(externalIdRepository.findByAssetId(asset.getId())).thenReturn(List.of(extId));

            var result = assetService.getExternalIds(asset.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSourceSystem()).isEqualTo("AWS");
        }

        @Test
        void deleteExternalIdSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            var extId = new AssetExternalId(asset, "AWS", "arn:aws:ec2:us-east-1:123:instance/i-abc");
            setField(extId, "id", UUID.randomUUID());

            when(externalIdRepository.findByIdWithAsset(extId.getId())).thenReturn(Optional.of(extId));

            assetService.deleteExternalId(asset.getId(), extId.getId());
            verify(externalIdRepository).delete(extId);
        }
    }

    @Nested
    class RelationProvenance {

        @Test
        void createRelationWithProvenanceSucceeds() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            when(assetRepository.findById(source.getId())).thenReturn(Optional.of(source));
            when(assetRepository.findById(target.getId())).thenReturn(Optional.of(target));
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                            source.getId(), target.getId(), AssetRelationType.DEPENDS_ON))
                    .thenReturn(false);
            when(relationRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetRelation.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var now = Instant.now();
            var command = new CreateAssetRelationCommand(
                    target.getId(),
                    AssetRelationType.DEPENDS_ON,
                    "Observed dependency",
                    "AWS_CONFIG",
                    "config-rule-123",
                    now,
                    "0.95");
            var result = assetService.createRelation(command, source.getId());

            assertThat(result.getRelationType()).isEqualTo(AssetRelationType.DEPENDS_ON);
            assertThat(result.getDescription()).isEqualTo("Observed dependency");
            assertThat(result.getSourceSystem()).isEqualTo("AWS_CONFIG");
            assertThat(result.getExternalSourceId()).isEqualTo("config-rule-123");
            assertThat(result.getCollectedAt()).isEqualTo(now);
            assertThat(result.getConfidence()).isEqualTo("0.95");
        }
    }

    @Nested
    class ProjectAwareUpdate {

        @Test
        void updateWithProjectIdSucceeds() {
            var asset = createAsset("ASSET-001", "Old Name");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetCommand("New Name", "New desc", AssetType.DATABASE);
            var result = assetService.update(projectId, asset.getId(), command);

            assertThat(result.getName()).isEqualTo("New Name");
            assertThat(result.getDescription()).isEqualTo("New desc");
            assertThat(result.getAssetType()).isEqualTo(AssetType.DATABASE);
        }

        @Test
        void updateWithProjectIdNotFoundThrows() {
            var id = UUID.randomUUID();
            when(assetRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            var command = new UpdateAssetCommand("New Name", null, null);

            assertThatThrownBy(() -> assetService.update(projectId, id, command))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ProjectAwareRead {

        @Test
        void getByIdWithProjectIdReturnsAsset() {
            var asset = createAsset("ASSET-001", "Test");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));

            var result = assetService.getById(projectId, asset.getId());
            assertThat(result.getUid()).isEqualTo("ASSET-001");
        }

        @Test
        void getByIdWithProjectIdNotFoundThrows() {
            var id = UUID.randomUUID();
            when(assetRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> assetService.getById(projectId, id)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void listByProjectAndTypeReturnsList() {
            var a1 = createAsset("ASSET-001", "DB One");
            a1.setAssetType(AssetType.DATABASE);
            when(assetRepository.findByProjectIdAndAssetTypeAndArchivedAtIsNull(projectId, AssetType.DATABASE))
                    .thenReturn(List.of(a1));

            var result = assetService.listByProjectAndType(projectId, AssetType.DATABASE);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAssetType()).isEqualTo(AssetType.DATABASE);
        }
    }

    @Nested
    class ProjectAwareArchive {

        @Test
        void archiveWithProjectIdSetsArchivedAt() {
            var asset = createAsset("ASSET-001", "Test");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = assetService.archive(projectId, asset.getId());
            assertThat(result.getArchivedAt()).isNotNull();
        }
    }

    @Nested
    class ProjectAwareDelete {

        @Test
        void deleteWithProjectIdSucceeds() {
            var asset = createAsset("ASSET-001", "Test");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            // Explicit stubs for the inbound-finding-link guard and the
            // outbound-link sweep — without them the test relied on Mockito
            // defaults and could pass even if a refactor accidentally
            // removed the guard from the project-aware path (test-quality
            // review finding on #722).
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.ASSET,
                            asset.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(linkRepository.findByAssetId(asset.getId())).thenReturn(java.util.List.of());

            assetService.delete(projectId, asset.getId());

            verify(findingLinkRepository)
                    .findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.ASSET,
                            asset.getId(),
                            projectId);
            verify(assetRepository).delete(asset);
        }
    }
}
