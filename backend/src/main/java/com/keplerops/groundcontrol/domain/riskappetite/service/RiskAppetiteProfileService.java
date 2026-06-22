package com.keplerops.groundcontrol.domain.riskappetite.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskappetite.model.RiskAppetiteProfile;
import com.keplerops.groundcontrol.domain.riskappetite.model.ToleranceThreshold;
import com.keplerops.groundcontrol.domain.riskappetite.repository.RiskAppetiteProfileRepository;
import com.keplerops.groundcontrol.domain.riskappetite.state.RiskAppetiteProfileStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Domain orchestration for risk appetite and tolerance profiles (GC-T005). Owns the appetite
 * aggregate's write path: uniqueness on {@code (project, appetiteKey, version)}, methodology-aware
 * tolerance-threshold validation, and the invariant that no two {@code ACTIVE} versions of the same
 * appetite key may have overlapping effective windows.
 */
@Service
@Transactional
public class RiskAppetiteProfileService {

    private static final String PROBABILITY_UNIT = "probability";
    private static final String NOT_FOUND = "Risk appetite profile not found: ";

    private final RiskAppetiteProfileRepository repository;
    private final ProjectService projectService;

    public RiskAppetiteProfileService(RiskAppetiteProfileRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    public RiskAppetiteProfile create(CreateRiskAppetiteProfileCommand command) {
        var project = projectService.getById(command.projectId());
        if (command.effectiveFrom() == null) {
            throw new DomainValidationException("effectiveFrom is required");
        }
        if (repository.existsByProjectIdAndAppetiteKeyAndVersion(
                project.getId(), command.appetiteKey(), command.version())) {
            throw new ConflictException("Risk appetite profile with key " + command.appetiteKey() + " version "
                    + command.version() + " already exists");
        }
        validateWindow(command.effectiveFrom(), command.effectiveTo());
        validateThresholds(command.toleranceThresholds());

        var profile = new RiskAppetiteProfile(
                project,
                command.appetiteKey(),
                command.name(),
                command.version(),
                command.methodologyFamily(),
                command.effectiveFrom());
        profile.setAppetiteStatement(command.appetiteStatement());
        profile.setToleranceThresholds(command.toleranceThresholds());
        profile.setEffectiveTo(command.effectiveTo());
        if (command.status() != null) {
            profile.setStatus(command.status());
        }
        if (profile.getStatus() == RiskAppetiteProfileStatus.ACTIVE) {
            assertNoActiveWindowOverlap(profile);
        }
        return repository.save(profile);
    }

    @Transactional(readOnly = true)
    public List<RiskAppetiteProfile> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByNameAscVersionDesc(projectId);
    }

    @Transactional(readOnly = true)
    public RiskAppetiteProfile getById(UUID projectId, UUID id) {
        return repository.findByIdAndProjectId(id, projectId).orElseThrow(() -> new NotFoundException(NOT_FOUND + id));
    }

    public RiskAppetiteProfile update(UUID projectId, UUID id, UpdateRiskAppetiteProfileCommand command) {
        // Resolve via the repository (not getById) to avoid the @Transactional self-invocation
        // Sonar S6809 flags — the proxy would be bypassed. Class-level @Transactional still applies.
        var profile =
                repository.findByIdAndProjectId(id, projectId).orElseThrow(() -> new NotFoundException(NOT_FOUND + id));
        if (command.name() != null) {
            profile.setName(command.name());
        }
        if (command.version() != null) {
            profile.setVersion(command.version());
        }
        if (command.methodologyFamily() != null) {
            profile.setMethodologyFamily(command.methodologyFamily());
        }
        if (command.appetiteStatement() != null) {
            profile.setAppetiteStatement(command.appetiteStatement());
        }
        if (command.toleranceThresholds() != null) {
            validateThresholds(command.toleranceThresholds());
            profile.setToleranceThresholds(command.toleranceThresholds());
        }
        if (command.effectiveFrom() != null) {
            profile.setEffectiveFrom(command.effectiveFrom());
        }
        if (command.effectiveTo() != null) {
            profile.setEffectiveTo(command.effectiveTo());
        }
        if (command.status() != null) {
            profile.setStatus(command.status());
        }
        validateWindow(profile.getEffectiveFrom(), profile.getEffectiveTo());
        if (profile.getStatus() == RiskAppetiteProfileStatus.ACTIVE) {
            assertNoActiveWindowOverlap(profile);
        }
        return repository.save(profile);
    }

