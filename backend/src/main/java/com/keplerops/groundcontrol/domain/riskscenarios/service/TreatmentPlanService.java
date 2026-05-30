package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.graph.service.GraphTargetResolverService;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSignal;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSourceEntityType;
import com.keplerops.groundcontrol.domain.riskscenarios.events.TreatmentProgressChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MonitoredRiskFactor;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ReassessmentTrigger;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ActionItemStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TreatmentPlanService {

    // GC-T004 / C8: field-name keys shared by every TreatmentPlan publisher branch.
    // Hoisted out of inline literals so each rename is one site, not three.
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ACTION_ITEMS_HISTOGRAM = "actionItemsStatusHistogram";

    private final TreatmentPlanRepository repository;
    private final RiskRegisterRecordRepository riskRegisterRecordRepository;
    private final RiskScenarioRepository riskScenarioRepository;
    private final MethodologyProfileRepository methodologyProfileRepository;
    private final RiskAssessmentResultRepository riskAssessmentResultRepository;
    private final ProjectService projectService;
    private final Validator validator;
    private final GraphTargetResolverService graphTargetResolverService;
    private final ApplicationEventPublisher eventPublisher;

    @SuppressWarnings("java:S107") // service aggregates nine collaborators from the constructor on purpose
    public TreatmentPlanService(
            TreatmentPlanRepository repository,
            RiskRegisterRecordRepository riskRegisterRecordRepository,
            RiskScenarioRepository riskScenarioRepository,
            MethodologyProfileRepository methodologyProfileRepository,
            RiskAssessmentResultRepository riskAssessmentResultRepository,
            ProjectService projectService,
            Validator validator,
            GraphTargetResolverService graphTargetResolverService,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.riskRegisterRecordRepository = riskRegisterRecordRepository;
        this.riskScenarioRepository = riskScenarioRepository;
        this.methodologyProfileRepository = methodologyProfileRepository;
        this.riskAssessmentResultRepository = riskAssessmentResultRepository;
        this.projectService = projectService;
        this.validator = validator;
        this.graphTargetResolverService = graphTargetResolverService;
        this.eventPublisher = eventPublisher;
    }

    public TreatmentPlan create(CreateTreatmentPlanCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("Treatment plan with UID " + command.uid() + " already exists");
        }
        var record = riskRegisterRecordRepository
                .findByIdAndProjectIdWithScenarios(command.riskRegisterRecordId(), project.getId())
                .orElseThrow(() ->
                        new NotFoundException("Risk register record not found: " + command.riskRegisterRecordId()));
        var plan = new TreatmentPlan(project, command.uid(), command.title(), record, command.strategy());
        boolean initialStatusIsTransition = command.status() != null && command.status() != TreatmentPlanStatus.PLANNED;
        if (initialStatusIsTransition) {
            plan.transitionStatus(command.status());
        }
        applyUpdates(plan, project.getId(), command);
        var saved = repository.save(plan);
        if (initialStatusIsTransition) {
            // Create-with-non-PLANNED-status is functionally a PLANNED → <status> transition
            // at birth; the publisher contract treats it like `transitionStatus` so the
            // C8 reassessment signal reaches affected assessment rows.
            publish(buildSignal(
                    saved,
                    ReassessmentTriggerCategory.TREATMENT_PROGRESS_CHANGED,
                    Set.of(FIELD_STATUS),
                    Map.of(FIELD_STATUS, TreatmentPlanStatus.PLANNED),
                    Map.of(FIELD_STATUS, saved.getStatus())));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TreatmentPlan> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<TreatmentPlan> listByRiskRegisterRecord(UUID projectId, UUID riskRegisterRecordId) {
        if (riskRegisterRecordRepository
                .findByIdAndProjectIdWithScenarios(riskRegisterRecordId, projectId)
                .isEmpty()) {
            throw new NotFoundException("Risk register record not found: " + riskRegisterRecordId);
        }
        return repository.findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(projectId, riskRegisterRecordId);
    }

    @Transactional(readOnly = true)
    public TreatmentPlan getById(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Treatment plan not found: " + id));
    }

    public TreatmentPlan update(UUID projectId, UUID id, UpdateTreatmentPlanCommand command) {
        var plan = getById(projectId, id);
        if (command.title() != null) {
            plan.setTitle(command.title());
        }
        var oldHistogram = actionItemStatusHistogram(plan.getActionItems());
        applyUpdates(plan, projectId, command);
        var saved = repository.save(plan);
        publishProgressIfChanged(saved, oldHistogram);
        return saved;
    }

    public TreatmentPlan transitionStatus(UUID projectId, UUID id, TreatmentPlanStatus status) {
        var plan = getById(projectId, id);
        var oldStatus = plan.getStatus();
        plan.transitionStatus(status);
        var saved = repository.save(plan);
        if (oldStatus != saved.getStatus()) {
            publish(buildSignal(
                    saved,
                    ReassessmentTriggerCategory.TREATMENT_PROGRESS_CHANGED,
                    Set.of(FIELD_STATUS),
                    Map.of(FIELD_STATUS, oldStatus),
                    Map.of(FIELD_STATUS, saved.getStatus())));
        }
        return saved;
    }

    public void delete(UUID projectId, UUID id) {
        repository.delete(getById(projectId, id));
    }

    private void applyUpdates(TreatmentPlan plan, UUID projectId, CreateTreatmentPlanCommand command) {
        applySharedUpdates(
                plan,
                projectId,
                command.riskScenarioId(),
                command.strategy(),
                command.owner(),
                command.rationale(),
                command.dueDate(),
                command.actionItems(),
                command.reassessmentTriggers(),
                command.methodologyProfileId(),
                command.methodologyStrategyKey());
        applyMonitoringFields(
                plan,
                projectId,
                command.riskAssessmentResultId(),
                command.monitoredRiskFactors(),
                command.updateCadence());
    }

    private void applyUpdates(TreatmentPlan plan, UUID projectId, UpdateTreatmentPlanCommand command) {
        applySharedUpdates(
                plan,
                projectId,
                command.riskScenarioId(),
                command.strategy(),
                command.owner(),
                command.rationale(),
                command.dueDate(),
                command.actionItems(),
                command.reassessmentTriggers(),
                command.methodologyProfileId(),
                command.methodologyStrategyKey());
        applyMonitoringFields(
                plan,
                projectId,
                command.riskAssessmentResultId(),
                command.monitoredRiskFactors(),
                command.updateCadence());
    }

    /**
     * GC-T015: apply monitoring fields (RAR FK, monitored factors, cadence). The RAR
     * FK is routed through {@code GraphTargetResolverService.validateRiskAssessmentResultTarget}
     * so a cross-project RAR id is rejected before persistence (closes the
     * GC-T004/C8 cross-project bug class).
     */
    private void applyMonitoringFields(
            TreatmentPlan plan,
            UUID projectId,
            UUID riskAssessmentResultId,
            List<MonitoredRiskFactor> monitoredRiskFactors,
            String updateCadence) {
        if (riskAssessmentResultId != null) {
            graphTargetResolverService.validateRiskAssessmentResultTarget(projectId, riskAssessmentResultId);
            var rar = riskAssessmentResultRepository
                    .findByIdAndProjectId(riskAssessmentResultId, projectId)
                    .orElseThrow(
                            () -> new NotFoundException("Risk assessment result not found: " + riskAssessmentResultId));
            plan.setRiskAssessmentResult(rar);
        }
        if (monitoredRiskFactors != null) {
            validateMonitoredRiskFactors(monitoredRiskFactors);
            plan.setMonitoredRiskFactors(monitoredRiskFactors);
        }
        if (updateCadence != null) {
            plan.setUpdateCadence(updateCadence.isBlank() ? null : updateCadence);
        }
    }

    private void validateMonitoredRiskFactors(List<MonitoredRiskFactor> factors) {
        for (int i = 0; i < factors.size(); i++) {
            var factor = factors.get(i);
            if (factor == null) {
                throw new DomainValidationException("Monitored risk factor at index " + i + " must not be null");
            }
            Set<ConstraintViolation<MonitoredRiskFactor>> violations = validator.validate(factor);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));
                throw new DomainValidationException("Monitored risk factor at index " + i + " has invalid " + detail);
            }
        }
    }

    @SuppressWarnings("java:S107") // shared updater is a single point of mutation; arg count tracks the DTO surface
    private void applySharedUpdates(
            TreatmentPlan plan,
            UUID projectId,
            UUID riskScenarioId,
            TreatmentStrategy strategy,
            String owner,
            String rationale,
            java.time.Instant dueDate,
            List<ActionItem> actionItems,
            List<ReassessmentTrigger> reassessmentTriggers,
            UUID methodologyProfileId,
            String methodologyStrategyKey) {
        if (riskScenarioId != null) {
            var scenario = riskScenarioRepository
                    .findByIdAndProjectId(riskScenarioId, projectId)
                    .orElseThrow(() -> new NotFoundException("Risk scenario not found: " + riskScenarioId));
            if (!plan.getRiskRegisterRecord().getRiskScenarios().isEmpty()
                    && plan.getRiskRegisterRecord().getRiskScenarios().stream()
                            .noneMatch(candidate -> candidate.getId().equals(scenario.getId()))) {
                throw new DomainValidationException(
                        "Treatment plan scenario must belong to the linked risk register record");
            }
            plan.setRiskScenario(scenario);
        }
        if (strategy != null) {
            plan.setStrategy(strategy);
        }
        if (owner != null) {
            plan.setOwner(owner);
        }
        if (rationale != null) {
            plan.setRationale(rationale);
        }
        if (dueDate != null) {
            plan.setDueDate(dueDate);
        }
        if (actionItems != null) {
            validateActionItems(actionItems);
            plan.setActionItems(actionItems);
        }
        if (reassessmentTriggers != null) {
            validateReassessmentTriggers(reassessmentTriggers, projectId);
            plan.setReassessmentTriggers(reassessmentTriggers);
        }
        applyMethodologyBinding(plan, projectId, methodologyProfileId, methodologyStrategyKey);
    }

    /**
     * Bypass-write guard for action items. Controller writes traverse Bean Validation
     * via {@code @Valid} on the request DTOs; service-layer writes (tests, future
     * internal callers) hit this guard so they cannot persist out-of-contract data.
     * Reuses the {@link ActionItem} constraint annotations programmatically so the
     * service guard and the REST boundary share one source of truth.
     */
    private void validateActionItems(List<ActionItem> actionItems) {
        for (int i = 0; i < actionItems.size(); i++) {
            var item = actionItems.get(i);
            if (item == null) {
                throw new DomainValidationException("Action item at index " + i + " must not be null");
            }
            Set<ConstraintViolation<ActionItem>> violations = validator.validate(item);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));
                throw new DomainValidationException("Action item at index " + i + " has invalid " + detail);
            }
        }
    }

    /**
     * Bypass-write guard for typed reassessment triggers (GC-T004 / C8, issue #863).
     * Mirrors {@link #validateActionItems} — programmatic Bean Validation on each
     * element, plus project-scoped resolver enforcement on any {@code targetType}
     * supplied. Service-layer callers that route around the REST {@code @Valid}
     * boundary cannot persist out-of-contract triggers or cross-project target ids.
     */
    private void validateReassessmentTriggers(List<ReassessmentTrigger> triggers, UUID projectId) {
        for (int i = 0; i < triggers.size(); i++) {
            var trigger = triggers.get(i);
            if (trigger == null) {
                throw new DomainValidationException("Reassessment trigger at index " + i + " must not be null");
            }
            Set<ConstraintViolation<ReassessmentTrigger>> violations = validator.validate(trigger);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));
                throw new DomainValidationException("Reassessment trigger at index " + i + " has invalid " + detail);
            }
            validateReassessmentTriggerTargetShape(trigger, i, projectId);
        }
    }

    /**
     * GC-T004 / C8 (#863), codex cycle-1 finding #2: trigger target fields are not
     * three independent optionals. The contract is exactly one of:
     * <ul>
     *   <li>No target at all: {@code targetType}, {@code targetEntityId}, and
     *       {@code targetIdentifier} are all null. The trigger is a category-only
     *       declaration.</li>
     *   <li>Internal target: {@code targetType} is one of the modelled types and
     *       {@code targetEntityId} is present (resolved through the project-scoped
     *       resolver). {@code targetIdentifier} must be null.</li>
     *   <li>External target: {@code targetType} is {@code EXTERNAL} and
     *       {@code targetIdentifier} is present. {@code targetEntityId} must be
     *       null.</li>
     * </ul>
     *
     * <p>Reject any other combination as a {@link DomainValidationException} so a
     * trigger that names {@code targetEntityId} without a {@code targetType}
     * (uninterpretable later) or mixes EXTERNAL with {@code targetEntityId}
     * (inconsistent typed contract) never reaches persistence.
     */
    private void validateReassessmentTriggerTargetShape(ReassessmentTrigger trigger, int index, UUID projectId) {
        var targetType = trigger.targetType();
        var entityId = trigger.targetEntityId();
        var identifier = trigger.targetIdentifier();
        boolean hasIdentifier = identifier != null && !identifier.isBlank();

        if (targetType == null) {
            if (entityId != null || hasIdentifier) {
                throw new DomainValidationException(
                        "Reassessment trigger at index " + index + " has target reference fields without targetType");
            }
            return;
        }

        if (targetType
                == com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerTargetType.EXTERNAL) {
            if (entityId != null) {
                throw new DomainValidationException("Reassessment trigger at index " + index
                        + " with targetType=EXTERNAL must not set targetEntityId");
            }
            if (!hasIdentifier) {
                throw new DomainValidationException("Reassessment trigger at index " + index
                        + " with targetType=EXTERNAL requires targetIdentifier");
            }
        } else {
            if (hasIdentifier) {
                throw new DomainValidationException("Reassessment trigger at index " + index
                        + " with internal targetType=" + targetType + " must not set targetIdentifier");
            }
            if (entityId == null) {
                throw new DomainValidationException("Reassessment trigger at index " + index
                        + " with internal targetType=" + targetType + " requires targetEntityId");
            }
        }

        // Project-scoped resolver enforcement — a non-existent or cross-project UUID
        // becomes DomainValidationException ("not found in the requested project").
        graphTargetResolverService.validateReassessmentTriggerTarget(projectId, targetType, entityId, identifier);
    }

    private void applyMethodologyBinding(
            TreatmentPlan plan, UUID projectId, UUID methodologyProfileId, String methodologyStrategyKey) {
        if (plan.getStrategy() != TreatmentStrategy.OTHER) {
            plan.setMethodologyProfile(null);
            plan.setMethodologyStrategyKey(null);
            return;
        }
        // strategy == OTHER: resolve effective profile and key
        MethodologyProfile effectiveProfile;
        if (methodologyProfileId != null) {
            effectiveProfile = methodologyProfileRepository
                    .findByIdAndProjectId(methodologyProfileId, projectId)
                    .orElseThrow(() -> new NotFoundException("Methodology profile not found: " + methodologyProfileId));
        } else {
            effectiveProfile = plan.getMethodologyProfile();
        }
        String effectiveKey =
                methodologyStrategyKey != null ? methodologyStrategyKey : plan.getMethodologyStrategyKey();

        if (effectiveProfile == null || effectiveKey == null || effectiveKey.isBlank()) {
            throw new DomainValidationException(
                    "Treatment plan with strategy OTHER requires methodologyProfileId and methodologyStrategyKey");
        }
        var vocabulary = effectiveProfile.getTreatmentStrategyVocabulary();
        if (vocabulary == null || !vocabulary.containsKey(effectiveKey)) {
            throw new DomainValidationException("Methodology strategy key '"
                    + effectiveKey
                    + "' is not defined in methodology profile "
                    + effectiveProfile.getProfileKey());
        }
        plan.setMethodologyProfile(effectiveProfile);
        plan.setMethodologyStrategyKey(effectiveKey);
    }

    private void publishProgressIfChanged(TreatmentPlan saved, Map<ActionItemStatus, Long> oldHistogram) {
        var newHistogram = actionItemStatusHistogram(saved.getActionItems());
        if (!oldHistogram.equals(newHistogram)) {
            publish(buildSignal(
                    saved,
                    ReassessmentTriggerCategory.TREATMENT_PROGRESS_CHANGED,
                    Set.of(FIELD_ACTION_ITEMS_HISTOGRAM),
                    Map.of(FIELD_ACTION_ITEMS_HISTOGRAM, new EnumMap<>(oldHistogram)),
                    Map.of(FIELD_ACTION_ITEMS_HISTOGRAM, new EnumMap<>(newHistogram))));
        }
    }

    /**
     * Defensive: persistence-read items bypass Bean Validation, so a legacy row with
     * status=null could otherwise NPE in {@code hist.merge}. The {@code @NotNull}
     * contract on {@code ActionItem.status} applies at the REST / service write boundary,
     * not at the JPA read boundary that feeds this method — Sonar's S2589 ("always false")
     * reads the contract but not the read path, hence the suppression.
     */
    @SuppressWarnings("java:S2589")
    private Map<ActionItemStatus, Long> actionItemStatusHistogram(List<ActionItem> items) {
        Map<ActionItemStatus, Long> hist = new EnumMap<>(ActionItemStatus.class);
        if (items == null) {
            return hist;
        }
        for (var item : items) {
            if (item == null || item.status() == null) {
                continue;
            }
            hist.merge(item.status(), 1L, Long::sum);
        }
        return hist;
    }

    private ReassessmentSignal buildSignal(
            TreatmentPlan plan,
            ReassessmentTriggerCategory category,
            Set<String> changedFields,
            Map<String, Object> oldValues,
            Map<String, Object> newValues) {
        return new ReassessmentSignal(
                plan.getProject().getId(),
                category,
                ReassessmentSourceEntityType.TREATMENT_PLAN,
                plan.getId(),
                new HashSet<>(changedFields),
                new HashMap<>(oldValues),
                new HashMap<>(newValues),
                Instant.now());
    }

    private void publish(ReassessmentSignal signal) {
        eventPublisher.publishEvent(new TreatmentProgressChangedEvent(signal));
    }
}
