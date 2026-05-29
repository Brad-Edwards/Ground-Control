package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.AssetRelation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetRelationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetRelationType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService;
import com.keplerops.groundcontrol.domain.grcanalysis.service.EvidenceFreshnessAnalysisService.AssetScopedFreshnessSummary;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModelLink;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelWorkspaceResult.WorkspaceAsset;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelWorkspaceService;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkType;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
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

/**
 * Unit tests for ThreatModelWorkspaceService — read-only composition over existing aggregates
 * per GC-Q010 and the implementation plan.
 *
 * <p>The service receives an already-resolved {@code projectId} (resolution happens in the
 * controller). No ProjectService mock is needed here.
 */
@ExtendWith(MockitoExtension.class)
class ThreatModelWorkspaceServiceTest {

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private AssetRelationRepository assetRelationRepository;

    @Mock
    private ThreatModelRepository threatModelRepository;

    @Mock
    private ThreatModelLinkRepository threatModelLinkRepository;

    @Mock
    private EvidenceFreshnessAnalysisService evidenceFreshnessAnalysisService;

    @InjectMocks
    private ThreatModelWorkspaceService service;

    private Project project;
    private UUID projectId;
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");
    private static final int WINDOW = 90;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private OperationalAsset makeAsset(String uid, String name, AssetType type) {
        var a = new OperationalAsset(project, uid, name);
        a.setAssetType(type);
        setField(a, "id", UUID.randomUUID());
        setField(a, "createdAt", NOW);
        setField(a, "updatedAt", NOW);
        return a;
    }

    private ThreatModel makeThreatModel(String uid, ThreatModelStatus status, StrideCategory stride) {
        var tm = new ThreatModel(project, uid, "Title " + uid, "Source", "Event", "Effect");
        // ThreatModel starts as DRAFT; transition to ACTIVE first.
        tm.transitionStatus(ThreatModelStatus.ACTIVE);
        if (status == ThreatModelStatus.ARCHIVED) {
            tm.transitionStatus(ThreatModelStatus.ARCHIVED);
        }
        if (stride != null) {
            tm.setStride(stride);
        }
        setField(tm, "id", UUID.randomUUID());
        setField(tm, "createdAt", NOW);
        setField(tm, "updatedAt", NOW);
        return tm;
    }

    private ThreatModel makeDraftThreatModel(String uid) {
        var tm = new ThreatModel(project, uid, "Title " + uid, "Source", "Event", "Effect");
        setField(tm, "id", UUID.randomUUID());
        setField(tm, "createdAt", NOW);
        setField(tm, "updatedAt", NOW);
        return tm;
    }

    private ThreatModelLink makeLink(ThreatModel tm, ThreatModelLinkTargetType type, UUID entityId, String identifier) {
        var link = new ThreatModelLink(tm, type, entityId, identifier, ThreatModelLinkType.AFFECTS);
        link.setTargetTitle("Title");
        link.setTargetUrl("https://example.com");
        setField(link, "id", UUID.randomUUID());
        return link;
    }

    private AssetScopedFreshnessSummary freshSummary() {
        return new AssetScopedFreshnessSummary(3, 0, 0, 0, "FRESH");
    }

    private AssetScopedFreshnessSummary staleSummary() {
        return new AssetScopedFreshnessSummary(0, 2, 0, 0, "STALE");
    }

    @Nested
    class AssetAndBoundaryPartition {

        @Test
        void partitionsAssetsAndBoundaries() {
            var asset = makeAsset("A-001", "Auth Service", AssetType.SERVICE);
            var boundary = makeAsset("B-001", "DMZ", AssetType.BOUNDARY);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of(asset, boundary));
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.assets()).hasSize(2);

            WorkspaceAsset assetItem = result.assets().stream()
                    .filter(a -> a.uid().equals("A-001"))
                    .findFirst()
                    .orElseThrow();
            assertThat(assetItem.isBoundary()).isFalse();
            assertThat(assetItem.assetType()).isEqualTo(AssetType.SERVICE);

