package com.keplerops.groundcontrol.domain.dataclassification.service;

import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationFlowRule;
import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLabel;
import com.keplerops.groundcontrol.domain.dataclassification.model.DataClassificationLattice;
import com.keplerops.groundcontrol.domain.dataclassification.repository.DataClassificationFlowRuleRepository;
import com.keplerops.groundcontrol.domain.dataclassification.repository.DataClassificationLabelRepository;
import com.keplerops.groundcontrol.domain.dataclassification.repository.DataClassificationLatticeRepository;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeDefinition.Edge;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeDefinition.Label;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the project-scoped data classification lattice aggregate (GC-GRC-006): resolving the active
 * policy (custom row or shipped default), replacing it under soundness validation, and reverting to
 * default. The persisted permitted-flow relation is the closure computed by
 * {@link DataClassificationLatticeFactory}, so evaluation is a pure lookup. Writes are restricted to
 * ROLE_ADMIN at the API boundary because tampering with the policy would silently defeat the
 * deterministic leak detection (GC-TM-010).
 */
@Service
public class DataClassificationLatticeService {

    private static final Logger log = LoggerFactory.getLogger(DataClassificationLatticeService.class);

    private final DataClassificationLatticeRepository latticeRepository;
    private final DataClassificationLabelRepository labelRepository;
    private final DataClassificationFlowRuleRepository flowRuleRepository;
    private final ProjectService projectService;

    public DataClassificationLatticeService(
            DataClassificationLatticeRepository latticeRepository,
            DataClassificationLabelRepository labelRepository,
            DataClassificationFlowRuleRepository flowRuleRepository,
            ProjectService projectService) {
        this.latticeRepository = latticeRepository;
        this.labelRepository = labelRepository;
        this.flowRuleRepository = flowRuleRepository;
        this.projectService = projectService;
    }

    /** The active lattice for a project: the stored custom policy, or the shipped default. */
    @Transactional(readOnly = true)
    public DataClassificationLatticeDefinition resolveActiveDefinition(UUID projectId) {
        var root = latticeRepository.findByProjectId(projectId);
        if (root.isEmpty()) {
            return DefaultDataClassificationLattice.definition();
        }
        var labels = labelRepository.findByLatticeIdOrderByLabelKey(root.get().getId()).stream()
                .map(label ->
                        new Label(label.getLabelKey(), label.getDisplayName(), label.getDescription(), label.getRank()))
                .toList();
        var edges = flowRuleRepository
                .findByLatticeIdOrderByFromLabelKeyAscToLabelKeyAsc(root.get().getId())
                .stream()
                .map(rule -> new Edge(rule.getFromLabelKey(), rule.getToLabelKey()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new DataClassificationLatticeDefinition(
                root.get().getSource(), root.get().getPolicyVersion(), labels, edges);
    }

    /**
     * Replace a project's lattice with a validated custom taxonomy + permitted-flow policy. The
     * existing policy (if any) is removed entity-by-entity so Envers records the change, then the
     * new labels and closed flow relation are persisted with a fresh policy version.
     */
    @Transactional
    public DataClassificationLatticeDefinition replace(UUID projectId, DataClassificationLatticeCommand command) {
        var project = projectService.getById(projectId);
        var definition = DataClassificationLatticeFactory.build(DataClassificationSource.CUSTOM, command);
        removeExisting(projectId);
        latticeRepository.flush();
        var root = latticeRepository.save(new DataClassificationLattice(
                project,
                definition.policyVersion(),
                DataClassificationSource.CUSTOM,
                definition.labelCount(),
                definition.edgeCount()));
        for (Label label : definition.labels()) {
            labelRepository.save(new DataClassificationLabel(
                    project, root, label.key(), label.displayName(), label.description(), label.rank()));
        }
        for (Edge edge : DataClassificationLatticeFactory.sortedEdges(definition.permittedFlows())) {
            flowRuleRepository.save(new DataClassificationFlowRule(project, root, edge.from(), edge.to()));
        }
        log.info(
                "data_classification_lattice_replaced: project={} policyVersion={} labels={} edges={}",
                project.getIdentifier(),
                definition.policyVersion(),
                definition.labelCount(),
                definition.edgeCount());
        return definition;
    }

    /** Remove any custom lattice for the project, reverting it to the shipped default. Idempotent. */
    @Transactional
    public DataClassificationLatticeDefinition resetToDefault(UUID projectId) {
        Project project = projectService.getById(projectId);
        boolean removed = latticeRepository.findByProjectId(projectId).isPresent();
        removeExisting(projectId);
        if (removed) {
            log.info("data_classification_lattice_reset_to_default: project={}", project.getIdentifier());
        }
        return DefaultDataClassificationLattice.definition();
    }

    private void removeExisting(UUID projectId) {
        flowRuleRepository.deleteAll(flowRuleRepository.findByProjectId(projectId));
        labelRepository.deleteAll(labelRepository.findByProjectId(projectId));
        latticeRepository.findByProjectId(projectId).ifPresent(latticeRepository::delete);
    }
}
