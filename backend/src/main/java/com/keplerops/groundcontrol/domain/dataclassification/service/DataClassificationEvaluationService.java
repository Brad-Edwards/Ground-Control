package com.keplerops.groundcontrol.domain.dataclassification.service;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementStateRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelSnapshotRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationFindingReason;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates the data flows of an architecture-model snapshot against a project's data classification
 * lattice (GC-GRC-006 clause c). A flow whose (source-label, sink-label) pair is absent from the
 * permitted-flow relation is a violation, derived deterministically with no LLM judgment. Missing or
 * unknown labels and dangling endpoints surface as explicit limitations rather than passing
 * silently. Evaluation is read-only: the violation IS the derivable finding, reproducible from the
 * stored policy and the snapshot's stored label assignments.
 */
@Service
public class DataClassificationEvaluationService {

    static final String SCHEMA_VERSION = "data-classification-evaluation/v1";

    private static final Logger log = LoggerFactory.getLogger(DataClassificationEvaluationService.class);

    private final DataClassificationLatticeService latticeService;
    private final ArchitectureModelSnapshotRepository snapshotRepository;
    private final ArchitectureModelElementStateRepository stateRepository;

    public DataClassificationEvaluationService(
            DataClassificationLatticeService latticeService,
            ArchitectureModelSnapshotRepository snapshotRepository,
            ArchitectureModelElementStateRepository stateRepository) {
        this.latticeService = latticeService;
        this.snapshotRepository = snapshotRepository;
        this.stateRepository = stateRepository;
    }

    /** Evaluate the project's most recent architecture-model snapshot, if any. */
    @Transactional(readOnly = true)
    public DataClassificationEvaluationResult evaluateLatest(UUID projectId) {
        var definition = latticeService.resolveActiveDefinition(projectId);
        var snapshot = snapshotRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .findFirst();
        if (snapshot.isEmpty()) {
            return empty(definition);
        }
        return evaluateSnapshotEntity(definition, snapshot.get());
    }

    /** Evaluate a specific architecture-model snapshot, scoped to the project. */
    @Transactional(readOnly = true)
    public DataClassificationEvaluationResult evaluateSnapshot(UUID projectId, UUID snapshotId) {
        var definition = latticeService.resolveActiveDefinition(projectId);
        var snapshot = snapshotRepository
                .findByIdAndProjectId(snapshotId, projectId)
                .orElseThrow(() -> new NotFoundException("Architecture model snapshot not found: " + snapshotId));
        return evaluateSnapshotEntity(definition, snapshot);
    }

    private DataClassificationEvaluationResult evaluateSnapshotEntity(
            DataClassificationLatticeDefinition definition, ArchitectureModelSnapshot snapshot) {
        var views = stateRepository.findBySnapshotIdOrderByStableKey(snapshot.getId()).stream()
                .map(DataClassificationEvaluationService::toView)
                .toList();
        var result = evaluate(definition, snapshot.getModelVersion(), snapshot.getId(), views);
        log.info(
                "data_classification_evaluated: snapshot={} modelVersion={} policyVersion={} flows={} violations={} limitations={}",
                snapshot.getId(),
                result.modelVersion(),
                result.policyVersion(),
                result.evaluatedFlowCount(),
                result.violations().size(),
                result.limitations().size());
        return result;
    }

    private DataClassificationEvaluationResult empty(DataClassificationLatticeDefinition definition) {
        return new DataClassificationEvaluationResult(
                SCHEMA_VERSION, definition.policyVersion(), definition.source(), null, null, 0, List.of(), List.of());
    }

    private static DataClassificationElementView toView(ArchitectureModelElementState state) {
        return new DataClassificationElementView(
                state.getStableKey(),
                state.getElementKind(),
                state.getDataClassificationKey(),
                state.getFlowSourceStableKey(),
                state.getFlowTargetStableKey(),
                state.getFlowDirection());
    }