            WorkspaceAsset boundaryItem = result.assets().stream()
                    .filter(a -> a.uid().equals("B-001"))
                    .findFirst()
                    .orElseThrow();
            assertThat(boundaryItem.isBoundary()).isTrue();
            assertThat(boundaryItem.assetType()).isEqualTo(AssetType.BOUNDARY);
        }
    }

    @Nested
    class FlowsIncluded {

        @Test
        void includesFlowsFromActiveRelations() {
            var source = makeAsset("A-001", "Auth", AssetType.SERVICE);
            var target = makeAsset("A-002", "DB", AssetType.DATABASE);

            var relation = new AssetRelation(source, target, AssetRelationType.DATA_FLOW);
            setField(relation, "id", UUID.randomUUID());

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of(source, target));
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of(relation));
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.flows()).hasSize(1);
            assertThat(result.flows().get(0).sourceAssetId()).isEqualTo(source.getId());
            assertThat(result.flows().get(0).targetAssetId()).isEqualTo(target.getId());
            assertThat(result.flows().get(0).relationType()).isEqualTo(AssetRelationType.DATA_FLOW);
        }
    }

    @Nested
    class EntryLinkGrouping {

        @Test
        void groupsLinksIntoAssetControlRequirementBuckets() {
            var tm = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, StrideCategory.SPOOFING);
            UUID assetEntityId = UUID.randomUUID();
            UUID controlEntityId = UUID.randomUUID();
            UUID reqEntityId = UUID.randomUUID();

            var assetLink = makeLink(tm, ThreatModelLinkTargetType.ASSET, assetEntityId, null);
            var controlLink = makeLink(tm, ThreatModelLinkTargetType.CONTROL, controlEntityId, null);
            var reqLink = makeLink(tm, ThreatModelLinkTargetType.REQUIREMENT, reqEntityId, null);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm));
            when(threatModelLinkRepository.findByProjectId(projectId))
                    .thenReturn(List.of(assetLink, controlLink, reqLink));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetEntityId)))
                    .thenReturn(freshSummary());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.entries()).hasSize(1);
            var entry = result.entries().get(0);
            assertThat(entry.uid()).isEqualTo("TM-001");
            assertThat(entry.status()).isEqualTo(ThreatModelStatus.ACTIVE);
            assertThat(entry.stride()).isEqualTo(StrideCategory.SPOOFING);
            assertThat(entry.linkedAssetIds()).containsExactly(assetEntityId);
            assertThat(entry.linkedControls()).hasSize(1);
            assertThat(entry.linkedControls().get(0).targetEntityId()).isEqualTo(controlEntityId);
            assertThat(entry.linkedRequirements()).hasSize(1);
            assertThat(entry.linkedRequirements().get(0).targetEntityId()).isEqualTo(reqEntityId);
        }
    }

    @Nested
    class StaleRollup {

        @Test
        void entryIsFreshWhenLinkedAssetIsFresh() {
            var tm = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);
            UUID assetEntityId = UUID.randomUUID();
            var assetLink = makeLink(tm, ThreatModelLinkTargetType.ASSET, assetEntityId, null);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of(assetLink));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetEntityId)))
                    .thenReturn(freshSummary());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.entries().get(0).staleIndicator()).isEqualTo("FRESH");
        }

        @Test
        void entryIsStaleWhenLinkedAssetIsStale() {
            var tm = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);
            UUID assetEntityId = UUID.randomUUID();
            var assetLink = makeLink(tm, ThreatModelLinkTargetType.ASSET, assetEntityId, null);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of(assetLink));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetEntityId)))
                    .thenReturn(staleSummary());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.entries().get(0).staleIndicator()).isEqualTo("STALE");
        }

        @Test
        void worstStateWinsAcrossMultipleLinkedAssets() {
            var tm = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);
            UUID assetId1 = UUID.randomUUID();
            UUID assetId2 = UUID.randomUUID();
            var link1 = makeLink(tm, ThreatModelLinkTargetType.ASSET, assetId1, null);
            var link2 = makeLink(tm, ThreatModelLinkTargetType.ASSET, assetId2, null);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of(link1, link2));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetId1)))
                    .thenReturn(freshSummary());
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetId2)))
                    .thenReturn(staleSummary());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.entries().get(0).staleIndicator()).isEqualTo("STALE");
        }

        @Test
        void entryIsNoObservationsWhenNoLinkedAssets() {
            var tm = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.entries().get(0).staleIndicator()).isEqualTo("NO_OBSERVATIONS");
        }
    }

    @Nested
    class AsOfHandling {

        @Test
        void passesAsOfToFreshnessService() {
            var tm = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);
            UUID assetEntityId = UUID.randomUUID();
            var assetLink = makeLink(tm, ThreatModelLinkTargetType.ASSET, assetEntityId, null);
            var customAsOf = Instant.parse("2025-06-01T00:00:00Z");

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of(assetLink));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), eq(customAsOf), eq(WINDOW), eq(assetEntityId)))
                    .thenReturn(freshSummary());

            var result = service.workspace(projectId, customAsOf, WINDOW, null, null, null);

            assertThat(result.entries()).hasSize(1);
        }
    }

    @Nested
    class EmptyProject {

        @Test
        void returnsEmptyResultForProjectWithNoEntities() {
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.assets()).isEmpty();
            assertThat(result.flows()).isEmpty();
            assertThat(result.entries()).isEmpty();
            assertThat(result.assetCount()).isZero();
            assertThat(result.flowCount()).isZero();
            assertThat(result.entryCount()).isZero();
        }
    }

    @Nested
    class ProjectIsolation {

        @Test
        void onlyCallsRepositoriesWithGivenProjectId() {
            // The service receives an already-resolved projectId; project isolation
            // is enforced by the repo layer (project-scoped queries). We verify the
            // service passes only projectId to each repo, not any other project.
            var asset = makeAsset("A-001", "Auth Service", AssetType.SERVICE);
            var tm = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of(asset));
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.assets()).hasSize(1);
            assertThat(result.entries()).hasSize(1);

            // Verify repos are called with the correct projectId
            org.mockito.Mockito.verify(operationalAssetRepository).findByProjectIdAndArchivedAtIsNull(projectId);
            org.mockito.Mockito.verify(assetRelationRepository).findActiveByProjectId(projectId);
            org.mockito.Mockito.verify(threatModelRepository).findByProjectIdOrderByCreatedAtDesc(projectId);
            org.mockito.Mockito.verify(threatModelLinkRepository).findByProjectId(projectId);
        }
    }

    @Nested
    class AssetScopedFilter {

        @Test
        void throwsNotFoundForAssetIdNotInProject() {
            UUID badAssetId = UUID.randomUUID();
            when(operationalAssetRepository.findByIdAndProjectId(badAssetId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.workspace(projectId, null, WINDOW, badAssetId, null, null))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void filtersByAssetId() {
            var asset = makeAsset("A-001", "Auth Service", AssetType.SERVICE);
            var tm1 = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);
            var tm2 = makeThreatModel("TM-002", ThreatModelStatus.ACTIVE, null);
            UUID assetId = asset.getId();

            var linkToAsset = makeLink(tm1, ThreatModelLinkTargetType.ASSET, assetId, null);

            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.of(asset));
            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of(asset));
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm1, tm2));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of(linkToAsset));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetId)))
                    .thenReturn(freshSummary());

            var result = service.workspace(projectId, null, WINDOW, assetId, null, null);

            // Only TM-001 has a link to this asset
            assertThat(result.entries()).hasSize(1);
            assertThat(result.entries().get(0).uid()).isEqualTo("TM-001");
        }
    }

    @Nested
    class Validation {

        @Test
        void throwsDomainValidationExceptionWhenFreshnessWindowIsZero() {
            assertThatThrownBy(() -> service.workspace(projectId, null, 0, null, null, null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("freshnessWindowDays");
        }

        @Test
        void throwsDomainValidationExceptionWhenFreshnessWindowIsNegative() {
            assertThatThrownBy(() -> service.workspace(projectId, null, -1, null, null, null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("freshnessWindowDays");
        }
    }

    @Nested
    class StrideFilter {

        @Test
        void filtersEntriesByStride() {
            var tmSpoofing = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, StrideCategory.SPOOFING);
            var tmTampering = makeThreatModel("TM-002", ThreatModelStatus.ACTIVE, StrideCategory.TAMPERING);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tmSpoofing, tmTampering));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, StrideCategory.SPOOFING, null);

            assertThat(result.entries()).hasSize(1);
            assertThat(result.entries().get(0).uid()).isEqualTo("TM-001");
        }
    }

    @Nested
    class StatusFilter {

        @Test
        void filtersEntriesByStatus() {
            var tmActive = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);
            var tmArchived = makeThreatModel("TM-002", ThreatModelStatus.ARCHIVED, null);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tmActive, tmArchived));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of());

            var result = service.workspace(projectId, null, WINDOW, null, null, ThreatModelStatus.ACTIVE);

            assertThat(result.entries()).hasSize(1);
            assertThat(result.entries().get(0).uid()).isEqualTo("TM-001");
        }
    }

    @Nested
    class FreshnessDeduplication {

        @Test
        void computesFreshnessOncePerUniqueAsset() {
            var tm = makeThreatModel("TM-001", ThreatModelStatus.ACTIVE, null);
            UUID assetEntityId = UUID.randomUUID();

            // Two links to the same asset — freshness should be called only once
            var link1 = makeLink(tm, ThreatModelLinkTargetType.ASSET, assetEntityId, null);
            var link2 = makeLink(tm, ThreatModelLinkTargetType.ASSET, assetEntityId, null);

            when(operationalAssetRepository.findByProjectIdAndArchivedAtIsNull(projectId))
                    .thenReturn(List.of());
            when(assetRelationRepository.findActiveByProjectId(projectId)).thenReturn(List.of());
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(tm));
            when(threatModelLinkRepository.findByProjectId(projectId)).thenReturn(List.of(link1, link2));
            when(evidenceFreshnessAnalysisService.assetScopedEvidenceFreshness(
                            eq(projectId), any(), eq(WINDOW), eq(assetEntityId)))
                    .thenReturn(freshSummary());

            var result = service.workspace(projectId, null, WINDOW, null, null, null);

            assertThat(result.entries()).hasSize(1);
            // Mockito verifies exactly 1 call to freshness (dedup by LinkedHashSet)
            org.mockito.Mockito.verify(evidenceFreshnessAnalysisService, org.mockito.Mockito.times(1))
                    .assetScopedEvidenceFreshness(projectId, null, WINDOW, assetEntityId);
        }
    }
}
