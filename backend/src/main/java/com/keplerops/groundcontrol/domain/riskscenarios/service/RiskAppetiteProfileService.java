package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAppetiteTolerance;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAppetiteProfileRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T005: governance of Risk Appetite Profile aggregates.
 *
 * <p>Service-side guards:
 * <ul>
 *   <li>{@code (project_id, profile_key, version)} uniqueness — bumped via the
 *       repository's UNIQUE constraint, surfaced as {@link ConflictException}.
 *   <li>Only one active profile per {@code profileKey} per project: setting
 *       {@code active=true} on a new or updated profile archives the others.
 *   <li>Each {@link RiskAppetiteTolerance} band is validated through Bean
 *       Validation; duplicate {@code (category, kind)} entries are rejected so
 *       the {@link RiskAppetiteEvaluator} sees a deterministic match.
 * </ul>
 */
@Service
@Transactional
public class RiskAppetiteProfileService {

    private static final String PROFILE_NOT_FOUND = "Risk appetite profile not found: ";

    private final RiskAppetiteProfileRepository repository;
    private final ProjectService projectService;
    private final Validator validator;

    public RiskAppetiteProfileService(
            RiskAppetiteProfileRepository repository, ProjectService projectService, Validator validator) {
        this.repository = repository;
        this.projectService = projectService;
        this.validator = validator;
    }

    public RiskAppetiteProfile create(CreateRiskAppetiteProfileCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndProfileKeyAndVersion(
                project.getId(), command.profileKey(), command.version())) {
            throw new ConflictException(
                    "Risk appetite profile " + command.profileKey() + "@" + command.version() + " already exists");
        }
        var profile = new RiskAppetiteProfile(project, command.profileKey(), command.name(), command.version());
        applyUpdates(profile, command.appetiteStatement(), command.owner(), command.active(), command.tolerances());
        var saved = repository.save(profile);
        if (saved.isActive()) {
            archiveOtherActiveVersions(project.getId(), saved);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RiskAppetiteProfile> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByProfileKeyAscVersionDesc(projectId);
    }

    @Transactional(readOnly = true)
    public RiskAppetiteProfile getById(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException(PROFILE_NOT_FOUND + id));
    }

    public RiskAppetiteProfile update(UUID projectId, UUID id, UpdateRiskAppetiteProfileCommand command) {
        // Resolve directly via the repository rather than via getById() to avoid
        // the @Transactional self-invocation pattern Sonar S6809 flags — the
        // proxy is bypassed and any per-method tx semantics would be lost. The
        // class-level @Transactional covers this method, so behaviour is unchanged.
        var profile = repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException(PROFILE_NOT_FOUND + id));
        if (command.name() != null) {
            profile.setName(command.name());
        }
        if (command.version() != null) {
            profile.setVersion(command.version());
        }
        applyUpdates(profile, command.appetiteStatement(), command.owner(), command.active(), command.tolerances());
        var saved = repository.save(profile);
        if (saved.isActive()) {
            archiveOtherActiveVersions(projectId, saved);
        }
        return saved;
    }

    public void delete(UUID projectId, UUID id) {
        // Resolve directly via the repository rather than via getById() to avoid
        // the @Transactional self-invocation pattern Sonar S6809 flags — the
        // proxy is bypassed and any per-method tx semantics would be lost. The
        // class-level @Transactional covers this method, so behaviour is unchanged.
        var profile = repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException(PROFILE_NOT_FOUND + id));
        repository.delete(profile);
    }

    private void applyUpdates(
            RiskAppetiteProfile profile,
            String appetiteStatement,
            String owner,
            Boolean active,
            List<RiskAppetiteTolerance> tolerances) {
        if (appetiteStatement != null) {
            profile.setAppetiteStatement(appetiteStatement);
        }
        if (owner != null) {
            profile.setOwner(owner);
        }
        if (active != null) {
            profile.setActive(active);
        }
        if (tolerances != null) {
            validateTolerances(tolerances);
            profile.setTolerances(tolerances);
        }
    }

    private void validateTolerances(List<RiskAppetiteTolerance> tolerances) {
        Set<String> seenCategoryKind = new HashSet<>();
        for (int i = 0; i < tolerances.size(); i++) {
            var tolerance = tolerances.get(i);
            if (tolerance == null) {
                throw new DomainValidationException("Tolerance at index " + i + " must not be null");
            }
            Set<ConstraintViolation<RiskAppetiteTolerance>> violations = validator.validate(tolerance);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));
                throw new DomainValidationException("Tolerance at index " + i + " has invalid " + detail);
            }
            String key = tolerance.category().toLowerCase(Locale.ROOT) + "|" + tolerance.kind();
            if (!seenCategoryKind.add(key)) {
                throw new DomainValidationException(
                        "Duplicate tolerance band for category=" + tolerance.category() + " kind=" + tolerance.kind());
            }
        }
    }

    private void archiveOtherActiveVersions(UUID projectId, RiskAppetiteProfile activated) {
        var siblings = repository.findByProjectIdAndProfileKeyAndActiveTrue(projectId, activated.getProfileKey());
        for (var sibling : siblings) {
            if (!sibling.getId().equals(activated.getId())) {
                sibling.setActive(false);
                repository.save(sibling);
            }
        }
    }
}
