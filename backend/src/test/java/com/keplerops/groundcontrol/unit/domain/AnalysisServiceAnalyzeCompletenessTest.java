package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.service.AnalysisService;
import com.keplerops.groundcontrol.domain.requirements.service.AuditService;
import com.keplerops.groundcontrol.domain.requirements.service.BlockingStatus;
import com.keplerops.groundcontrol.domain.requirements.service.CompletenessResult;
import com.keplerops.groundcontrol.domain.requirements.service.CoverageStats;
import com.keplerops.groundcontrol.domain.requirements.service.DashboardStats;
import com.keplerops.groundcontrol.domain.requirements.service.RecentChange;
import com.keplerops.groundcontrol.domain.requirements.service.WorkOrderItem;
import com.keplerops.groundcontrol.domain.requirements.service.WorkOrderResult;
import com.keplerops.groundcontrol.domain.requirements.service.WorkOrderWave;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from AnalysisServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceAnalyzeCompletenessTest {
    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private RequirementRelationRepository relationRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @Mock
    private AuditService auditService;

    private AnalysisService service;

    private static final List<RelationType> DAG_TYPES =
            List.of(RelationType.PARENT, RelationType.DEPENDS_ON, RelationType.REFINES);

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Project TEST_PROJECT = createTestProject();

    private static Project createTestProject() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", PROJECT_ID);
        return project;
    }

    @BeforeEach
    void setUp() {
        service = new AnalysisService(
                requirementRepository, relationRepository, traceabilityLinkRepository, auditService);
    }

    private static Requirement makeRequirement(String uid, UUID id) {
        var req = new Requirement(TEST_PROJECT, uid, "Title for " + uid, "Statement for " + uid);
        setField(req, "id", id);
        return req;
    }

    private static Requirement makeRequirement(String uid, UUID id, Integer wave) {
        var req = makeRequirement(uid, id);
        req.setWave(wave);
        return req;
    }

    private static void setField(Object obj, String fieldName, Object value) {
        TestUtil.setField(obj, fieldName, value);
    }

    @Nested
    class AnalyzeCompleteness {

        @Test
        void emptyProject_returnsZero() {
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of());

            CompletenessResult result = service.analyzeCompleteness(PROJECT_ID);

            assertThat(result.total()).isZero();
            assertThat(result.byStatus()).isEmpty();
            assertThat(result.issues()).isEmpty();
        }

        @Test
        void countsStatuses() {
            var draft = makeRequirement("REQ-D", UUID.randomUUID());
            var active = makeRequirement("REQ-A", UUID.randomUUID());
            setField(active, "status", Status.ACTIVE);
            var active2 = makeRequirement("REQ-A2", UUID.randomUUID());
            setField(active2, "status", Status.ACTIVE);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(draft, active, active2));

            CompletenessResult result = service.analyzeCompleteness(PROJECT_ID);

            assertThat(result.total()).isEqualTo(3);
            assertThat(result.byStatus()).containsEntry("DRAFT", 1);
            assertThat(result.byStatus()).containsEntry("ACTIVE", 2);
            assertThat(result.issues()).isEmpty();
        }

        @Test
        void detectsMissingStatement() {
            var req = makeRequirement("REQ-BLANK", UUID.randomUUID());
            setField(req, "statement", "");

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(req));

            CompletenessResult result = service.analyzeCompleteness(PROJECT_ID);

            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).uid()).isEqualTo("REQ-BLANK");
            assertThat(result.issues().get(0).issue()).isEqualTo("missing statement");
        }

        @Test
        void detectsMissingTitle() {
            var req = makeRequirement("REQ-NOTITLE", UUID.randomUUID());
            setField(req, "title", "");

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(req));

            CompletenessResult result = service.analyzeCompleteness(PROJECT_ID);

            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().get(0).uid()).isEqualTo("REQ-NOTITLE");
            assertThat(result.issues().get(0).issue()).isEqualTo("missing title");
        }
    }

    @Nested
    class GetDashboardStats {

        @Test
        void emptyProject_returnsZeroCounts() {
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of());
            when(auditService.getRecentRequirementChanges(Set.of(), 10)).thenReturn(List.of());

            DashboardStats result = service.getDashboardStats(PROJECT_ID);

            assertThat(result.totalRequirements()).isZero();
            assertThat(result.byStatus()).isEmpty();
            assertThat(result.byWave()).isEmpty();
            assertThat(result.coverageByLinkType()).hasSize(LinkType.values().length);
            assertThat(result.recentChanges()).isEmpty();
        }

        @Test
        void aggregatesByStatusAndWave() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();
            var draft1 = makeRequirement("REQ-D1", aId, 1);
            var active1 = makeRequirement("REQ-A1", bId, 1);
            setField(active1, "status", Status.ACTIVE);
            var active2 = makeRequirement("REQ-A2", cId, 2);
            setField(active2, "status", Status.ACTIVE);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(draft1, active1, active2));
            when(auditService.getRecentRequirementChanges(Set.of(aId, bId, cId), 10))
                    .thenReturn(List.of());

            DashboardStats result = service.getDashboardStats(PROJECT_ID);

            assertThat(result.totalRequirements()).isEqualTo(3);
            assertThat(result.byStatus()).containsEntry("DRAFT", 1);
            assertThat(result.byStatus()).containsEntry("ACTIVE", 2);

            // Wave 1: 2 reqs (1 DRAFT, 1 ACTIVE); Wave 2: 1 req (1 ACTIVE)
            assertThat(result.byWave()).hasSize(2);
            assertThat(result.byWave().get(0).wave()).isEqualTo(1);
            assertThat(result.byWave().get(0).total()).isEqualTo(2);
            assertThat(result.byWave().get(1).wave()).isEqualTo(2);
            assertThat(result.byWave().get(1).total()).isEqualTo(1);
        }

        @Test
        void coverageComputed() {
            UUID reqId = UUID.randomUUID();
            var req = makeRequirement("REQ-COV", reqId);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(req));
            when(traceabilityLinkRepository.findRequirementIdsWithLinkType(anyCollection(), eq(LinkType.IMPLEMENTS)))
                    .thenReturn(Set.of(reqId));
            when(traceabilityLinkRepository.findRequirementIdsWithLinkType(anyCollection(), eq(LinkType.TESTS)))
                    .thenReturn(Set.of());
            when(traceabilityLinkRepository.findRequirementIdsWithLinkType(anyCollection(), eq(LinkType.DOCUMENTS)))
                    .thenReturn(Set.of());
            when(traceabilityLinkRepository.findRequirementIdsWithLinkType(anyCollection(), eq(LinkType.CONSTRAINS)))
                    .thenReturn(Set.of());
            when(traceabilityLinkRepository.findRequirementIdsWithLinkType(anyCollection(), eq(LinkType.VERIFIES)))
                    .thenReturn(Set.of());
            when(auditService.getRecentRequirementChanges(Set.of(reqId), 10))
                    .thenReturn(
                            List.of(new RecentChange("REQ-COV", "Title", "ADD", Instant.now(), "test-actor", null)));

            DashboardStats result = service.getDashboardStats(PROJECT_ID);

            CoverageStats implCoverage = result.coverageByLinkType().get("IMPLEMENTS");
            assertThat(implCoverage.total()).isEqualTo(1);
            assertThat(implCoverage.covered()).isEqualTo(1);
            assertThat(implCoverage.percentage()).isEqualTo(100.0);

            CoverageStats testsCoverage = result.coverageByLinkType().get("TESTS");
            assertThat(testsCoverage.covered()).isZero();
            assertThat(testsCoverage.percentage()).isEqualTo(0.0);

            assertThat(result.recentChanges()).hasSize(1);
        }
    }

    @Nested
    class GetWorkOrder {

        @Test
        void emptyProject_returnsEmptyResult() {
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of());
            when(relationRepository.findActiveByProjectAndRelationTypeIn(PROJECT_ID, DAG_TYPES))
                    .thenReturn(List.of());

            WorkOrderResult result = service.getWorkOrder(PROJECT_ID);

            assertThat(result.totalRequirements()).isZero();
            assertThat(result.totalUnblocked()).isZero();
            assertThat(result.totalBlocked()).isZero();
            assertThat(result.totalUnconstrained()).isZero();
            assertThat(result.waves()).isEmpty();
        }

        @Test
        void blockedRequirement_identifiedWithBlockers() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            var a = makeRequirement("REQ-A", aId, 1);
            var b = makeRequirement("REQ-B", bId, 1);
            // b is DRAFT (default), a depends on b -> a is BLOCKED
            var rel = new RequirementRelation(a, b, RelationType.DEPENDS_ON);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b));
            when(relationRepository.findActiveByProjectAndRelationTypeIn(PROJECT_ID, DAG_TYPES))
                    .thenReturn(List.of(rel));

            WorkOrderResult result = service.getWorkOrder(PROJECT_ID);

            assertThat(result.totalRequirements()).isEqualTo(2);
            assertThat(result.totalBlocked()).isEqualTo(1);
            assertThat(result.totalUnconstrained()).isEqualTo(1);

            WorkOrderWave wave = result.waves().get(0);
            WorkOrderItem blockedItem = wave.items().stream()
                    .filter(i -> i.uid().equals("REQ-A"))
                    .findFirst()
                    .orElseThrow();
            assertThat(blockedItem.blockingStatus()).isEqualTo(BlockingStatus.BLOCKED);
            assertThat(blockedItem.blockedBy()).containsExactly("REQ-B");

            WorkOrderItem unconstrainedItem = wave.items().stream()
                    .filter(i -> i.uid().equals("REQ-B"))
                    .findFirst()
                    .orElseThrow();
            assertThat(unconstrainedItem.blockingStatus()).isEqualTo(BlockingStatus.UNCONSTRAINED);
        }

        @Test
        void satisfiedDependency_isUnblocked() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            var a = makeRequirement("REQ-A", aId, 1);
            var b = makeRequirement("REQ-B", bId, 1);
            setField(b, "status", Status.ACTIVE);
            // a depends on b, b is ACTIVE -> a is UNBLOCKED
            var rel = new RequirementRelation(a, b, RelationType.DEPENDS_ON);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b));
            when(relationRepository.findActiveByProjectAndRelationTypeIn(PROJECT_ID, DAG_TYPES))
                    .thenReturn(List.of(rel));

            WorkOrderResult result = service.getWorkOrder(PROJECT_ID);

            assertThat(result.totalUnblocked()).isEqualTo(1);

            WorkOrderItem unblockedItem = result.waves().get(0).items().stream()
                    .filter(i -> i.uid().equals("REQ-A"))
                    .findFirst()
                    .orElseThrow();
            assertThat(unblockedItem.blockingStatus()).isEqualTo(BlockingStatus.UNBLOCKED);
            assertThat(unblockedItem.blockedBy()).isEmpty();
        }

        @Test
        void singleWave_sortedByDependencyThenPriority() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();
            // c (COULD) depends on a (MUST). b (SHOULD) is independent.
            var a = makeRequirement("REQ-A", aId, 1);
            a.setPriority(Priority.MUST);
            var b = makeRequirement("REQ-B", bId, 1);
            b.setPriority(Priority.SHOULD);
            var c = makeRequirement("REQ-C", cId, 1);
            c.setPriority(Priority.COULD);
            setField(a, "status", Status.ACTIVE);

            var rel = new RequirementRelation(c, a, RelationType.DEPENDS_ON);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b, c));
            when(relationRepository.findActiveByProjectAndRelationTypeIn(PROJECT_ID, DAG_TYPES))
                    .thenReturn(List.of(rel));

            WorkOrderResult result = service.getWorkOrder(PROJECT_ID);

            List<String> uids = result.waves().get(0).items().stream()
                    .map(WorkOrderItem::uid)
                    .toList();
            // a comes before c (dependency). b is independent with SHOULD priority.
            // Topo sort: a (MUST, no deps) and b (SHOULD, no deps) first, then c (COULD, depends on a)
            // Priority tie-breaking: a (MUST=0) before b (SHOULD=1)
            assertThat(uids).containsExactly("REQ-A", "REQ-B", "REQ-C");
        }

        @Test
        void multipleWaves_groupedSeparately() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            var a = makeRequirement("REQ-A", aId, 1);
            var b = makeRequirement("REQ-B", bId, 2);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b));
            when(relationRepository.findActiveByProjectAndRelationTypeIn(PROJECT_ID, DAG_TYPES))
                    .thenReturn(List.of());

            WorkOrderResult result = service.getWorkOrder(PROJECT_ID);

            assertThat(result.waves()).hasSize(2);
            assertThat(result.waves().get(0).wave()).isEqualTo(1);
            assertThat(result.waves().get(0).items()).hasSize(1);
            assertThat(result.waves().get(0).items().get(0).uid()).isEqualTo("REQ-A");
            assertThat(result.waves().get(1).wave()).isEqualTo(2);
            assertThat(result.waves().get(1).items()).hasSize(1);
            assertThat(result.waves().get(1).items().get(0).uid()).isEqualTo("REQ-B");
        }

        @Test
        void nullWave_sortedLast() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            var a = makeRequirement("REQ-A", aId, 1);
            var b = makeRequirement("REQ-B", bId); // null wave

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b));
            when(relationRepository.findActiveByProjectAndRelationTypeIn(PROJECT_ID, DAG_TYPES))
                    .thenReturn(List.of());

            WorkOrderResult result = service.getWorkOrder(PROJECT_ID);

            assertThat(result.waves()).hasSize(2);
            assertThat(result.waves().get(0).wave()).isEqualTo(1);
            assertThat(result.waves().get(1).wave()).isNull();
        }

        @Test
        void cycleParticipants_appendedSortedByPriority() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();
            // a and b form a cycle within wave 1; c is independent
            var a = makeRequirement("REQ-A", aId, 1);
            a.setPriority(Priority.SHOULD);
            var b = makeRequirement("REQ-B", bId, 1);
            b.setPriority(Priority.MUST);
            var c = makeRequirement("REQ-C", cId, 1);
            c.setPriority(Priority.COULD);

            // a -> b -> a (cycle)
            var ab = new RequirementRelation(a, b, RelationType.DEPENDS_ON);
            var ba = new RequirementRelation(b, a, RelationType.DEPENDS_ON);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b, c));
            when(relationRepository.findActiveByProjectAndRelationTypeIn(PROJECT_ID, DAG_TYPES))
                    .thenReturn(List.of(ab, ba));

            WorkOrderResult result = service.getWorkOrder(PROJECT_ID);

            List<String> uids = result.waves().get(0).items().stream()
                    .map(WorkOrderItem::uid)
                    .toList();
            // c (COULD, no deps) is topo-sorted first; a and b are cycle participants appended by priority
            // b (MUST=0) before a (SHOULD=1)
            assertThat(uids).containsExactly("REQ-C", "REQ-B", "REQ-A");
        }

        @Test
        void crossWaveDependency_excludedFromIntraWaveSort() {
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            // a in wave 1 depends on b in wave 2 (cross-wave edge)
            var a = makeRequirement("REQ-A", aId, 1);
            a.setPriority(Priority.MUST);
            var b = makeRequirement("REQ-B", bId, 2);
            b.setPriority(Priority.MUST);

            var rel = new RequirementRelation(a, b, RelationType.DEPENDS_ON);

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b));
            when(relationRepository.findActiveByProjectAndRelationTypeIn(PROJECT_ID, DAG_TYPES))
                    .thenReturn(List.of(rel));

            WorkOrderResult result = service.getWorkOrder(PROJECT_ID);

            // Both requirements appear, each in their own wave
            assertThat(result.waves()).hasSize(2);
            assertThat(result.waves().get(0).wave()).isEqualTo(1);
            assertThat(result.waves().get(0).items()).hasSize(1);
            assertThat(result.waves().get(0).items().get(0).uid()).isEqualTo("REQ-A");
            assertThat(result.waves().get(1).wave()).isEqualTo(2);
            assertThat(result.waves().get(1).items()).hasSize(1);
            assertThat(result.waves().get(1).items().get(0).uid()).isEqualTo("REQ-B");

            // a is BLOCKED (b is DRAFT), cross-wave edge doesn't break intra-wave sort
            assertThat(result.waves().get(0).items().get(0).blockingStatus()).isEqualTo(BlockingStatus.BLOCKED);
        }
    }

    @Nested
    class GetGraphVisualization {

        @Test
        void returnsNodesAndEdges() {
            var aId = UUID.randomUUID();
            var bId = UUID.randomUUID();
            var a = makeRequirement("REQ-A", aId, 1);
            var b = makeRequirement("REQ-B", bId, 2);
            var rel = new RequirementRelation(a, b, RelationType.DEPENDS_ON);
            setField(rel, "id", UUID.randomUUID());

            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of(a, b));
            when(relationRepository.findActiveWithSourceAndTargetByProjectId(PROJECT_ID))
                    .thenReturn(List.of(rel));

            var result = service.getGraphVisualization(PROJECT_ID);

            assertThat(result.nodes()).extracting(node -> node.uid()).containsExactly("REQ-A", "REQ-B");
            assertThat(result.edges()).extracting(edge -> edge.edgeType()).containsExactly("DEPENDS_ON");
        }

        @Test
        void emptyProject_returnsEmptyLists() {
            when(requirementRepository.findByProjectIdAndArchivedAtIsNull(PROJECT_ID))
                    .thenReturn(List.of());
            when(relationRepository.findActiveWithSourceAndTargetByProjectId(PROJECT_ID))
                    .thenReturn(List.of());

            var result = service.getGraphVisualization(PROJECT_ID);

            assertThat(result.nodes()).isEmpty();
            assertThat(result.edges()).isEmpty();
        }
    }
}
