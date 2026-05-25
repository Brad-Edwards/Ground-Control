package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TreatmentPlanService {

    private final TreatmentPlanRepository repository;
    private final RiskRegisterRecordRepository riskRegisterRecordRepository;
    private final RiskScenarioRepository riskScenarioRepository;
    private final MethodologyProfileRepository methodologyProfileRepository;
    private final ProjectService projectService;
    private final Validator validator;

    public TreatmentPlanService(
            TreatmentPlanRepository repository,
            RiskRegisterRecordRepository riskRegisterRecordRepository,
            RiskScenarioRepository riskScenarioRepository,
            MethodologyProfileRepository methodologyProfileRepository,
            ProjectService projectService,
            Validator validator) {
        this.repository = repository;
        this.riskRegisterRecordRepository = riskRegisterRecordRepository;
        this.riskScenarioRepository = riskScenarioRepository;
        this.methodologyProfileRepository = methodologyProfileRepository;
        this.projectService = projectService;
        this.validator = validator;
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
        if (command.status() != null && command.status() != TreatmentPlanStatus.PLANNED) {
            plan.transitionStatus(command.status());
        }
        applyUpdates(plan, project.getId(), command);
        return repository.save(plan);
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
        applyUpdates(plan, projectId, command);
        return repository.save(plan);
    }

    public TreatmentPlan transitionStatus(UUID projectId, UUID id, TreatmentPlanStatus status) {
        var plan = getById(projectId, id);
        plan.transitionStatus(status);
        return repository.save(plan);
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
    }

    private void applySharedUpdates(
            TreatmentPlan plan,
            UUID projectId,
            UUID riskScenarioId,
            TreatmentStrategy strategy,
            String owner,
            String rationale,
            java.time.Instant dueDate,
            List<ActionItem> actionItems,
            List<String> reassessmentTriggers,
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
}
