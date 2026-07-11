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
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetLinkCommand;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetRelationCommand;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetSubtypeSchemaCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetExternalIdCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetRelationCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetSubtypeSchemaCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetCriticality;
import com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkType;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetScope;
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

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

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
    class Create {

        @Test
        void createSucceeds() {
            var command =
                    new CreateAssetCommand(projectId, "ASSET-001", "Web Server", "A web server", AssetType.SERVICE);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-001"))
                    .thenReturn(false);
            when(assetRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, OperationalAsset.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var result = assetService.create(command);

            assertThat(result.getUid()).isEqualTo("ASSET-001");
            assertThat(result.getName()).isEqualTo("Web Server");
            assertThat(result.getAssetType()).isEqualTo(AssetType.SERVICE);
        }

        @Test
        void createNormalizesUidToUpperCase() {
            var command = new CreateAssetCommand(projectId, "asset-001", "Web Server", null, null);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-001"))
                    .thenReturn(false);
            when(assetRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, OperationalAsset.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var result = assetService.create(command);

            assertThat(result.getUid()).isEqualTo("ASSET-001");
        }

        @Test
        void createDuplicateUidThrowsConflict() {
            var command = new CreateAssetCommand(projectId, "ASSET-001", "Web Server", null, null);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-001"))
                    .thenReturn(true);

            assertThatThrownBy(() -> assetService.create(command)).isInstanceOf(ConflictException.class);
        }

        @Test
        void createPersistsOwnershipCriticalityScopeMetadata() {
            // GC-M012: ownership, stewardship, environment, criticality,
            // business/mission context, and scope designation must persist on
            // the asset alongside the existing core attributes.
            var command = new CreateAssetCommand(
                    projectId,
                    "ASSET-007",
                    "Payments API",
                    "Production payments service.",
                    AssetType.SERVICE,
                    "alice@example.com",
                    "platform-sre",
                    AssetEnvironment.PRODUCTION,
                    AssetCriticality.CRITICAL,
                    "Revenue-bearing customer payment flow; PCI-DSS scope.",
                    AssetScope.IN_SCOPE);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-007"))
                    .thenReturn(false);
            when(assetRepository.save(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, OperationalAsset.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var result = assetService.create(command);

            assertThat(result.getOwner()).isEqualTo("alice@example.com");
            assertThat(result.getSteward()).isEqualTo("platform-sre");
            assertThat(result.getEnvironment()).isEqualTo(AssetEnvironment.PRODUCTION);
            assertThat(result.getCriticality()).isEqualTo(AssetCriticality.CRITICAL);
            assertThat(result.getBusinessContext()).isEqualTo("Revenue-bearing customer payment flow; PCI-DSS scope.");
            assertThat(result.getScopeDesignation()).isEqualTo(AssetScope.IN_SCOPE);
        }
    }

    @Nested
    class Update {

        @Test
        void updateNameOnly() {
            var asset = createAsset("ASSET-001", "Old Name");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetCommand("New Name", null, null);
            var result = assetService.update(asset.getId(), command);

            assertThat(result.getName()).isEqualTo("New Name");
        }

        @Test
        void updateBlankNameThrows() {
            var asset = createAsset("ASSET-001", "Old Name");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

            var command = new UpdateAssetCommand("", null, null);

            assertThatThrownBy(() -> assetService.update(asset.getId(), command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("must not be blank");
        }

        @Test
        void updateAllFields() {
            var asset = createAsset("ASSET-001", "Old");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetCommand("New", "A new description", AssetType.DATABASE);
            var result = assetService.update(asset.getId(), command);

            assertThat(result.getName()).isEqualTo("New");
            assertThat(result.getDescription()).isEqualTo("A new description");
            assertThat(result.getAssetType()).isEqualTo(AssetType.DATABASE);
        }

        @Test
        void updateOwnershipCriticalityScopeMetadata() {
            // GC-M012: each new metadata field follows null-means-unchanged
            // semantics (mirrors the existing name/description/assetType
            // branch). Setting one field leaves the others on the asset alone.
            var asset = createAsset("ASSET-001", "Payments API");
            asset.setOwner("legacy-owner");
            asset.setCriticality(AssetCriticality.LOW);
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetCommand(
                    null,
                    null,
                    null,
                    "alice@example.com",
                    "platform-sre",
                    AssetEnvironment.PRODUCTION,
                    AssetCriticality.CRITICAL,
                    "Revenue-bearing payments flow.",
                    AssetScope.IN_SCOPE);
            var result = assetService.update(projectId, asset.getId(), command);

            assertThat(result.getOwner()).isEqualTo("alice@example.com");
            assertThat(result.getSteward()).isEqualTo("platform-sre");
            assertThat(result.getEnvironment()).isEqualTo(AssetEnvironment.PRODUCTION);
            assertThat(result.getCriticality()).isEqualTo(AssetCriticality.CRITICAL);
            assertThat(result.getBusinessContext()).isEqualTo("Revenue-bearing payments flow.");
            assertThat(result.getScopeDesignation()).isEqualTo(AssetScope.IN_SCOPE);
            // Core fields (name, description, assetType) untouched.
            assertThat(result.getName()).isEqualTo("Payments API");
        }

        @Test
        void updateClearsMetadataFieldsWhenClearFlagSet() {
            // GC-M012: nullable metadata must be resettable to NULL once set.
            // The clear flag wins over null-means-unchanged so callers can
            // re-undesignate a previously-assigned criticality / scope / etc.
            var asset = createAsset("ASSET-001", "Payments API");
            asset.setOwner("alice@example.com");
            asset.setSteward("platform-sre");
            asset.setEnvironment(AssetEnvironment.PRODUCTION);
            asset.setCriticality(AssetCriticality.CRITICAL);
            asset.setBusinessContext("PCI scope");
            asset.setScopeDesignation(AssetScope.IN_SCOPE);
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
                    /* clearOwner */ true,
                    /* clearSteward */ true,
                    /* clearEnvironment */ true,
                    /* clearCriticality */ true,
                    /* clearBusinessContext */ true,
                    /* clearScopeDesignation */ true);
            var result = assetService.update(projectId, asset.getId(), command);

            assertThat(result.getOwner()).isNull();
            assertThat(result.getSteward()).isNull();
            assertThat(result.getEnvironment()).isNull();
            assertThat(result.getCriticality()).isNull();
            assertThat(result.getBusinessContext()).isNull();
            assertThat(result.getScopeDesignation()).isNull();
        }

        @Test
        void updateClearFlagWinsOverSamePayloadAssignment() {
            // Documented semantic: clear wins so the wire form is unambiguous.
            var asset = createAsset("ASSET-001", "Payments API");
            asset.setCriticality(AssetCriticality.HIGH);
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetCommand(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    /* criticality */ AssetCriticality.CRITICAL,
                    null,
                    null,
                    false,
                    false,
                    false,
                    /* clearCriticality */ true,
                    false,
                    false);
            var result = assetService.update(projectId, asset.getId(), command);

            assertThat(result.getCriticality()).isNull();
        }

        @Test
        void updatePreservesUnsetMetadataFields() {
            // Null on a metadata field means "leave alone" — the existing
            // owner / steward / environment / criticality / scope must not
            // get cleared by an update that only touches description.
            var asset = createAsset("ASSET-001", "Payments API");
            asset.setOwner("alice@example.com");
            asset.setSteward("platform-sre");
            asset.setEnvironment(AssetEnvironment.PRODUCTION);
            asset.setCriticality(AssetCriticality.CRITICAL);
            asset.setBusinessContext("PCI scope");
            asset.setScopeDesignation(AssetScope.IN_SCOPE);
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetCommand(null, "Updated description.", null);
            var result = assetService.update(projectId, asset.getId(), command);

            assertThat(result.getDescription()).isEqualTo("Updated description.");
            assertThat(result.getOwner()).isEqualTo("alice@example.com");
            assertThat(result.getSteward()).isEqualTo("platform-sre");
            assertThat(result.getEnvironment()).isEqualTo(AssetEnvironment.PRODUCTION);
            assertThat(result.getCriticality()).isEqualTo(AssetCriticality.CRITICAL);
            assertThat(result.getBusinessContext()).isEqualTo("PCI scope");
            assertThat(result.getScopeDesignation()).isEqualTo(AssetScope.IN_SCOPE);
        }
    }

    @Nested
    class Read {

        @Test
        void getByIdReturnsAsset() {
            var asset = createAsset("ASSET-001", "Test");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

            var result = assetService.getById(asset.getId());
            assertThat(result.getUid()).isEqualTo("ASSET-001");
        }

        @Test
        void getByIdNotFoundThrows() {
            var id = UUID.randomUUID();
            when(assetRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> assetService.getById(id)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void getByUidReturnsAsset() {
            var asset = createAsset("ASSET-001", "Test");
            when(assetRepository.findByProjectIdAndUidIgnoreCase(projectId, "ASSET-001"))
                    .thenReturn(Optional.of(asset));

            var result = assetService.getByUid(projectId, "ASSET-001");
            assertThat(result.getUid()).isEqualTo("ASSET-001");
        }

        @Test
        void listByProjectReturnsList() {
            var a1 = createAsset("ASSET-001", "First");
            var a2 = createAsset("ASSET-002", "Second");
            when(assetRepository.findByProjectIdAndArchivedAtIsNull(projectId)).thenReturn(List.of(a1, a2));

            var result = assetService.listByProject(projectId);
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    class Archive {

        @Test
        void archiveSetsArchivedAt() {
            var asset = createAsset("ASSET-001", "Test");
            when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = assetService.archive(asset.getId());
            assertThat(result.getArchivedAt()).isNotNull();
        }
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
            org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never())
                    .delete(asset);
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
            org.mockito.Mockito.verify(assetRepository, org.mockito.Mockito.never())
                    .delete(asset);
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

            assertThatThrownBy(() -> assetService.createRelation(
                            projectId, source.getId(), target.getId(), AssetRelationType.DEPENDS_ON))
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

            assertThatThrownBy(() -> assetService.updateRelation(
                            projectId,
                            unrelated.getId(),
                            relation.getId(),
                            new UpdateAssetRelationCommand("desc", null, null, null, null)))
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

            assertThatThrownBy(() -> assetService.deleteRelation(projectId, unrelated.getId(), relation.getId()))
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

            assertThatThrownBy(() -> assetService.createLink(projectId, asset.getId(), command))
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

            assertThatThrownBy(() -> assetService.deleteLink(projectId, other.getId(), link.getId()))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void deleteLinkWithProjectIdNotFoundThrows() {
            var linkId = UUID.randomUUID();
            when(linkRepository.findByIdWithAssetAndProjectId(linkId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> assetService.deleteLink(projectId, UUID.randomUUID(), linkId))
                    .isInstanceOf(NotFoundException.class);
        }
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

            assertThatThrownBy(() -> assetService.createExternalId(projectId, asset.getId(), command))
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

            assertThatThrownBy(() -> assetService.updateExternalId(
                            projectId, other.getId(), extId.getId(), new UpdateAssetExternalIdCommand(null, "LOW")))
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

            assertThatThrownBy(() -> assetService.deleteExternalId(projectId, other.getId(), extId.getId()))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("does not belong");
        }

        @Test
        void deleteExternalIdWithProjectIdNotFoundThrows() {
            var extIdId = UUID.randomUUID();
            when(externalIdRepository.findByIdWithAssetAndProjectId(extIdId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> assetService.deleteExternalId(projectId, UUID.randomUUID(), extIdId))
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

    @Nested
    class SubtypeSchemaRegistry {

        @Test
        void registerFirstSchemaIsActive() {
            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId,
                    AssetType.IDENTITY,
                    "service_principal",
                    "v1",
                    "Cloud service principals",
                    Map.of("fields", Map.of("client_id", Map.of("type", "STRING", "required", true))));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                            projectId, AssetType.IDENTITY, "service_principal", "v1"))
                    .thenReturn(false);
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.IDENTITY, "service_principal", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(subtypeSchemaRepository.saveAndFlush(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetSubtypeSchema.class);
                setField(saved, "id", UUID.randomUUID());
                return saved;
            });

            var result = assetService.registerSubtypeSchema(command);

            assertThat(result.getStatus()).isEqualTo(AssetSubtypeSchemaStatus.ACTIVE);
            assertThat(result.getSubtype()).isEqualTo("service_principal");
            assertThat(result.getSchemaVersion()).isEqualTo("v1");
        }

        @Test
        void registerSecondActiveDeprecatesPrevious() {
            var existing = new AssetSubtypeSchema(project, AssetType.IDENTITY, "service_principal", "v1", Map.of());
            setField(existing, "id", UUID.randomUUID());

            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId,
                    AssetType.IDENTITY,
                    "service_principal",
                    "v2",
                    null,
                    Map.of("fields", Map.of("client_id", Map.of("type", "STRING"))));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                            projectId, AssetType.IDENTITY, "service_principal", "v2"))
                    .thenReturn(false);
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.IDENTITY, "service_principal", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.of(existing));
            when(subtypeSchemaRepository.saveAndFlush(any())).thenAnswer(inv -> {
                var saved = inv.getArgument(0, AssetSubtypeSchema.class);
                if (saved.getId() == null) {
                    setField(saved, "id", UUID.randomUUID());
                }
                return saved;
            });

            var result = assetService.registerSubtypeSchema(command);

            assertThat(existing.getStatus()).isEqualTo(AssetSubtypeSchemaStatus.DEPRECATED);
            assertThat(result.getStatus()).isEqualTo(AssetSubtypeSchemaStatus.ACTIVE);
            // Both writes use saveAndFlush to force ordering: the deprecation
            // UPDATE must hit the DB before the new ACTIVE INSERT, or the
            // partial unique index uk_asset_subtype_schema_active fires
            // against the still-ACTIVE prior row.
            verify(subtypeSchemaRepository, org.mockito.Mockito.times(2)).saveAndFlush(any());
        }

        @Test
        void registerDuplicateVersionConflicts() {
            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId,
                    AssetType.IDENTITY,
                    "service_principal",
                    "v1",
                    null,
                    Map.of("fields", Map.of("client_id", Map.of("type", "STRING"))));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                            projectId, AssetType.IDENTITY, "service_principal", "v1"))
                    .thenReturn(true);

            assertThatThrownBy(() -> assetService.registerSubtypeSchema(command))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        void registerTranslatesDbUniqueViolationToConflict() {
            // Codex pre-push review: the partial unique index on
            // (project, asset_type, subtype) WHERE status='ACTIVE' (V075) is
            // the safety net for a concurrent race past the service-layer
            // existence check. Spring's DataIntegrityViolationException must
            // surface as ConflictException rather than HTTP 500.
            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId,
                    AssetType.IDENTITY,
                    "service_principal",
                    "v1",
                    null,
                    Map.of("fields", Map.of("client_id", Map.of("type", "STRING"))));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(subtypeSchemaRepository.existsByProjectIdAndAssetTypeAndSubtypeAndSchemaVersion(
                            projectId, AssetType.IDENTITY, "service_principal", "v1"))
                    .thenReturn(false);
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.IDENTITY, "service_principal", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(subtypeSchemaRepository.saveAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "uk_asset_subtype_schema_active"));

            assertThatThrownBy(() -> assetService.registerSubtypeSchema(command))
                    .isInstanceOf(ConflictException.class)
                    .extracting(e -> ((ConflictException) e).getErrorCode())
                    .isEqualTo("asset_subtype_schema_active_conflict");
        }

        @Test
        void registerRejectsMalformedSchemaBody() {
            // Codex pre-push review: a malformed schema body must be rejected at
            // the registry boundary so it cannot block subsequent asset writes.
            var command = new CreateAssetSubtypeSchemaCommand(
                    projectId, AssetType.IDENTITY, "service_principal", "v1", null, Map.of("fields", "not-a-map"));

            assertThatThrownBy(() -> assetService.registerSubtypeSchema(command))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void registerRejectsBlankSubtype() {
            var command = new CreateAssetSubtypeSchemaCommand(projectId, AssetType.SERVICE, " ", "v1", null, Map.of());

            assertThatThrownBy(() -> assetService.registerSubtypeSchema(command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("subtype");
        }

        @Test
        void deprecateMarksSchemaDeprecated() {
            var schema = new AssetSubtypeSchema(project, AssetType.SERVICE, "internal_api", "v1", Map.of());
            setField(schema, "id", UUID.randomUUID());
            when(subtypeSchemaRepository.findByIdAndProjectId(schema.getId(), projectId))
                    .thenReturn(Optional.of(schema));
            when(subtypeSchemaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assetService.deprecateSubtypeSchema(projectId, schema.getId());

            assertThat(schema.getStatus()).isEqualTo(AssetSubtypeSchemaStatus.DEPRECATED);
        }

        @Test
        void updateRejectsClearSchemaBodyOnActiveRow() {
            // Codex over-cap finding 3: an ACTIVE registry row must keep an
            // enforceable schema body. Callers must deprecate first if the
            // intent is to drop the contract entirely.
            var schema = new AssetSubtypeSchema(
                    project,
                    AssetType.SERVICE,
                    "internal_api",
                    "v1",
                    Map.of("fields", Map.of("name", Map.of("type", "STRING"))));
            setField(schema, "id", UUID.randomUUID());
            when(subtypeSchemaRepository.findByIdAndProjectId(schema.getId(), projectId))
                    .thenReturn(Optional.of(schema));

            var command = new UpdateAssetSubtypeSchemaCommand(null, null, false, /* clearSchemaBody */ true);
            var schemaId = schema.getId();

            assertThatThrownBy(() -> assetService.updateSubtypeSchema(projectId, schemaId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getErrorCode())
                    .isEqualTo("asset_subtype_schema_active_body_required");
        }

        @Test
        void updateReplacesSchemaBody() {
            var schema = new AssetSubtypeSchema(project, AssetType.SERVICE, "internal_api", "v1", Map.of());
            setField(schema, "id", UUID.randomUUID());
            when(subtypeSchemaRepository.findByIdAndProjectId(schema.getId(), projectId))
                    .thenReturn(Optional.of(schema));
            when(subtypeSchemaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetSubtypeSchemaCommand(
                    "New description", Map.of("fields", Map.of("name", Map.of("type", "STRING"))), false, false);

            var result = assetService.updateSubtypeSchema(projectId, schema.getId(), command);

            assertThat(result.getDescription()).isEqualTo("New description");
            assertThat(result.getSchemaBody()).containsKey("fields");
        }

        @Test
        void getActiveThrowsWhenAbsent() {
            when(subtypeSchemaRepository.findByProjectIdAndAssetTypeAndSubtypeAndStatus(
                            projectId, AssetType.SERVICE, "missing", AssetSubtypeSchemaStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> assetService.getActiveSubtypeSchema(projectId, AssetType.SERVICE, "missing"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void listByProjectOnlyReturnsAllProjectSchemas() {
            var s1 = new AssetSubtypeSchema(project, AssetType.SERVICE, "api", "v1", Map.of());
            var s2 = new AssetSubtypeSchema(project, AssetType.IDENTITY, "user_account", "v1", Map.of());
            when(subtypeSchemaRepository.findByProjectId(projectId)).thenReturn(List.of(s1, s2));

            var result = assetService.listSubtypeSchemas(projectId, null, null);

            assertThat(result).hasSize(2);
        }

        @Test
        void listRejectsSubtypeWithoutAssetType() {
            assertThatThrownBy(() -> assetService.listSubtypeSchemas(projectId, null, "rogue"))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("assetType");
        }
    }

    @Nested
    class KnowledgeStateBehavior {

        @Test
        void createDefaultsToConfirmedWhenOmitted() {
            // GC-M018: omission == CONFIRMED. The entity initializer sets
            // CONFIRMED so the service path that simply doesn't pass a value
            // produces the same end state as an explicit CONFIRMED.
            var command = new CreateAssetCommand(projectId, "ASSET-001", "Service", "desc", AssetType.SERVICE);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-001"))
                    .thenReturn(false);
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = assetService.create(command);

            assertThat(result.getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.CONFIRMED);
        }

        @Test
        void createAcceptsExplicitProvisional() {
            // GC-M018: PROVISIONAL is the explicit "manually asserted but not
            // yet validated" state. The service must not silently coerce it.
            var command = new CreateAssetCommand(
                    projectId,
                    "ASSET-002",
                    "Tentative Service",
                    "Manually asserted; not validated.",
                    AssetType.SERVICE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.PROVISIONAL);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-002"))
                    .thenReturn(false);
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = assetService.create(command);

            assertThat(result.getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.PROVISIONAL);
        }

        @Test
        void createAcceptsExplicitUnknownForPlaceholderAssets() {
            // GC-M018: UNKNOWN is the placeholder-asset state — an
            // operational asset row whose existence is asserted because a
            // dependency points at it, but whose details aren't known yet.
            var command = new CreateAssetCommand(
                    projectId,
                    "ASSET-PLACEHOLDER",
                    "Unknown Service",
                    "Placeholder for an unresolved dependency.",
                    AssetType.OTHER,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.UNKNOWN);
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
            when(assetRepository.existsByProjectIdAndUidIgnoreCase(projectId, "ASSET-PLACEHOLDER"))
                    .thenReturn(false);
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = assetService.create(command);

            assertThat(result.getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.UNKNOWN);
        }

        @Test
        void updateNullKnowledgeStateLeavesUnchanged() {
            // Null = leave unchanged. The service must not coerce a
            // pre-existing PROVISIONAL back to CONFIRMED on an update that
            // only touches description.
            var asset = createAsset("ASSET-001", "Service");
            asset.setKnowledgeState(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.PROVISIONAL);
            when(assetRepository.findByIdAndProjectId(asset.getId(), projectId)).thenReturn(Optional.of(asset));
            when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetCommand(null, "Updated description.", null);
            var result = assetService.update(projectId, asset.getId(), command);

            assertThat(result.getDescription()).isEqualTo("Updated description.");
            assertThat(result.getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.PROVISIONAL);
        }

        @Test
        void updateTransitionsProvisionalToConfirmed() {
            // Once a manually-asserted asset is validated, the caller flips
            // it to CONFIRMED. The service must accept any non-null
            // KnowledgeState — there is no automatic promotion workflow.
            var asset = createAsset("ASSET-001", "Service");
            asset.setKnowledgeState(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.PROVISIONAL);
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
                    com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.CONFIRMED,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false);
            var result = assetService.update(projectId, asset.getId(), command);

            assertThat(result.getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.CONFIRMED);
        }

        @Test
        void createRelationDefaultsToConfirmed() {
            // GC-M018: relation defaults to CONFIRMED, same as the asset.
            var source = createAsset("ASSET-SRC", "Source");
            var target = createAsset("ASSET-TGT", "Target");
            when(assetRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(assetRepository.findByIdAndProjectId(target.getId(), projectId))
                    .thenReturn(Optional.of(target));
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(any(), any(), any()))
                    .thenReturn(false);
            when(relationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateAssetRelationCommand(
                    target.getId(), AssetRelationType.DEPENDS_ON, null, null, null, null, null);
            var result = assetService.createRelation(projectId, command, source.getId());

            assertThat(result.getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.CONFIRMED);
        }

        @Test
        void createRelationAcceptsUnknownForTentativeDependencies() {
            // GC-M018: a tentative dependency to a placeholder target is
            // expressed as an UNKNOWN relation. Risk / threat / control
            // workflows that see the edge can choose whether to treat it as
            // coverage.
            var source = createAsset("ASSET-SRC", "Source");
            var placeholder = createAsset("ASSET-UNKNOWN", "Placeholder");
            placeholder.setKnowledgeState(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.UNKNOWN);
            when(assetRepository.findByIdAndProjectId(source.getId(), projectId))
                    .thenReturn(Optional.of(source));
            when(assetRepository.findByIdAndProjectId(placeholder.getId(), projectId))
                    .thenReturn(Optional.of(placeholder));
            when(relationRepository.existsBySourceIdAndTargetIdAndRelationType(any(), any(), any()))
                    .thenReturn(false);
            when(relationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateAssetRelationCommand(
                    placeholder.getId(),
                    AssetRelationType.DEPENDS_ON,
                    "Unresolved external dependency",
                    null,
                    null,
                    null,
                    null,
                    com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.UNKNOWN);
            var result = assetService.createRelation(projectId, command, source.getId());

            assertThat(result.getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.UNKNOWN);
            assertThat(result.getTarget().getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.UNKNOWN);
        }

        @Test
        void updateRelationNullKnowledgeStateLeavesUnchanged() {
            // Null on update = leave alone. Confirmation level on a topology
            // edge must survive an update that only touches confidence.
            var source = createAsset("ASSET-SRC", "Source");
            var target = createAsset("ASSET-TGT", "Target");
            var relation = new AssetRelation(source, target, AssetRelationType.DEPENDS_ON);
            setField(relation, "id", UUID.randomUUID());
            relation.setKnowledgeState(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.PROVISIONAL);
            when(relationRepository.findByIdWithEntitiesAndProjectId(relation.getId(), projectId))
                    .thenReturn(Optional.of(relation));
            when(relationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateAssetRelationCommand(null, null, null, null, "0.85");
            var result = assetService.updateRelation(projectId, source.getId(), relation.getId(), command);

            assertThat(result.getKnowledgeState())
                    .isEqualTo(com.keplerops.groundcontrol.domain.assets.state.KnowledgeState.PROVISIONAL);
            assertThat(result.getConfidence()).isEqualTo("0.85");
        }
    }
}
