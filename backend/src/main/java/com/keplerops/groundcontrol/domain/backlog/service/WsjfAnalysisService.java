package com.keplerops.groundcontrol.domain.backlog.service;

import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import com.keplerops.groundcontrol.domain.backlog.model.WsjfDistribution;
import com.keplerops.groundcontrol.domain.backlog.repository.BacklogItemRepository;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes WSJF distributions on demand for backlog items and emits
 * re-prioritization analyses comparing two ranking snapshots.
 *
 * <p>Sampling is deterministic per {@code (item, seed, iterations)} so the
 * analysis is reproducible — a stakeholder can re-run the same computation
 * from the persisted CoD inputs and confirm the result.
 */
@Service
@Transactional(readOnly = true)
public class WsjfAnalysisService {

    /** Default iteration count balances signal vs. test/runtime overhead. */
    public static final int DEFAULT_ITERATIONS = 10_000;

    private final BacklogItemRepository repository;

    public WsjfAnalysisService(BacklogItemRepository repository) {
        this.repository = repository;
    }

    /** Compute a single WSJF distribution from a persisted backlog item. */
    public WsjfDistribution computeForItem(UUID projectId, UUID itemId, long seed, int iterations) {
        var item = repository
                .findByIdAndProjectId(itemId, projectId)
                .orElseThrow(() -> new NotFoundException("BacklogItem not found: " + itemId));
        if (!item.hasAllComponents()) {
            throw new DomainValidationException(
                    "BacklogItem " + item.getUid() + " is missing one or more CoD components",
                    "validation_error",
                    Map.of("uid", item.getUid()));
        }
        return WsjfDistribution.compute(
                item.getUserBusinessValue(),
                item.getTimeCriticality(),
                item.getRiskReductionOpportunityEnablement(),
                item.getJobDuration(),
                seed,
                iterations);
    }

    /** Compute distributions for every fully-calibrated backlog item in a project. */
    public Map<UUID, WsjfDistribution> computeForProject(UUID projectId, long seed, int iterations) {
        var items = repository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Map<UUID, WsjfDistribution> out = new LinkedHashMap<>();
        for (BacklogItem item : items) {
            if (!item.hasAllComponents()) {
                continue;
            }
            out.put(
                    item.getId(),
                    WsjfDistribution.compute(
                            item.getUserBusinessValue(),
                            item.getTimeCriticality(),
                            item.getRiskReductionOpportunityEnablement(),
                            item.getJobDuration(),
                            seed,
                            iterations));
        }
        return out;
    }

    /**
     * Re-prioritization analysis: emits per-item rank deltas given a snapshot
     * map carrying the previous distributions keyed by item id. Items that no
     * longer carry full components are skipped from both sides.
     */
    public List<WsjfDistribution.RankDelta> rankingDelta(
            UUID projectId, Map<UUID, WsjfDistribution> previous, long seed, int iterations) {
        var current = computeForProject(projectId, seed, iterations);
        var common = new ArrayList<UUID>();
        for (UUID id : current.keySet()) {
            if (previous.containsKey(id)) {
                common.add(id);
            }
        }
        if (common.isEmpty()) {
            return List.of();
        }
        List<WsjfDistribution> prevList = new ArrayList<>(common.size());
        List<WsjfDistribution> currList = new ArrayList<>(common.size());
        for (UUID id : common) {
            prevList.add(previous.get(id));
            currList.add(current.get(id));
        }
        return WsjfDistribution.rankingDelta(common, prevList, common, currList);
    }
}
