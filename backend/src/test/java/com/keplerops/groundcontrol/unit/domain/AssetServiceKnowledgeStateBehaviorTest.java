package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetExternalIdRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.AssetSubtypeSchemaRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetRelationCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetRelationCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
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
class AssetServiceKnowledgeStateBehaviorTest {
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
