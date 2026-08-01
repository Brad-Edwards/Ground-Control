package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetCriticality;
import com.keplerops.groundcontrol.domain.assets.state.AssetEnvironment;
import com.keplerops.groundcontrol.domain.assets.state.AssetScope;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
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

            var assetId = asset.getId();
            assertThatThrownBy(() -> assetService.update(assetId, command))
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
}
