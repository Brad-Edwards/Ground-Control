package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityMatrixResult;
import com.keplerops.groundcontrol.domain.requirements.service.TraceabilityMatrixService;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for TraceabilityMatrixService — read-only composition over requirements and their
 * traceability links per GC-Q003.
 */
@ExtendWith(MockitoExtension.class)
class TraceabilityMatrixServiceTest {

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @InjectMocks
    private TraceabilityMatrixService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Requirement req(String uid, Status status, Integer wave) {
        Requirement r = new Requirement(project, uid, "Title " + uid, "Statement for " + uid);
        setField(r, "id", UUID.randomUUID());
        if (wave != null) {
            r.setWave(wave);
        }
        if (status == Status.ACTIVE) {
            r.transitionStatus(Status.ACTIVE);
        } else if (status == Status.DEPRECATED) {
            r.transitionStatus(Status.ACTIVE);
            r.transitionStatus(Status.DEPRECATED);
        }
        return r;
    }

    private TraceabilityLink link(Requirement r, LinkType linkType, ArtifactType artifactType, String identifier) {
        TraceabilityLink l = new TraceabilityLink(r, artifactType, identifier, linkType);
        setField(l, "id", UUID.randomUUID());
        return l;
    }

    private TraceabilityMatrixResult.MatrixRow rowFor(TraceabilityMatrixResult result, String uid) {
        return result.rows().stream()
                .filter(row -> row.uid().equals(uid))
                .findFirst()
                .orElseThrow(() -> new AssertionError("row not found: " + uid));
    }

    private TraceabilityMatrixResult.LinkTypeColumn column(TraceabilityMatrixResult result, LinkType linkType) {
        return result.columns().stream()
                .filter(c -> c.linkType() == linkType)
                .findFirst()
                .orElseThrow(() -> new AssertionError("column not found: " + linkType));
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Nested
    class EmptyProject {

        @Test
        void returnsEmptyRowsAndAllFiveColumnsWithZeroTotals() {
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of());

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            assertThat(result.rows()).isEmpty();
            assertThat(result.requirementCount()).isZero();
            assertThat(result.linkedRequirementCount()).isZero();
            assertThat(result.gapCount()).isZero();
            assertThat(result.columns()).hasSize(LinkType.values().length);
            assertThat(result.columns()).allSatisfy(c -> {
                assertThat(c.totalRequirements()).isZero();
                assertThat(c.coveredRequirements()).isZero();
                assertThat(c.artifactCount()).isZero();
            });
            // No requirements → no bulk link query is issued.
            verify(traceabilityLinkRepository, never()).findByRequirementIdIn(anyList());
        }
    }

    @Nested
    class Composition {

        @Test
        void groupsLinksIntoCellsAndComputesCoverage() {
            Requirement r = req("GC-001", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList()))
                    .thenReturn(List.of(
                            link(r, LinkType.IMPLEMENTS, ArtifactType.CODE_FILE, "Foo.java"),
                            link(r, LinkType.TESTS, ArtifactType.TEST, "FooTest.java")));

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            TraceabilityMatrixResult.MatrixRow row = rowFor(result, "GC-001");
            assertThat(row.cells()).hasSize(2);
            assertThat(row.coveredLinkTypes()).containsExactlyInAnyOrder(LinkType.IMPLEMENTS, LinkType.TESTS);
            assertThat(row.hasGap()).isFalse();
            assertThat(result.linkedRequirementCount()).isEqualTo(1);
            assertThat(column(result, LinkType.IMPLEMENTS).coveredRequirements())
                    .isEqualTo(1);
            assertThat(column(result, LinkType.TESTS).coveredRequirements()).isEqualTo(1);
        }

