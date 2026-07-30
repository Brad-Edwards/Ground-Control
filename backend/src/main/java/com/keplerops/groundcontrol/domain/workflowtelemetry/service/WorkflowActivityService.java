package com.keplerops.groundcontrol.domain.workflowtelemetry.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowGateFindingRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded, read-only activity projection over the existing ADR-061 aggregates (issue #1437).
 *
 * <p>The repositories reduce the event history to one deterministic lifecycle transition, routing
 * observation, and latest attempt per station in the database. This service joins those batches;
 * it never performs one query per run and never treats ADR-036 step economics as lifecycle state.
 */
@Service
public class WorkflowActivityService {

    private static final EnumSet<WorkflowRunState> OPEN_STATES =
            EnumSet.of(WorkflowRunState.RUNNING, WorkflowRunState.READY_FOR_REVIEW, WorkflowRunState.ESCALATED);

    private static final EnumSet<WorkflowRunState> TERMINAL_STATES = EnumSet.complementOf(OPEN_STATES);

    private final WorkflowRunRepository runRepository;
    private final WorkflowPhaseEventRepository phaseEventRepository;
    private final WorkflowGateFindingRepository findingRepository;
    private final StationCatalog stationCatalog;
    private final WorkflowActivityProperties properties;
    private final Supplier<Instant> now;

    @Autowired
    public WorkflowActivityService(
            WorkflowRunRepository runRepository,
            WorkflowPhaseEventRepository phaseEventRepository,
            WorkflowGateFindingRepository findingRepository,
            StationCatalog stationCatalog,
            WorkflowActivityProperties properties) {
        this(runRepository, phaseEventRepository, findingRepository, stationCatalog, properties, Instant::now);
    }

    /** Clock seam for deterministic threshold and {@code asOf} tests. */
    public WorkflowActivityService(
            WorkflowRunRepository runRepository,
            WorkflowPhaseEventRepository phaseEventRepository,
            WorkflowGateFindingRepository findingRepository,
            StationCatalog stationCatalog,
            WorkflowActivityProperties properties,
            Supplier<Instant> now) {
        this.runRepository = runRepository;
        this.phaseEventRepository = phaseEventRepository;
        this.findingRepository = findingRepository;
        this.stationCatalog = stationCatalog;
        this.properties = properties;
        this.now = now;
    }

    @Transactional(readOnly = true)
    public WorkflowActivitySnapshot snapshot(String project) {
        if (project == null || project.isBlank()) {
            throw new DomainValidationException("project must not be blank");
        }

        long openTotal = runRepository.countByProjectAndFinalStateIn(project, OPEN_STATES);
        var openRuns = runRepository.findByProjectAndFinalStateInOrderByCreatedAtDesc(
                project, OPEN_STATES, PageRequest.of(0, properties.getMaxOpenRuns()));
        var recent = runRepository.findRecentTerminalRuns(
                project, TERMINAL_STATES, PageRequest.of(0, properties.getRecentRuns()));

        var asOf = now.get();
        if (openRuns.isEmpty()) {
            return new WorkflowActivitySnapshot(asOf, openTotal, openTotal > 0, List.of(), List.copyOf(recent));
        }

        var runIds = openRuns.stream().map(WorkflowRun::getId).toList();
        var currentByRun = byRun(phaseEventRepository.findLatestLifecycleEvents(project, runIds));
        var routingByRun = byRun(phaseEventRepository.findLatestRoutingObservations(project, runIds));
        var gates = phaseEventRepository.findLatestStationAttempts(project, runIds);
        var findingCounts = findingCounts(project, gates);
        var gatesByRun = gateRows(gates, findingCounts);

        var rows = openRuns.stream()
                .map(run -> openRun(run, currentByRun.get(run.getId()), routingByRun.get(run.getId()), gatesByRun))
                .toList();
        return new WorkflowActivitySnapshot(asOf, openTotal, openTotal > openRuns.size(), rows, List.copyOf(recent));
    }

    private WorkflowActivitySnapshot.OpenRun openRun(
            WorkflowRun run,
            WorkflowPhaseEvent current,
            WorkflowPhaseEvent routing,
            Map<UUID, List<WorkflowActivitySnapshot.GateAttempt>> gatesByRun) {
        var currentPhase = current == null ? null : current.getPhase();
        return new WorkflowActivitySnapshot.OpenRun(
                run,
                currentPhase,
                currentPhase == null ? null : stationCatalog.displayNameForPhase(currentPhase),
                current == null ? null : current.getOccurredAt(),
                current == null ? null : current.getCycleIndex(),
                properties.getStallThreshold(),
                routing == null ? null : routingObservation(routing),
                completeGateRows(gatesByRun.getOrDefault(run.getId(), List.of())));
    }

    private List<WorkflowActivitySnapshot.GateAttempt> completeGateRows(
            List<WorkflowActivitySnapshot.GateAttempt> observed) {
        var byStation = observed.stream()
                .collect(Collectors.toMap(WorkflowActivitySnapshot.GateAttempt::stationId, gate -> gate));
        var complete = new LinkedHashMap<String, WorkflowActivitySnapshot.GateAttempt>();
        for (var stationId : stationCatalog.stationOrder()) {
            complete.put(
                    stationId,
                    byStation.getOrDefault(
                            stationId,
                            new WorkflowActivitySnapshot.GateAttempt(
                                    stationId,
                                    stationCatalog.stationTitle(stationId),
                                    null,
                                    StationResult.UNOBSERVED,
                                    null,
                                    null,
                                    null,
                                    0,
                                    0)));
        }
        // Historical data can name a formerly catalogued station. Keep it explicit after the
        // current catalogue rather than silently dropping a durable attempt.
        for (var gate : observed) {
            complete.putIfAbsent(gate.stationId(), gate);
        }
        return List.copyOf(complete.values());
    }

    private static WorkflowActivitySnapshot.RoutingObservation routingObservation(WorkflowPhaseEvent event) {
        return new WorkflowActivitySnapshot.RoutingObservation(
                event.getPhase(),
                event.getStepAlias(),
                event.getTier(),
                event.getModel(),
                event.getExpectedModel(),
                event.getModelMatchesExpected(),
                event.getOccurredAt());
    }

    private Map<UUID, List<WorkflowActivitySnapshot.GateAttempt>> gateRows(
            List<WorkflowPhaseEvent> events, Map<UUID, Long> findingCounts) {
        var stationRank = new HashMap<String, Integer>();
        var order = stationCatalog.stationOrder();
        for (int index = 0; index < order.size(); index++) {
            stationRank.put(order.get(index), index);
        }
        Comparator<WorkflowActivitySnapshot.GateAttempt> byCatalogue = Comparator.comparingInt(
                        (WorkflowActivitySnapshot.GateAttempt gate) ->
                                stationRank.getOrDefault(gate.stationId(), Integer.MAX_VALUE))
                .thenComparing(WorkflowActivitySnapshot.GateAttempt::stationId);

        return events.stream()
                .map(event -> new java.util.AbstractMap.SimpleImmutableEntry<>(
                        event.getRunId(),
                        new WorkflowActivitySnapshot.GateAttempt(
                                event.getStationId(),
                                stationCatalog.stationTitle(event.getStationId()),
                                event.getEventType(),
                                event.getStationResult(),
                                event.getCycleIndex(),
                                event.getOccurredAt(),
                                event.getDurationMs(),
                                findingCounts.getOrDefault(event.getId(), 0L),
                                event.getFindingsDropped())))
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.collectingAndThen(
                                Collectors.mapping(Map.Entry::getValue, Collectors.toCollection(ArrayList::new)),
                                values -> {
                                    values.sort(byCatalogue);
                                    return List.copyOf(values);
                                })));
    }

    private Map<UUID, Long> findingCounts(String project, List<WorkflowPhaseEvent> gates) {
        if (gates.isEmpty()) {
            return Map.of();
        }
        var eventIds = gates.stream().map(WorkflowPhaseEvent::getId).toList();
        return findingRepository.countByPhaseEventIds(project, eventIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        WorkflowGateFindingRepository.PhaseEventFindingCount::getPhaseEventId,
                        WorkflowGateFindingRepository.PhaseEventFindingCount::getFindingCount));
    }

    private static Map<UUID, WorkflowPhaseEvent> byRun(Collection<WorkflowPhaseEvent> events) {
        return events.stream().collect(Collectors.toUnmodifiableMap(WorkflowPhaseEvent::getRunId, event -> event));
    }
}
