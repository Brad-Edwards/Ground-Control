package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.AssetLink;
import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetLinkCommand;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetRelationCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetRelationCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkType;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
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
class AssetServiceProjectAwareRelationsTest {
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
    class ProjectAwareRelations {

        @Test
        void createRelationWithProjectIdSucceeds() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            when(assetRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(assetRepository.findByIdAndProjectId(target.getId(), projectId))
                    .thenReturn(Optional.of(target));
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                            source.getId(), target.getId(), AssetRelationType.DEPENDS_ON))
                    .thenReturn(false);
            when(relationRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetRelation.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var result = assetService.createRelation(
                    projectId, source.getId(), target.getId(), AssetRelationType.DEPENDS_ON);

            assertThat(result.getRelationType()).isEqualTo(AssetRelationType.DEPENDS_ON);
        }

        @Test
        void createRelationWithProjectIdSelfReferenceThrows() {
            var id = UUID.randomUUID();

            assertThatThrownBy(() -> assetService.createRelation(projectId, id, id, AssetRelationType.DEPENDS_ON))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("cannot relate to itself");
        }

        @Test
        void createRelationWithProjectIdDuplicateThrowsConflict() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                            source.getId(), target.getId(), AssetRelationType.DEPENDS_ON))
                    .thenReturn(true);

            var sourceId = source.getId();
            var targetId = target.getId();
            assertThatThrownBy(() ->
                            assetService.createRelation(projectId, sourceId, targetId, AssetRelationType.DEPENDS_ON))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void createRelationWithCommandAndProjectIdSucceeds() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            when(assetRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(assetRepository.findByIdAndProjectId(target.getId(), projectId))
                    .thenReturn(Optional.of(target));
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
                    target.getId(), AssetRelationType.DEPENDS_ON, "desc", "SRC", "ext-1", now, "0.8");
            var result = assetService.createRelation(projectId, command, source.getId());

            assertThat(result.getRelationType()).isEqualTo(AssetRelationType.DEPENDS_ON);
            assertThat(result.getDescription()).isEqualTo("desc");
            assertThat(result.getSourceSystem()).isEqualTo("SRC");
            assertThat(result.getExternalSourceId()).isEqualTo("ext-1");
            assertThat(result.getCollectedAt()).isEqualTo(now);
            assertThat(result.getConfidence()).isEqualTo("0.8");
        }

        @Test
        void updateRelationWithProjectIdSucceeds() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());

            when(relationRepository.findByIdWithEntitiesAndProjectId(relation.getId(), projectId))
                    .thenReturn(Optional.of(relation));
            when(relationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var now = Instant.now();
            var command = new UpdateAssetRelationCommand("Updated", "SYS", "ext-id", now, "0.75");
            var result = assetService.updateRelation(projectId, source.getId(), relation.getId(), command);

            assertThat(result.getDescription()).isEqualTo("Updated");
            assertThat(result.getSourceSystem()).isEqualTo("SYS");
            assertThat(result.getExternalSourceId()).isEqualTo("ext-id");
            assertThat(result.getCollectedAt()).isEqualTo(now);
            assertThat(result.getConfidence()).isEqualTo("0.75");
        }

        @Test
        void updateRelationWithProjectIdNotBelongingThrows() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var unrelated = createAsset("ASSET-003", "Unrelated");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());

            when(relationRepository.findByIdWithEntitiesAndProjectId(relation.getId(), projectId))
                    .thenReturn(Optional.of(relation));

            var unrelatedId = unrelated.getId();
            var relationId = relation.getId();
            var command = new UpdateAssetRelationCommand("desc", null, null, null, null);
            assertThatThrownBy(() -> assetService.updateRelation(projectId, unrelatedId, relationId, command))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void getRelationsWithProjectIdReturnsCombinedList() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var outgoing = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(outgoing, "id", UUID.randomUUID());
            var incoming = new AssetRelation(target, source, AssetRelationType.CONTAINS);
            setField(incoming, "id", UUID.randomUUID());

            when(assetRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(relationRepository.findBySourceIdWithEntities(source.getId())).thenReturn(List.of(outgoing));
            when(relationRepository.findByTargetIdWithEntities(source.getId())).thenReturn(List.of(incoming));

            var result = assetService.getRelations(projectId, source.getId());

            assertThat(result).hasSize(2);
        }

        @Test
        void deleteRelationWithProjectIdSucceeds() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());

            when(relationRepository.findByIdWithEntitiesAndProjectId(relation.getId(), projectId))
                    .thenReturn(Optional.of(relation));

            assetService.deleteRelation(projectId, source.getId(), relation.getId());
            verify(relationRepository).delete(relation);
        }

        @Test
        void deleteRelationWithProjectIdNotBelongingThrows() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var unrelated = createAsset("ASSET-003", "Unrelated");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());

            when(relationRepository.findByIdWithEntitiesAndProjectId(relation.getId(), projectId))
                    .thenReturn(Optional.of(relation));

            var unrelatedId = unrelated.getId();
            var relationId = relation.getId();
            assertThatThrownBy(() -> assetService.deleteRelation(projectId, unrelatedId, relationId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }
    }

    @Nested
    class ProjectAwareLinks {

        private AssetLink makeLink(OperationalAsset asset) {
            var link = new AssetLink(asset, AssetLinkTargetType.REQUIREMENT, null, "GC-M010", AssetLinkType.IMPLEMENTS);
            setField(link, "id", UUID.randomUUID());
            setField(link, "createdAt", Instant.now());
            setField(link, "updatedAt", Instant.now());
            return link;
        }

        @Test
        void createLinkWithProjectIdSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(graphTargetResolverService.validateAssetTarget(
                            projectId, AssetLinkTargetType.REQUIREMENT, null, "GC-M010"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(null, "GC-M010", false));
            when(linkRepository.existsByAssetIdAndTargetTypeAndTargetIdentifierAndLinkType(
                            asset.getId(), AssetLinkTargetType.REQUIREMENT, "GC-M010", AssetLinkType.IMPLEMENTS))
                    .thenReturn(false);
            when(linkRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetLink.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var command = new CreateAssetLinkCommand(
                    AssetLinkTargetType.REQUIREMENT, null, "GC-M010", AssetLinkType.IMPLEMENTS, null, null);
            var result = assetService.createLink(projectId, asset.getId(), command);

            assertThat(result.getTargetType()).isEqualTo(AssetLinkTargetType.REQUIREMENT);
            assertThat(result.getTargetIdentifier()).isEqualTo("GC-M010");
        }

        @Test
        void createLinkWithProjectIdInternalTargetSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            var targetEntityId = UUID.randomUUID();
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(graphTargetResolverService.validateAssetTarget(
                            projectId, AssetLinkTargetType.REQUIREMENT, targetEntityId, "GC-M010"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(targetEntityId, "GC-M010", true));
            when(linkRepository.existsByAssetIdAndTargetTypeAndTargetEntityIdAndLinkType(
                            asset.getId(), AssetLinkTargetType.REQUIREMENT, targetEntityId, AssetLinkType.IMPLEMENTS))
                    .thenReturn(false);
            when(linkRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetLink.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var command = new CreateAssetLinkCommand(
                    AssetLinkTargetType.REQUIREMENT, targetEntityId, "GC-M010", AssetLinkType.IMPLEMENTS, null, null);
            var result = assetService.createLink(projectId, asset.getId(), command);

            assertThat(result.getTargetType()).isEqualTo(AssetLinkTargetType.REQUIREMENT);
        }

        @Test
        void createLinkWithProjectIdDuplicateInternalTargetThrowsConflict() {
            var asset = createAsset("ASSET-001", "Web Server");
            var targetEntityId = UUID.randomUUID();
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(graphTargetResolverService.validateAssetTarget(
                            projectId, AssetLinkTargetType.REQUIREMENT, targetEntityId, "GC-M010"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(targetEntityId, "GC-M010", true));
            when(linkRepository.existsByAssetIdAndTargetTypeAndTargetEntityIdAndLinkType(
                            asset.getId(), AssetLinkTargetType.REQUIREMENT, targetEntityId, AssetLinkType.IMPLEMENTS))
                    .thenReturn(true);

            var command = new CreateAssetLinkCommand(
                    AssetLinkTargetType.REQUIREMENT, targetEntityId, "GC-M010", AssetLinkType.IMPLEMENTS, null, null);

            var assetId = asset.getId();
            assertThatThrownBy(() -> assetService.createLink(projectId, assetId, command))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void createLinkWithProjectIdSetsOptionalFields() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(graphTargetResolverService.validateAssetTarget(
                            projectId, AssetLinkTargetType.EXTERNAL, null, "ext-123"))
                    .thenReturn(new GraphTargetResolverService.ValidatedTarget(null, "ext-123", false));
            when(linkRepository.existsByAssetIdAndTargetTypeAndTargetIdentifierAndLinkType(
                            asset.getId(), AssetLinkTargetType.EXTERNAL, "ext-123", AssetLinkType.DEPENDS_ON))
                    .thenReturn(false);
            when(linkRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetLink.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var command = new CreateAssetLinkCommand(
                    AssetLinkTargetType.EXTERNAL,
                    null,
                    "ext-123",
                    AssetLinkType.DEPENDS_ON,
                    "https://example.com",
                    "Example");
            var result = assetService.createLink(projectId, asset.getId(), command);

            assertThat(result.getTargetUrl()).isEqualTo("https://example.com");
            assertThat(result.getTargetTitle()).isEqualTo("Example");
        }

        @Test
        void getLinksForAssetWithProjectIdReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(linkRepository.findByAssetId(asset.getId())).thenReturn(List.of(makeLink(asset)));

            var result = assetService.getLinksForAsset(projectId, asset.getId());

            assertThat(result).hasSize(1);
        }

        @Test
        void getLinksForAssetByTargetTypeReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(linkRepository.findByAssetIdAndTargetType(asset.getId(), AssetLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of(makeLink(asset)));

            var result = assetService.getLinksForAssetByTargetType(
                    projectId, asset.getId(), AssetLinkTargetType.REQUIREMENT);

            assertThat(result).hasSize(1);
        }

        @Test
        void getLinksForAssetByTargetTypeLegacyReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(linkRepository.findByAssetIdAndTargetType(asset.getId(), AssetLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of(makeLink(asset)));

            var result = assetService.getLinksForAssetByTargetType(asset.getId(), AssetLinkTargetType.REQUIREMENT);

            assertThat(result).hasSize(1);
        }

        @Test
        void getLinksByTargetWithEntityIdReturnsList() {
            var asset = createAsset("ASSET-001", "Web Server");
            var targetEntityId = UUID.randomUUID();
            when(linkRepository.findByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.REQUIREMENT, targetEntityId, projectId))
                    .thenReturn(List.of(makeLink(asset)));

            var result = assetService.getLinksByTarget(
                    projectId, AssetLinkTargetType.REQUIREMENT, targetEntityId, "GC-M010");

            assertThat(result).hasSize(1);
        }

        @Test
        void deleteLinkWithProjectIdSucceeds() {
            var asset = createAsset("ASSET-001", "Web Server");
            var link = makeLink(asset);
            when(linkRepository.findByIdWithAssetAndProjectId(link.getId(), projectId))
                    .thenReturn(Optional.of(link));

            assetService.deleteLink(projectId, asset.getId(), link.getId());
            verify(linkRepository).delete(link);
        }

        @Test
        void deleteLinkWithProjectIdNotBelongingThrows() {
            var asset = createAsset("ASSET-001", "Web Server");
            var other = createAsset("ASSET-002", "Other");
            var link = makeLink(asset);
            when(linkRepository.findByIdWithAssetAndProjectId(link.getId(), projectId))
                    .thenReturn(Optional.of(link));

            var otherId = other.getId();
            var linkId = link.getId();
            assertThatThrownBy(() -> assetService.deleteLink(projectId, otherId, linkId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void deleteLinkWithProjectIdNotFoundThrows() {
            var linkId = UUID.randomUUID();
            when(linkRepository.findByIdWithAssetAndProjectId(linkId, projectId))
                    .thenReturn(Optional.empty());

            var unknownId = UUID.randomUUID();
            assertThatThrownBy(() -> assetService.deleteLink(projectId, unknownId, linkId))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
