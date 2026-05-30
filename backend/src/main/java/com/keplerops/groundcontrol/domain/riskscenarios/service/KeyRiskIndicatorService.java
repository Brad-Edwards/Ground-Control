package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.events.KriBreachedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSignal;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSourceEntityType;
import com.keplerops.groundcontrol.domain.riskscenarios.model.KeyRiskIndicator;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.KeyRiskIndicatorRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.KriThresholdBand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T007: Key Risk Indicator service.
 *
 * <p>{@link #recordMeasurement} classifies the new value via
 * {@link KeyRiskIndicator#recordMeasurement} and, when the resulting band is
 * RED, publishes a {@link KriBreachedEvent} synchronously. The event is
 * consumed by {@code ReassessmentSignalService} under the same transaction —
 * a listener failure rolls back the measurement write so a breach can never
 * silently miss its reassessment signal (per the shared event contract
 * documented in cluster cross-cutting decisions).
 */
@Service
@Transactional
public class KeyRiskIndicatorService {

    private static final String FIELD_BAND = "band";
    private static final String FIELD_VALUE = "value";

    private final KeyRiskIndicatorRepository repository;
    private final ProjectService projectService;
    private final RiskRegisterRecordRepository riskRegisterRecordRepository;
    private final RiskScenarioRepository riskScenarioRepository;
    private final ApplicationEventPublisher eventPublisher;

    public KeyRiskIndicatorService(
            KeyRiskIndicatorRepository repository,
            ProjectService projectService,
            RiskRegisterRecordRepository riskRegisterRecordRepository,
            RiskScenarioRepository riskScenarioRepository,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.projectService = projectService;
        this.riskRegisterRecordRepository = riskRegisterRecordRepository;
        this.riskScenarioRepository = riskScenarioRepository;
        this.eventPublisher = eventPublisher;
    }

    public KeyRiskIndicator create(CreateKeyRiskIndicatorCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("Key risk indicator with UID " + command.uid() + " already exists");
        }
        var kri = new KeyRiskIndicator(project, command.uid(), command.name());
        applyUpdates(
                kri,
                project.getId(),
                command.description(),
                command.metricUnit(),
                command.yellowThreshold(),
                command.redThreshold(),
                command.direction(),
                command.owner(),
                command.riskRegisterRecordId(),
                command.riskScenarioId());
        return repository.save(kri);
    }

    @Transactional(readOnly = true)
    public List<KeyRiskIndicator> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public KeyRiskIndicator getById(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Key risk indicator not found: " + id));
    }

    public KeyRiskIndicator update(UUID projectId, UUID id, UpdateKeyRiskIndicatorCommand command) {
        var kri = getById(projectId, id);
        if (command.name() != null) {
            kri.setName(command.name());
        }
        applyUpdates(
                kri,
                projectId,
                command.description(),
                command.metricUnit(),
                command.yellowThreshold(),
                command.redThreshold(),
                command.direction(),
                command.owner(),
                command.riskRegisterRecordId(),
                command.riskScenarioId());
        return repository.save(kri);
    }

    public KeyRiskIndicator recordMeasurement(UUID projectId, UUID id, RecordKriMeasurementCommand command) {
        if (command.value() == null) {
            throw new DomainValidationException("KRI measurement value must not be null");
        }
        var kri = getById(projectId, id);
        var oldBand = kri.getCurrentBand();
        var measuredAt = command.measuredAt() != null ? command.measuredAt() : Instant.now();
        var newBand = kri.recordMeasurement(command.value(), measuredAt);
        var saved = repository.save(kri);
        if (newBand == KriThresholdBand.RED && oldBand != KriThresholdBand.RED) {
            publishBreach(saved, oldBand, command.value());
        }
        return saved;
    }

    public void delete(UUID projectId, UUID id) {
        repository.delete(getById(projectId, id));
    }

    @SuppressWarnings("java:S107") // shared updater mirrors the command DTO surface
    private void applyUpdates(
            KeyRiskIndicator kri,
            UUID projectId,
            String description,
            String metricUnit,
            BigDecimal yellowThreshold,
            BigDecimal redThreshold,
            String direction,
            String owner,
            UUID riskRegisterRecordId,
            UUID riskScenarioId) {
        if (description != null) {
            kri.setDescription(description);
        }
        if (metricUnit != null) {
            kri.setMetricUnit(metricUnit);
        }
        if (yellowThreshold != null) {
            kri.setYellowThreshold(yellowThreshold);
        }
        if (redThreshold != null) {
            kri.setRedThreshold(redThreshold);
        }
        if (direction != null && !direction.isBlank()) {
            // GC-T007: the direction column is a String (not an enum) for forward
            // compatibility, but a value outside KeyRiskIndicator.VALID_DIRECTIONS
            // silently defaults the classify() branch to HIGHER_IS_WORSE — a
            // typo like LOWER_IS_BETTER would produce inverted band assignments
            // with no diagnostic. Reject at the write boundary instead.
            if (!KeyRiskIndicator.VALID_DIRECTIONS.contains(direction)) {
                throw new DomainValidationException("KRI direction must be one of "
                        + KeyRiskIndicator.VALID_DIRECTIONS
                        + " (got '" + direction + "')");
            }
            kri.setDirection(direction);
        }
        if (owner != null) {
            kri.setOwner(owner);
        }
        if (riskRegisterRecordId != null) {
            var record = riskRegisterRecordRepository
                    .findByIdAndProjectIdWithScenarios(riskRegisterRecordId, projectId)
                    .orElseThrow(
                            () -> new NotFoundException("Risk register record not found: " + riskRegisterRecordId));
            kri.setRiskRegisterRecord(record);
        }
        if (riskScenarioId != null) {
            var scenario = riskScenarioRepository
                    .findByIdAndProjectId(riskScenarioId, projectId)
                    .orElseThrow(() -> new NotFoundException("Risk scenario not found: " + riskScenarioId));
            kri.setRiskScenario(scenario);
        }
    }

    private void publishBreach(KeyRiskIndicator kri, KriThresholdBand oldBand, BigDecimal newValue) {
        Map<String, Object> oldValues = new HashMap<>();
        oldValues.put(FIELD_BAND, oldBand);
        Map<String, Object> newValues = new HashMap<>();
        newValues.put(FIELD_BAND, KriThresholdBand.RED);
        newValues.put(FIELD_VALUE, newValue);
        var signal = new ReassessmentSignal(
                kri.getProject().getId(),
                ReassessmentTriggerCategory.KRI_BREACH,
                ReassessmentSourceEntityType.KEY_RISK_INDICATOR,
                kri.getId(),
                Set.of(FIELD_BAND),
                oldValues,
                newValues,
                Instant.now());
        eventPublisher.publishEvent(new KriBreachedEvent(signal));
    }
}
