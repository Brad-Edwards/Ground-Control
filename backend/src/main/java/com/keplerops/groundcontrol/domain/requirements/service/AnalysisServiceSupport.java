package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.state.Priority;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stateless helpers split out of {@link AnalysisService} under issue #1467
 * for the 500-LOC limit (docs/CODING_STANDARDS.md).
 *
 * Every method here touches no instance state, so it is static and the
 * original keeps a static import for each -- call sites are unchanged.
 */
final class AnalysisServiceSupport {

    private AnalysisServiceSupport() {}

    static final Map<Priority, Integer> PRIORITY_ORDER =
            Map.of(Priority.MUST, 0, Priority.SHOULD, 1, Priority.COULD, 2, Priority.WONT, 3);

    static List<UUID> topoSortWave(List<Requirement> waveReqs, Map<UUID, List<UUID>> dependsOn) {
        Set<UUID> waveIds = new HashSet<>();
        for (Requirement req : waveReqs) {
            waveIds.add(req.getId());
        }

        Map<UUID, List<UUID>> waveDeps = new HashMap<>();
        for (Requirement req : waveReqs) {
            UUID id = req.getId();
            List<UUID> deps = dependsOn.getOrDefault(id, List.of());
            List<UUID> intraWaveDeps = new ArrayList<>();
            for (UUID dep : deps) {
                if (waveIds.contains(dep)) {
                    intraWaveDeps.add(dep);
                }
            }
            waveDeps.put(id, intraWaveDeps);
        }

        Map<UUID, Priority> priorityMap = new HashMap<>();
        for (Requirement req : waveReqs) {
            priorityMap.put(req.getId(), req.getPriority());
        }
        Comparator<UUID> tieBreaker = Comparator.comparingInt(
                id -> PRIORITY_ORDER.getOrDefault(priorityMap.getOrDefault(id, Priority.WONT), 3));

        List<UUID> sorted = GraphAlgorithms.topologicalSort(waveDeps, tieBreaker);

        Set<UUID> sortedSet = new HashSet<>(sorted);
        List<UUID> remaining = new ArrayList<>();
        for (Requirement req : waveReqs) {
            if (!sortedSet.contains(req.getId())) {
                remaining.add(req.getId());
            }
        }
        remaining.sort(tieBreaker);
        sorted.addAll(remaining);
        return sorted;
    }
}