        @Test
        void countsMultipleArtifactsOfSameTypeOnceForCoverageButFullyForArtifactCount() {
            Requirement r = req("GC-002", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList()))
                    .thenReturn(List.of(
                            link(r, LinkType.IMPLEMENTS, ArtifactType.CODE_FILE, "Foo.java"),
                            link(r, LinkType.IMPLEMENTS, ArtifactType.CODE_FILE, "Bar.java")));

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            assertThat(column(result, LinkType.IMPLEMENTS).coveredRequirements())
                    .isEqualTo(1);
            assertThat(column(result, LinkType.IMPLEMENTS).artifactCount()).isEqualTo(2);
        }
    }

    @Nested
    class GapDetection {

        @Test
        void flagsActiveRequirementMissingTests() {
            Requirement r = req("GC-003", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList()))
                    .thenReturn(List.of(link(r, LinkType.IMPLEMENTS, ArtifactType.CODE_FILE, "Foo.java")));

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            assertThat(rowFor(result, "GC-003").hasGap()).isTrue();
            assertThat(result.gapCount()).isEqualTo(1);
        }

        @Test
        void doesNotFlagDraftRequirementWithoutLinks() {
            Requirement r = req("GC-004", Status.DRAFT, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList())).thenReturn(List.of());

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            assertThat(rowFor(result, "GC-004").hasGap()).isFalse();
            assertThat(result.gapCount()).isZero();
        }

        @Test
        void doesNotFlagDeprecatedRequirementMissingBothAxes() {
            // The gap guard is status==ACTIVE only; DEPRECATED is a valid terminal status and
            // must never be flagged even with zero links. Guards against the != ACTIVE check
            // being accidentally narrowed to DRAFT only.
            Requirement r = req("GC-DEP", Status.DEPRECATED, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList())).thenReturn(List.of());

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            assertThat(rowFor(result, "GC-DEP").hasGap()).isFalse();
            assertThat(result.gapCount()).isZero();
        }

        @Test
        void doesNotFlagActiveRequirementWithBothAxes() {
            Requirement r = req("GC-005", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList()))
                    .thenReturn(List.of(
                            link(r, LinkType.IMPLEMENTS, ArtifactType.CODE_FILE, "Foo.java"),
                            link(r, LinkType.TESTS, ArtifactType.TEST, "FooTest.java")));

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            assertThat(rowFor(result, "GC-005").hasGap()).isFalse();
        }
    }

    @Nested
    class Filtering {

        @Test
        void statusFilterUsesScopedRepositoryQuery() {
            Requirement r = req("GC-006", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndStatusAndArchivedAtIsNull(PROJECT_ID, Status.ACTIVE))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList())).thenReturn(List.of());

            service.matrix(PROJECT_ID, null, Status.ACTIVE, null);

            verify(requirementRepository).findByProjectIdAndStatusAndArchivedAtIsNull(PROJECT_ID, Status.ACTIVE);
            verify(requirementRepository, never()).findByProjectIdAndArchivedAtIsNull(eq(PROJECT_ID));
        }

        @Test
        void waveFilterKeepsOnlyMatchingRequirements() {
            Requirement w1 = req("GC-007", Status.ACTIVE, 1);
            Requirement w2 = req("GC-008", Status.ACTIVE, 2);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(w1, w2));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList())).thenReturn(List.of());

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, 2, null, null);

            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).uid()).isEqualTo("GC-008");
        }

        @Test
        void linkTypeFilterNarrowsCellsAndColumns() {
            Requirement r = req("GC-009", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList()))
                    .thenReturn(List.of(
                            link(r, LinkType.IMPLEMENTS, ArtifactType.CODE_FILE, "Foo.java"),
                            link(r, LinkType.TESTS, ArtifactType.TEST, "FooTest.java")));

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, LinkType.IMPLEMENTS);

            assertThat(result.columns()).hasSize(1);
            assertThat(result.columns().get(0).linkType()).isEqualTo(LinkType.IMPLEMENTS);
            TraceabilityMatrixResult.MatrixRow row = rowFor(result, "GC-009");
            assertThat(row.cells()).hasSize(1);
            assertThat(row.cells().get(0).linkType()).isEqualTo(LinkType.IMPLEMENTS);
            // With IMPLEMENTS as the sole axis and present, no gap.
            assertThat(row.hasGap()).isFalse();
        }

        @Test
        void linkTypeFilterFlagsGapWhenThatTypeMissingOnActiveRequirement() {
            Requirement r = req("GC-010", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(r));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList()))
                    .thenReturn(List.of(link(r, LinkType.IMPLEMENTS, ArtifactType.CODE_FILE, "Foo.java")));

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, LinkType.TESTS);

            TraceabilityMatrixResult.MatrixRow row = rowFor(result, "GC-010");
            assertThat(row.cells()).isEmpty();
            assertThat(row.hasGap()).isTrue();
        }
    }

    @Nested
    class Ordering {

        @Test
        void rowsAreSortedByUid() {
            Requirement c = req("GC-300", Status.ACTIVE, 1);
            Requirement a = req("GC-100", Status.ACTIVE, 1);
            Requirement b = req("GC-200", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(c, a, b));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList())).thenReturn(List.of());

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            assertThat(result.rows().stream().map(TraceabilityMatrixResult.MatrixRow::uid))
                    .containsExactly("GC-100", "GC-200", "GC-300");
        }

        @Test
        void linkedRequirementCountOnlyCountsRowsWithCells() {
            Requirement linked = req("GC-400", Status.ACTIVE, 1);
            Requirement bare = req("GC-401", Status.ACTIVE, 1);
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(linked, bare));
            when(traceabilityLinkRepository.findByRequirementIdIn(anyList()))
                    .thenReturn(List.of(link(linked, LinkType.DOCUMENTS, ArtifactType.ADR, "ADR-017")));

            TraceabilityMatrixResult result = service.matrix(PROJECT_ID, null, null, null);

            assertThat(result.linkedRequirementCount()).isEqualTo(1);
            assertThat(result.requirementCount()).isEqualTo(2);
        }
    }
}
