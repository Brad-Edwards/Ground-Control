package com.keplerops.groundcontrol.domain.decisions.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.decisions.model.DecisionAnalysisRecord;
import com.keplerops.groundcontrol.domain.decisions.repository.DecisionAnalysisRecordRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DecisionAnalysisRecordService {

    private static final Logger log = LoggerFactory.getLogger(DecisionAnalysisRecordService.class);

    private final DecisionAnalysisRecordRepository repository;
    private final ProjectService projectService;

    public DecisionAnalysisRecordService(DecisionAnalysisRecordRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    public DecisionAnalysisRecord create(CreateDecisionAnalysisRecordCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("DecisionAnalysisRecord with UID '" + command.uid()
                    + "' already exists in project " + project.getIdentifier());
        }
        var actor = ActorHolder.get();
        var dar = new DecisionAnalysisRecord(project, command.uid(), command.title(), command.modelName());
        dar.setSummary(command.summary());
        dar.setInputs(stampAttribution(command.inputs(), actor));
        dar.setSimulationParameters(command.simulationParameters());
        dar.setResults(command.results());
        dar.setAlternatives(command.alternatives());
        dar.setChosenAlternative(command.chosenAlternative());
        dar.setRationale(command.rationale());
        dar.setCreatedBy(actor);
        var saved = repository.save(dar);
        log.info(
                "decision_analysis_record_created: project={} uid={} model={} id={}",
                project.getIdentifier(),
                saved.getUid(),
                saved.getModelName(),
                saved.getId());
        return saved;
    }

    public DecisionAnalysisRecord update(UUID projectId, UUID id, UpdateDecisionAnalysisRecordCommand command) {
        var dar = findOrThrow(projectId, id);
        var actor = ActorHolder.get();
        if (command.title() != null) {
            dar.setTitle(command.title());
        }
        if (command.modelName() != null) {
            dar.setModelName(command.modelName());
        }
        if (command.summary() != null) {
            dar.setSummary(command.summary());
        }
        if (command.inputs() != null) {
            dar.setInputs(stampAttribution(command.inputs(), actor));
        }
        if (command.simulationParameters() != null) {
            dar.setSimulationParameters(command.simulationParameters());
        }
        if (command.results() != null) {
            dar.setResults(command.results());
        }
        if (command.alternatives() != null) {
            dar.setAlternatives(command.alternatives());
        }
        if (command.chosenAlternative() != null) {
            dar.setChosenAlternative(command.chosenAlternative());
        }
        if (command.rationale() != null) {
            dar.setRationale(command.rationale());
        }
        var saved = repository.save(dar);
        log.info("decision_analysis_record_updated: id={} uid={}", saved.getId(), saved.getUid());
        return saved;
    }

    public void delete(UUID projectId, UUID id) {
        var dar = findOrThrow(projectId, id);
        repository.delete(dar);
        log.info("decision_analysis_record_deleted: id={} uid={}", dar.getId(), dar.getUid());
    }

    @Transactional(readOnly = true)
    public DecisionAnalysisRecord getById(UUID projectId, UUID id) {
        return findOrThrow(projectId, id);
    }

    @Transactional(readOnly = true)
    public DecisionAnalysisRecord getByUid(UUID projectId, String uid) {
        return repository
                .findByProjectIdAndUid(projectId, uid)
                .orElseThrow(() -> new NotFoundException("DecisionAnalysisRecord not found: " + uid));
    }

    @Transactional(readOnly = true)
    public List<DecisionAnalysisRecord> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    private DecisionAnalysisRecord findOrThrow(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("DecisionAnalysisRecord not found: " + id));
    }

    /**
     * Reserved input-map key that names the actor who supplied the decision
     * inputs. The wire format is free-form {@code Map<String, Object>} for
     * forward-compat with new model knobs, but the audit story requires a
     * stable, server-controlled attribution slot — without it an authenticated
     * caller could record inputs and later claim they came from a different
     * estimator. Keep it package-visible for tests.
     */
    static final String ATTRIBUTED_TO_KEY = "_attributedTo";

    /**
     * Stamp the authenticated actor onto the decision-analysis inputs map.
     * Any caller-supplied value at {@link #ATTRIBUTED_TO_KEY} is dropped and
     * replaced with the ActorHolder principal so the Envers audit trail can
     * never persist a client-controlled attribution string. Per-input
     * attribution inside the map values is left to the model layer to validate
     * — those are arbitrary decision-model knobs whose schema is
     * model-specific.
     */
    private static Map<String, Object> stampAttribution(Map<String, Object> inputs, String actor) {
        var copy = new LinkedHashMap<String, Object>(inputs == null ? Map.of() : inputs);
        copy.put(ATTRIBUTED_TO_KEY, actor);
        return copy;
    }
}
