package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetRelationCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetCriticality;
import com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetScope;
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
class AssetServiceListByFiltersTest {
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
    class ListByFilters {

        @Test
        void filtersByOwnershipCriticalityScopeMetadata() {
            // GC-M012 queryability: the filter surface routes through the
            // repository's single JPQL query so risk/control/audit/reporting
            // callers don't have to invent per-workflow lookups.
            var match = createAsset("ASSET-IN-SCOPE", "Payments API");
            when(assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                            projectId,
                            AssetType.SERVICE,
                            "alice@example.com",
                            "platform-sre",
                            AssetEnvironment.PRODUCTION,
                            AssetCriticality.CRITICAL,
                            AssetScope.IN_SCOPE,
                            null,
                            null))
                    .thenReturn(List.of(match));

            var results = assetService.listByProjectAndFilters(
                    projectId,
                    AssetType.SERVICE,
                    "alice@example.com",
                    "platform-sre",
                    AssetEnvironment.PRODUCTION,
                    AssetCriticality.CRITICAL,
                    AssetScope.IN_SCOPE,
                    null,
                    null);

            assertThat(results).containsExactly(match);
        }

        @Test
        void filtersAllNullDelegatesToProjectQuery() {
            // No filters supplied = same shape as listByProject so the
            // controller can fall through cleanly when no filter param hits.
            var match = createAsset("ASSET-A", "A");
            when(assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                            projectId, null, null, null, null, null, null, null, null))
                    .thenReturn(List.of(match));

            var results =
                    assetService.listByProjectAndFilters(projectId, null, null, null, null, null, null, null, null);

