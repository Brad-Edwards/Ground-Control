package com.keplerops.groundcontrol.unit.domain.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import com.keplerops.groundcontrol.domain.backlog.model.WsjfDistribution;
import com.keplerops.groundcontrol.domain.backlog.repository.BacklogItemRepository;
import com.keplerops.groundcontrol.domain.backlog.service.WsjfAnalysisService;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WsjfAnalysisServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000A01");

    private BacklogItemRepository repository;
    private WsjfAnalysisService service;
    private Project project;

    @BeforeEach
    void setUp() {
        repository = mock(BacklogItemRepository.class);
        service = new WsjfAnalysisService(repository);
        project = new Project("ground-control", "Ground Control");
        TestUtil.setField(project, "id", PROJECT_ID);
    }

    // ── computeForItem ───────────────────────────────────────────────────────

    @Test
    void computeForItemReturnsDistributionForFullyCalibrated() {
        var item = fullyCalibrated("BI-1");
        var itemId = UUID.randomUUID();
        TestUtil.setField(item, "id", itemId);
        when(repository.findByIdAndProjectId(itemId, PROJECT_ID)).thenReturn(Optional.of(item));

        var dist = service.computeForItem(PROJECT_ID, itemId, 42L, 100);

        assertThat(dist.iterations()).isEqualTo(100);
        assertThat(dist.samples()).hasSize(100);
        assertThat(dist.mean()).isPositive();
    }

    @Test
    void computeForItemThrowsNotFoundWhenItemAbsent() {
        var missingId = UUID.randomUUID();
        when(repository.findByIdAndProjectId(missingId, PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.computeForItem(PROJECT_ID, missingId, 0L, 100))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missingId.toString());
    }

    @Test
    void computeForItemThrowsValidationWhenComponentsMissing() {
        var item = new BacklogItem(project, "BI-INCOMPLETE", "Missing CoD");
        // Only set one component; leave the others null
        item.setUserBusinessValue(CostOfDelayComponent.point(5, "alice"));
        var itemId = UUID.randomUUID();
        TestUtil.setField(item, "id", itemId);
        when(repository.findByIdAndProjectId(itemId, PROJECT_ID)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.computeForItem(PROJECT_ID, itemId, 0L, 100))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("missing");
    }

    // ── computeForProject ───────────────────────────────────────────────────

    @Test
    void computeForProjectSkipsItemsWithMissingComponents() {
        var complete = fullyCalibrated("BI-1");
        var incomplete = new BacklogItem(project, "BI-2", "Incomplete");
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        TestUtil.setField(complete, "id", id1);
        TestUtil.setField(incomplete, "id", id2);
        when(repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(complete, incomplete));

        var result = service.computeForProject(PROJECT_ID, 0L, 100);

        // Only the fully-calibrated item should appear in the result.
        assertThat(result).hasSize(1);
        assertThat(result).containsKey(id1);
        assertThat(result).doesNotContainKey(id2);
    }

    @Test
    void computeForProjectReturnsEmptyMapWhenNoItemsHaveAllComponents() {
        var incomplete = new BacklogItem(project, "BI-1", "Incomplete");
        TestUtil.setField(incomplete, "id", UUID.randomUUID());
        when(repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(incomplete));

        var result = service.computeForProject(PROJECT_ID, 0L, 100);

        assertThat(result).isEmpty();
    }

    @Test
    void computeForProjectReturnsEmptyMapWhenNoItems() {
        when(repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());

        var result = service.computeForProject(PROJECT_ID, 0L, 100);

        assertThat(result).isEmpty();
    }

    @Test
    void computeForProjectIncludesAllFullyCalibratedItems() {
        var a = fullyCalibrated("BI-1");
        var b = fullyCalibrated("BI-2");
        var idA = UUID.randomUUID();
        var idB = UUID.randomUUID();
        TestUtil.setField(a, "id", idA);
        TestUtil.setField(b, "id", idB);
        when(repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(a, b));

        var result = service.computeForProject(PROJECT_ID, 7L, 200);

        assertThat(result).hasSize(2);
        assertThat(result.get(idA).iterations()).isEqualTo(200);
        assertThat(result.get(idB).iterations()).isEqualTo(200);
    }

    // ── rankingDelta ─────────────────────────────────────────────────────────

    @Test
    void rankingDeltaReturnsEmptyListWhenNoPreviousItemsMatch() {
        // Project now has items not in previous snapshot.
        var a = fullyCalibrated("BI-1");
        var idA = UUID.randomUUID();
        TestUtil.setField(a, "id", idA);
        when(repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(a));

        // Previous snapshot has a completely different id.
        var otherId = UUID.randomUUID();
        var v = CostOfDelayComponent.point(5, "x");
        var jd = CostOfDelayComponent.point(1, "x");
        Map<UUID, WsjfDistribution> previous = Map.of(otherId, WsjfDistribution.compute(v, v, v, jd, 0L, 50));

        var deltas = service.rankingDelta(PROJECT_ID, previous, 0L, 50);

        assertThat(deltas).isEmpty();
    }

    @Test
    void rankingDeltaReturnsCorrectDeltaWhenCommonItemsExist() {
        var a = fullyCalibrated("BI-1");
        var b = fullyCalibrated("BI-2");
        var idA = UUID.randomUUID();
        var idB = UUID.randomUUID();
        TestUtil.setField(a, "id", idA);
        TestUtil.setField(b, "id", idB);
        when(repository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(a, b));

        // Build a previous snapshot where idA and idB both have distributions.
        var v = CostOfDelayComponent.point(5, "x");
        var jd = CostOfDelayComponent.point(1, "x");
        var distA = WsjfDistribution.compute(v, v, v, jd, 1L, 100);
        var distB = WsjfDistribution.compute(v, v, v, jd, 2L, 100);
        Map<UUID, WsjfDistribution> previous = Map.of(idA, distA, idB, distB);

        var deltas = service.rankingDelta(PROJECT_ID, previous, 1L, 100);

        // Both items appear in the deltas.
        assertThat(deltas).hasSize(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private BacklogItem fullyCalibrated(String uid) {
        var item = new BacklogItem(project, uid, "Feature " + uid);
        item.setUserBusinessValue(CostOfDelayComponent.point(5, "alice"));
        item.setTimeCriticality(CostOfDelayComponent.point(3, "alice"));
        item.setRiskReductionOpportunityEnablement(CostOfDelayComponent.point(2, "alice"));
        item.setJobDuration(CostOfDelayComponent.point(2, "alice"));
        return item;
    }
}
