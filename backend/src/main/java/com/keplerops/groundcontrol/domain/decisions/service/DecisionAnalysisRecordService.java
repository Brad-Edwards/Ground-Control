package com.keplerops.groundcontrol.domain.decisions.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.decisions.model.DecisionAnalysisRecord;
import com.keplerops.groundcontrol.domain.decisions.repository.DecisionAnalysisRecordRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.util.List;
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
        var record = new DecisionAnalysisRecord(project, command.uid(), command.title(), command.modelName());
        record.setSummary(command.summary());
        record.setInputs(command.inputs());
        record.setSimulationParameters(command.simulationParameters());
        record.setResults(command.results());
        record.setAlternatives(command.alternatives());
        record.setChosenAlternative(command.chosenAlternative());
        record.setRationale(command.rationale());
        record.setCreatedBy(ActorHolder.get());
        var saved = repository.save(record);
        log.info(
                "decision_analysis_record_created: project={} uid={} model={} id={}",
                project.getIdentifier(),
                saved.getUid(),
                saved.getModelName(),
                saved.getId());
        return saved;
    }

    public DecisionAnalysisRecord update(UUID projectId, UUID id, UpdateDecisionAnalysisRecordCommand command) {
        var record = findOrThrow(projectId, id);
        if (command.title() != null) {
            record.setTitle(command.title());
        }
        if (command.modelName() != null) {
            record.setModelName(command.modelName());
        }
        if (command.summary() != null) {
            record.setSummary(command.summary());
        }
        if (command.inputs() != null) {
            record.setInputs(command.inputs());
        }
        if (command.simulationParameters() != null) {
            record.setSimulationParameters(command.simulationParameters());
        }
        if (command.results() != null) {
            record.setResults(command.results());
        }
        if (command.alternatives() != null) {
            record.setAlternatives(command.alternatives());
        }
        if (command.chosenAlternative() != null) {
            record.setChosenAlternative(command.chosenAlternative());
        }
        if (command.rationale() != null) {
            record.setRationale(command.rationale());
        }
        var saved = repository.save(record);
        log.info("decision_analysis_record_updated: id={} uid={}", saved.getId(), saved.getUid());
        return saved;
    }

    public void delete(UUID projectId, UUID id) {
        var record = findOrThrow(projectId, id);
        repository.delete(record);
        log.info("decision_analysis_record_deleted: id={} uid={}", record.getId(), record.getUid());
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
}