            assertThat(results).containsExactly(match);
        }

        @Test
        void filtersBySubtype() {
            // GC-M011: subtype is a queryable facet on the same single-query
            // surface, so callers can list "all aws_ec2 workloads" without a
            // project-wide scan.
            var match = createAsset("ASSET-101", "EC2 worker");
            when(assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                            projectId, AssetType.WORKLOAD, null, null, null, null, null, "aws_ec2", null))
                    .thenReturn(List.of(match));

            var results = assetService.listByProjectAndFilters(
                    projectId, AssetType.WORKLOAD, null, null, null, null, null, "aws_ec2", null);

            assertThat(results).containsExactly(match);
        }

        @Test
        void filtersByKnowledgeState() {
            // GC-M018: knowledge-state filter rides the same single-query
            // surface. Risk / threat / control workflows that consume
            // "only confirmed model facts" pass CONFIRMED and the
            // provisional / unknown rows fall out of the response.
            var match = createAsset("ASSET-CONFIRMED", "Confirmed Inventory");
            when(assetRepository.findByProjectIdAndArchivedAtIsNullAndFilters(
                            projectId,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.CONFIRMED))
                    .thenReturn(List.of(match));

            var results = assetService.listByProjectAndFilters(
                    projectId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.CONFIRMED);

            assertThat(results).containsExactly(match);
        }
    }

    @Nested
    class Delete {

        @Test
        void deleteSucceeds() {
            var asset = createAsset("ASSET-001", "Test");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.ASSET,
                            asset.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(linkRepository.findByAssetId(asset.getId())).thenReturn(java.util.List.of());

            assetService.delete(asset.getId());
            verify(assetRepository).delete(asset);
        }

        @Test
        void deletesOutboundLinksThroughRepositoryBeforeParent() {
            var asset = createAsset("ASSET-001", "Test");
            var assetId = asset.getId();
            var outboundLinks = java.util.List.of(new com.keplerops.groundcontrol.domain.assets.model.AssetLink(
                    asset,
                    com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType.CONTROL,
                    UUID.randomUUID(),
                    null,
                    com.keplerops.groundcontrol.domain.assets.state.AssetLinkType.GOVERNED_BY));
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.ASSET,
                            assetId,
                            projectId))
                    .thenReturn(java.util.List.of());
            when(linkRepository.findByAssetId(assetId)).thenReturn(outboundLinks);

            assetService.delete(projectId, assetId);

            // Envers writes delete revisions only when Hibernate sees the link
            // delete. Driving outbound link deletes through the repository before
            // deleting the parent closes the parent-delete audit-history gap
            // (cycle-2 pre-push codex review on issue #279).
            var inOrder = org.mockito.Mockito.inOrder(linkRepository, assetRepository);
            inOrder.verify(linkRepository).deleteAll(outboundLinks);
            inOrder.verify(assetRepository).delete(asset);
        }

        @Test
        void rejectsDeleteWhenInboundAuditLinkReferencesAsset() {
            var asset = createAsset("ASSET-002", "Test");
            var assetId = asset.getId();
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.ASSET,
                            assetId,
                            projectId))
                    .thenReturn(java.util.List.of());
            when(auditLinkRepository.findAuditUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType.ASSET,
                            assetId,
                            projectId))
                    .thenReturn(java.util.List.of("AUDIT-001"));

            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    com.keplerops.groundcontrol.domain.exception.ConflictException.class,
                    () -> assetService.delete(projectId, assetId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("AuditLink references exist")
                    .extracting("errorCode")
                    .isEqualTo("asset_referenced");
            assertThat(thrown.getDetail()).containsEntry("auditCount", 1);
            org.mockito.Mockito.verifyNoInteractions(linkRepository);
            verify(assetRepository, never()).delete(asset);
        }

        @Test
        void rejectsDeleteWhenInboundFindingLinkReferencesAsset() {
            var asset = createAsset("ASSET-001", "Test");
            var assetId = asset.getId();
            when(assetRepository.findByIdAndProjectId(assetId, projectId)).thenReturn(Optional.of(asset));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.ASSET,
                            assetId,
                            projectId))
                    .thenReturn(java.util.List.of("FIND-001"));

            // FindingLink.targetEntityId is not an FK, so without this guard the
            // delete would leave dangling FindingLink rows (cycle-3 pre-push codex
            // review on issue #279, ADR-038).
            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    com.keplerops.groundcontrol.domain.exception.ConflictException.class,
                    () -> assetService.delete(projectId, assetId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("FindingLink references exist")
                    .extracting("errorCode")
                    .isEqualTo("asset_referenced");
            assertThat(thrown.getDetail()).containsEntry("findingCount", 1);
            // Parent + outbound-link cleanup must be skipped when the guard fires.
            org.mockito.Mockito.verifyNoInteractions(linkRepository);
            verify(assetRepository, never()).delete(asset);
        }
    }

    @Nested
    class Relations {

        @Test
        void createRelationSucceeds() {
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

            var result = assetService.createRelation(source.getId(), target.getId(), AssetRelationType.DEPENDS_ON);

            assertThat(result.getRelationType()).isEqualTo(AssetRelationType.DEPENDS_ON);
        }

        @Test
        void updateRelationSucceeds() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());

            when(relationRepository.findByIdWithEntities(relation.getId())).thenReturn(Optional.of(relation));
            when(relationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var now = Instant.now();
            var command = new UpdateAssetRelationCommand("Updated description", "CMDB", "cmdb-123", now, "0.95");
            var result = assetService.updateRelation(source.getId(), relation.getId(), command);

            assertThat(result.getDescription()).isEqualTo("Updated description");
            assertThat(result.getSourceSystem()).isEqualTo("CMDB");
            assertThat(result.getExternalSourceId()).isEqualTo("cmdb-123");
            assertThat(result.getCollectedAt()).isEqualTo(now);
            assertThat(result.getConfidence()).isEqualTo("0.95");
            assertThat(result.getRelationType()).isEqualTo(AssetRelationType.DEPENDS_ON);
            assertThat(result.getSource()).isSameAs(source);
            assertThat(result.getTarget()).isSameAs(target);
        }

        @Test
        void createRelationSelfReferenceThrows() {
            var id = UUID.randomUUID();

            assertThatThrownBy(() -> assetService.createRelation(id, id, AssetRelationType.DEPENDS_ON))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("cannot relate to itself");
        }

        @Test
        void createRelationDuplicateThrowsConflict() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                            source.getId(), target.getId(), AssetRelationType.DEPENDS_ON))
                    .thenReturn(true);

            assertThatThrownBy(() ->
                            assetService.createRelation(source.getId(), target.getId(), AssetRelationType.DEPENDS_ON))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void createRelationCrossProjectThrows() {
            var otherProject = new Project("other", "Other");
            var otherProjectId = UUID.randomUUID();
            setField(otherProject, "id", otherProjectId);

            var source = createAsset("ASSET-001", "Source");
            var target = new OperationalAsset(otherProject, "ASSET-002", "Target");
            setField(target, "id", UUID.randomUUID());

            when(assetRepository.findById(source.getId())).thenReturn(Optional.of(source));
            when(assetRepository.findById(target.getId())).thenReturn(Optional.of(target));
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(
                            source.getId(), target.getId(), AssetRelationType.DEPENDS_ON))
                    .thenReturn(false);

            assertThatThrownBy(() ->
                            assetService.createRelation(source.getId(), target.getId(), AssetRelationType.DEPENDS_ON))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("different projects");
        }

        @Test
        void deleteRelationSucceeds() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());

            when(relationRepository.findByIdWithEntities(relation.getId())).thenReturn(Optional.of(relation));

            assetService.deleteRelation(source.getId(), relation.getId());
            verify(relationRepository).delete(relation);
        }

        @Test
        void deleteRelationNotBelongingThrows() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var unrelated = createAsset("ASSET-003", "Unrelated");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());

            when(relationRepository.findByIdWithEntities(relation.getId())).thenReturn(Optional.of(relation));

            assertThatThrownBy(() -> assetService.deleteRelation(unrelated.getId(), relation.getId()))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void updateRelationNotBelongingThrows() {
            var source = createAsset("ASSET-001", "Source");
            var target = createAsset("ASSET-002", "Target");
            var unrelated = createAsset("ASSET-003", "Unrelated");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());

            when(relationRepository.findByIdWithEntities(relation.getId())).thenReturn(Optional.of(relation));

            assertThatThrownBy(() -> assetService.updateRelation(
                            unrelated.getId(),
                            relation.getId(),
                            new UpdateAssetRelationCommand("desc", null, null, null, null)))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }
    }
}
