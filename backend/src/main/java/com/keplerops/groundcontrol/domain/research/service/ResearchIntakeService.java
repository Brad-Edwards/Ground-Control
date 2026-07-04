package com.keplerops.groundcontrol.domain.research.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.research.model.ResearchEgressAllowance;
import com.keplerops.groundcontrol.domain.research.model.ResearchIntake;
import com.keplerops.groundcontrol.domain.research.repository.ResearchIntakeRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns ResearchIntake lifecycle. Composed by ProjectService for the
 * project-create / project-update flows; also exposes a standalone
 * {@link #replace} for the dedicated {@code PUT /research-intake} endpoint.
 *
 * <p>The "intake required iff Project.type = RESEARCH" invariant is enforced
 * here and by Bean Validation at the API boundary (defence in depth).
 */
@Service
@Transactional
public class ResearchIntakeService {

    private static final Logger log = LoggerFactory.getLogger(ResearchIntakeService.class);

    private static final int GOAL_MAX = 4000;
    private static final int PAPER_CONTEXT_MAX = 8000;
    private static final int PRIVACY_CONSTRAINTS_MAX = 4000;
    private static final int ALLOWED_TOOL_MAX = 100;
    private static final int ALLOWED_TOOLS_MAX_COUNT = 100;
    private static final int EGRESS_POLICY_MAX_COUNT = 200;

    private static final String FIELD = "field";
    private static final String ALLOWED_TOOLS = "allowedTools";
    private static final String EGRESS_POLICY = "egressPolicy";
    private static final String INVALID_CODE = "research_intake_invalid";

    private final ResearchIntakeRepository intakeRepository;

    public ResearchIntakeService(ResearchIntakeRepository intakeRepository) {
        this.intakeRepository = intakeRepository;
    }

    /**
     * Create the initial ResearchIntake for a freshly-created RESEARCH project.
     * Throws ConflictException if an intake already exists for the project.
     */
    public ResearchIntake create(Project project, ResearchIntakeCommand command) {
        if (project.getType() != ProjectType.RESEARCH) {
            throw new DomainValidationException(
                    "ResearchIntake can only be created for RESEARCH projects",
                    "research_intake_type_mismatch",
                    Map.of("project_type", project.getType().name()));
        }
        if (intakeRepository.existsByProjectId(project.getId())) {
            throw new ConflictException("ResearchIntake already exists for project " + project.getIdentifier());
        }
        validate(command);
        var intake = new ResearchIntake(
                project,
                command.goal().trim(),
                command.contributionType(),
                command.intendedOutput(),
                command.autonomyLevel(),
                normaliseTools(command.allowedTools()));
        intake.setPaperContext(emptyToNull(command.paperContext()));
        intake.setPrivacyConstraints(emptyToNull(command.privacyConstraints()));
        intake.setEgressPolicy(command.egressPolicy());
        intake.setBudgetTokens(command.budgetTokens());
        intake.setBudgetWallClockMinutes(command.budgetWallClockMinutes());
        intake.setBudgetCostUsdMicros(command.budgetCostUsdMicros());
        var saved = intakeRepository.save(intake);
        log.info(
                "research_intake_created: project={} id={} no_budget_caps={}",
                project.getIdentifier(),
                saved.getId(),
                command.budgetTokens() == null
                        && command.budgetWallClockMinutes() == null
                        && command.budgetCostUsdMicros() == null);
        return saved;
    }

    /**
     * Full replacement of an existing intake. 404 if the project has no intake.
     * The project must still be RESEARCH; otherwise 422.
     */
    public ResearchIntake replace(Project project, ResearchIntakeCommand command) {
        if (project.getType() != ProjectType.RESEARCH) {
            throw new DomainValidationException(
                    "ResearchIntake can only be replaced on RESEARCH projects",
                    "research_intake_type_mismatch",
                    Map.of("project_type", project.getType().name()));
        }
        var intake = intakeRepository
                .findByProjectId(project.getId())
                .orElseThrow(
                        () -> new NotFoundException("ResearchIntake not found for project " + project.getIdentifier()));
        validate(command);
        intake.setGoal(command.goal().trim());
        intake.setPaperContext(emptyToNull(command.paperContext()));
        intake.setContributionType(command.contributionType());
        intake.setIntendedOutput(command.intendedOutput());
        intake.setAutonomyLevel(command.autonomyLevel());
        intake.setAllowedTools(normaliseTools(command.allowedTools()));
        intake.setPrivacyConstraints(emptyToNull(command.privacyConstraints()));
        intake.setEgressPolicy(command.egressPolicy());
        intake.setBudgetTokens(command.budgetTokens());
        intake.setBudgetWallClockMinutes(command.budgetWallClockMinutes());
        intake.setBudgetCostUsdMicros(command.budgetCostUsdMicros());
        var saved = intakeRepository.save(intake);
        log.info("research_intake_replaced: project={} id={}", project.getIdentifier(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<ResearchIntake> findByProject(Project project) {
        return intakeRepository.findByProjectId(project.getId());
    }

    private void validate(ResearchIntakeCommand command) {
        if (command == null) {
            throw new DomainValidationException(
                    "ResearchIntake command must not be null", "research_intake_required", Map.of());
        }
        if (command.goal() == null || command.goal().trim().isEmpty()) {
            throw new DomainValidationException(
                    "ResearchIntake.goal must not be blank", INVALID_CODE, Map.of(FIELD, "goal"));
        }
        requireUnder(command.goal(), GOAL_MAX, "goal");
        requireUnder(command.paperContext(), PAPER_CONTEXT_MAX, "paperContext");
        requireUnder(command.privacyConstraints(), PRIVACY_CONSTRAINTS_MAX, "privacyConstraints");
        if (command.contributionType() == null) {
            throw new DomainValidationException(
                    "ResearchIntake.contributionType must not be null",
                    INVALID_CODE,
                    Map.of(FIELD, "contributionType"));
        }
        if (command.intendedOutput() == null) {
            throw new DomainValidationException(
                    "ResearchIntake.intendedOutput must not be null", INVALID_CODE, Map.of(FIELD, "intendedOutput"));
        }
        if (command.autonomyLevel() == null) {
            throw new DomainValidationException(
                    "ResearchIntake.autonomyLevel must not be null", INVALID_CODE, Map.of(FIELD, "autonomyLevel"));
        }
        if (command.allowedTools() == null) {
            throw new DomainValidationException(
                    "ResearchIntake.allowedTools must not be null (use an empty list for 'no tools')",
                    INVALID_CODE,
                    Map.of(FIELD, ALLOWED_TOOLS));
        }
        if (command.allowedTools().size() > ALLOWED_TOOLS_MAX_COUNT) {
            throw new DomainValidationException(
                    "ResearchIntake.allowedTools has too many entries",
                    INVALID_CODE,
                    Map.of(FIELD, ALLOWED_TOOLS, "max", ALLOWED_TOOLS_MAX_COUNT));
        }
        for (var tool : command.allowedTools()) {
            if (tool == null || tool.trim().isEmpty()) {
                throw new DomainValidationException(
                        "ResearchIntake.allowedTools must not contain blank entries",
                        INVALID_CODE,
                        Map.of(FIELD, ALLOWED_TOOLS));
            }
            if (tool.length() > ALLOWED_TOOL_MAX) {
                throw new DomainValidationException(
                        "ResearchIntake.allowedTools entry too long",
                        INVALID_CODE,
                        Map.of(FIELD, ALLOWED_TOOLS, "max", ALLOWED_TOOL_MAX));
            }
        }
        validateEgressPolicy(command.egressPolicy());
        rejectNegative(command.budgetTokens(), "budgetTokens");
        rejectNegative(command.budgetWallClockMinutes(), "budgetWallClockMinutes");
        rejectNegative(command.budgetCostUsdMicros(), "budgetCostUsdMicros");
    }

    /**
     * Bound the egress policy and require each rule's classification enums (the
     * enforcement fields) to be present — a partially-specified rule cannot
     * silently degrade the default-deny decision (GC-RSCH-N006 / ADR-086 §2).
     */
    private void validateEgressPolicy(List<ResearchEgressAllowance> policy) {
        if (policy == null) {
            return;
        }
        if (policy.size() > EGRESS_POLICY_MAX_COUNT) {
            throw new DomainValidationException(
                    "ResearchIntake.egressPolicy has too many entries",
                    INVALID_CODE,
                    Map.of(FIELD, EGRESS_POLICY, "max", EGRESS_POLICY_MAX_COUNT));
        }
        for (var allowance : policy) {
            if (allowance == null
                    || allowance.dataClass() == null
                    || allowance.destinationClass() == null
                    || allowance.allowedForm() == null) {
                throw new DomainValidationException(
                        "ResearchIntake.egressPolicy entries require dataClass, destinationClass, and allowedForm",
                        INVALID_CODE,
                        Map.of(FIELD, EGRESS_POLICY));
            }
        }
    }

    private void requireUnder(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new DomainValidationException(
                    "ResearchIntake." + field + " exceeds max length", INVALID_CODE, Map.of(FIELD, field, "max", max));
        }
    }

    private void rejectNegative(Number value, String field) {
        if (value != null && value.longValue() < 0) {
            throw new DomainValidationException(
                    "ResearchIntake." + field + " must not be negative", INVALID_CODE, Map.of(FIELD, field));
        }
    }

    /** Strip blanks, dedupe (set semantics), preserve insertion order of first occurrence. */
    private List<String> normaliseTools(List<String> tools) {
        if (tools == null) {
            return new ArrayList<>();
        }
        var seen = new LinkedHashSet<String>();
        for (var t : tools) {
            if (t == null) {
                continue;
            }
            var trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                seen.add(trimmed);
            }
        }
        return new ArrayList<>(seen);
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