    public void delete(UUID projectId, UUID id) {
        var profile =
                repository.findByIdAndProjectId(id, projectId).orElseThrow(() -> new NotFoundException(NOT_FOUND + id));
        repository.delete(profile);
    }

    private void validateWindow(Instant effectiveFrom, Instant effectiveTo) {
        if (effectiveFrom == null) {
            throw new DomainValidationException("effectiveFrom is required");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new DomainValidationException("effectiveTo must be after effectiveFrom");
        }
    }

    private void assertNoActiveWindowOverlap(RiskAppetiteProfile candidate) {
        var actives = repository.findByProjectIdAndAppetiteKeyAndStatus(
                candidate.getProject().getId(), candidate.getAppetiteKey(), RiskAppetiteProfileStatus.ACTIVE);
        for (RiskAppetiteProfile other : actives) {
            if (other.getId() != null && other.getId().equals(candidate.getId())) {
                continue;
            }
            if (windowsOverlap(
                    candidate.getEffectiveFrom(),
                    candidate.getEffectiveTo(),
                    other.getEffectiveFrom(),
                    other.getEffectiveTo())) {
                throw new ConflictException("Active risk appetite profile " + candidate.getAppetiteKey()
                        + " version " + other.getVersion()
                        + " already covers an overlapping effective window");
            }
        }
    }

    /** Half-open intervals [from, to); a null {@code to} means open-ended (+infinity). */
    private boolean windowsOverlap(Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
        boolean aStartsBeforeBEnds = bTo == null || aFrom.isBefore(bTo);
        boolean bStartsBeforeAEnds = aTo == null || bFrom.isBefore(aTo);
        return aStartsBeforeBEnds && bStartsBeforeAEnds;
    }

    private void validateThresholds(List<ToleranceThreshold> thresholds) {
        if (thresholds == null) {
            return;
        }
        for (ToleranceThreshold t : thresholds) {
            boolean hasQuantitative = t.maxQuantitativeValue() != null;
            boolean hasOrdinal =
                    t.maxOrdinalValue() != null && !t.maxOrdinalValue().isBlank();
            if (hasQuantitative == hasOrdinal) {
                throw new DomainValidationException("Tolerance threshold for " + t.metricPath()
                        + " must set exactly one of maxQuantitativeValue or maxOrdinalValue");
            }
            if (hasQuantitative) {
                validateQuantitative(t);
            } else {
                validateOrdinal(t);
            }
        }
    }

    private void validateQuantitative(ToleranceThreshold t) {
        double value = t.maxQuantitativeValue();
        if (value < 0) {
            throw new DomainValidationException("maxQuantitativeValue for " + t.metricPath() + " must not be negative");
        }
        if (PROBABILITY_UNIT.equalsIgnoreCase(t.units()) && value > 1.0) {
            throw new DomainValidationException(
                    "probability tolerance for " + t.metricPath() + " must be within [0,1]");
        }
    }

    private void validateOrdinal(ToleranceThreshold t) {
        if (t.orderedScale() == null || t.orderedScale().isEmpty()) {
            throw new DomainValidationException(
                    "ordinal tolerance for " + t.metricPath() + " requires a non-empty orderedScale");
        }
        if (!t.orderedScale().contains(t.maxOrdinalValue())) {
            throw new DomainValidationException("maxOrdinalValue " + t.maxOrdinalValue() + " for " + t.metricPath()
                    + " must appear in orderedScale");
        }
    }
}