    /**
     * Pure lattice evaluation over a snapshot's element views. Deterministic and side-effect free so
     * it is exercised directly by unit tests. For each {@code DATA_FLOW} element it resolves the
     * source and sink labels and checks the permitted-flow relation in each carried direction.
     */
    public static DataClassificationEvaluationResult evaluate(
            DataClassificationLatticeDefinition definition,
            String modelVersion,
            UUID snapshotId,
            List<DataClassificationElementView> views) {
        Map<String, DataClassificationElementView> byKey = new LinkedHashMap<>();
        for (var view : views) {
            byKey.putIfAbsent(view.stableKey(), view);
        }
        Set<String> labels = definition.labelKeys();
        List<DataClassificationFinding> violations = new ArrayList<>();
        List<DataClassificationFinding> limitations = new ArrayList<>();
        int evaluatedFlows = 0;
        for (var flow : views) {
            if (flow.elementKind() != ArchitectureModelElementKind.DATA_FLOW) {
                continue;
            }
            evaluatedFlows++;
            var source = byKey.get(flow.flowSourceStableKey());
            var target = byKey.get(flow.flowTargetStableKey());
            if (source == null || target == null) {
                limitations.add(new DataClassificationFinding(
                        flow.stableKey(),
                        flow.flowSourceStableKey(),
                        flow.flowTargetStableKey(),
                        null,
                        null,
                        DataClassificationFindingReason.DANGLING_FLOW_ENDPOINT,
                        "Flow endpoint not present in the evaluated snapshot"));
                continue;
            }
            checkDirected(definition, labels, flow, source, target, violations, limitations);
            if (flow.flowDirection() == ArchitectureFlowDirection.BIDIRECTIONAL) {
                checkDirected(definition, labels, flow, target, source, violations, limitations);
            }
        }
        return new DataClassificationEvaluationResult(
                SCHEMA_VERSION,
                definition.policyVersion(),
                definition.source(),
                modelVersion,
                snapshotId,
                evaluatedFlows,
                violations,
                limitations);
    }

    private static void checkDirected(
            DataClassificationLatticeDefinition definition,
            Set<String> labels,
            DataClassificationElementView flow,
            DataClassificationElementView source,
            DataClassificationElementView sink,
            List<DataClassificationFinding> violations,
            List<DataClassificationFinding> limitations) {
        var sourceLabel = trimToNull(source.dataClassificationKey());
        var sinkLabel = trimToNull(sink.dataClassificationKey());
        if (sourceLabel == null) {
            limitations.add(finding(
                    flow,
                    source,
                    sink,
                    null,
                    sinkLabel,
                    DataClassificationFindingReason.MISSING_SOURCE_LABEL,
                    "Flow source has no data classification label"));
            return;
        }
        if (sinkLabel == null) {
            limitations.add(finding(
                    flow,
                    source,
                    sink,
                    sourceLabel,
                    null,
                    DataClassificationFindingReason.MISSING_SINK_LABEL,
                    "Flow sink has no data classification label"));
            return;
        }
        if (!labels.contains(sourceLabel)) {
            limitations.add(finding(
                    flow,
                    source,
                    sink,
                    sourceLabel,
                    sinkLabel,
                    DataClassificationFindingReason.UNKNOWN_SOURCE_LABEL,
                    "Source label is not defined in the active lattice"));
            return;
        }
        if (!labels.contains(sinkLabel)) {
            limitations.add(finding(
                    flow,
                    source,
                    sink,
                    sourceLabel,
                    sinkLabel,
                    DataClassificationFindingReason.UNKNOWN_SINK_LABEL,
                    "Sink label is not defined in the active lattice"));
            return;
        }
        if (!definition.permits(sourceLabel, sinkLabel)) {
            violations.add(finding(
                    flow,
                    source,
                    sink,
                    sourceLabel,
                    sinkLabel,
                    DataClassificationFindingReason.LABEL_FLOW_NOT_PERMITTED,
                    "Flow from " + sourceLabel + " to " + sinkLabel + " is not a permitted label flow"));
        }
    }

    private static DataClassificationFinding finding(
            DataClassificationElementView flow,
            DataClassificationElementView source,
            DataClassificationElementView sink,
            String sourceLabel,
            String sinkLabel,
            DataClassificationFindingReason reason,
            String detail) {
        return new DataClassificationFinding(
                flow.stableKey(), source.stableKey(), sink.stableKey(), sourceLabel, sinkLabel, reason, detail);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
