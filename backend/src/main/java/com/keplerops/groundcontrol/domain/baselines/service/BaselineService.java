package com.keplerops.groundcontrol.domain.baselines.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.audit.service.AsOfRevisionResolver;
import com.keplerops.groundcontrol.domain.baselines.model.Baseline;
import com.keplerops.groundcontrol.domain.baselines.repository.BaselineRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hibernate.envers.AuditReaderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BaselineService {

    private static final Logger log = LoggerFactory.getLogger(BaselineService.class);

    private final BaselineRepository baselineRepository;
    private final ProjectRepository projectRepository;
    private final EntityManager entityManager;
    private final AsOfRevisionResolver asOfRevisionResolver;

    public BaselineService(
            BaselineRepository baselineRepository,
            ProjectRepository projectRepository,
            EntityManager entityManager,
            AsOfRevisionResolver asOfRevisionResolver) {
        this.baselineRepository = baselineRepository;
        this.projectRepository = projectRepository;
        this.entityManager = entityManager;
        this.asOfRevisionResolver = asOfRevisionResolver;
    }

    public Baseline create(CreateBaselineCommand command) {
        var project = projectRepository
                .findById(command.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + command.projectId()));

        if (baselineRepository.existsByProjectIdAndName(project.getId(), command.name())) {
            throw new ConflictException(
                    "Baseline with name '" + command.name() + "' already exists in project " + project.getIdentifier());
        }

        // Optional.empty() means "no revision has ever been created" (a fresh database with
        // nothing audited yet). Baseline.revisionNumber is a persisted int and
        // getRequirementsAtRevision(0) is defined to return List.of() — this is the one place
        // that origin sentinel is minted; the resolver itself never invents a 0 revision.
        int revisionNumber = asOfRevisionResolver.currentRevision().orElse(0);

        var baseline = new Baseline(project, command.name(), command.description(), revisionNumber, ActorHolder.get());
        var saved = baselineRepository.save(baseline);
        log.info(
                "baseline_created: project={} name={} revision={} id={}",
                project.getIdentifier(),
                saved.getName(),
                saved.getRevisionNumber(),
                saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Baseline getById(UUID id) {
        return baselineRepository.findById(id).orElseThrow(() -> new NotFoundException("Baseline not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Baseline> listByProject(UUID projectId) {
        return baselineRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public BaselineSnapshot getSnapshot(UUID baselineId) {
        var baseline = getById(baselineId);
        var requirements = getRequirementsAtRevision(
                baseline.getRevisionNumber(), baseline.getProject().getId());
        return new BaselineSnapshot(
                baseline.getId(),
                baseline.getName(),
                baseline.getRevisionNumber(),
                baseline.getCreatedAt(),
                requirements);
    }

    @Transactional(readOnly = true)
    public BaselineComparison compare(UUID baselineId, UUID otherBaselineId) {
        var baseline = getById(baselineId);
        var other = getById(otherBaselineId);

        if (!baseline.getProject().getId().equals(other.getProject().getId())) {
            throw new DomainValidationException(
                    "Cannot compare baselines from different projects",
                    "cross_project_comparison",
                    Map.of(
                            "baseline_project",
                            baseline.getProject().getIdentifier(),
                            "other_project",
                            other.getProject().getIdentifier()));
        }

        var baseReqs = getRequirementsAtRevision(
                baseline.getRevisionNumber(), baseline.getProject().getId());
        var otherReqs = getRequirementsAtRevision(
                other.getRevisionNumber(), other.getProject().getId());

        Map<UUID, Requirement> baseMap =
                baseReqs.stream().collect(Collectors.toMap(Requirement::getId, Function.identity()));
        Map<UUID, Requirement> otherMap =
                otherReqs.stream().collect(Collectors.toMap(Requirement::getId, Function.identity()));

        List<Requirement> added =
                otherReqs.stream().filter(r -> !baseMap.containsKey(r.getId())).toList();

        List<Requirement> removed =
                baseReqs.stream().filter(r -> !otherMap.containsKey(r.getId())).toList();

        List<ModifiedRequirement> modified = new ArrayList<>();
        for (var entry : baseMap.entrySet()) {
            var otherReq = otherMap.get(entry.getKey());
            if (otherReq != null && hasChanged(entry.getValue(), otherReq)) {
                modified.add(
                        new ModifiedRequirement(entry.getKey(), entry.getValue().getUid(), entry.getValue(), otherReq));
            }
        }

        return new BaselineComparison(
                baseline.getId(), baseline.getName(), other.getId(), other.getName(), added, removed, modified);
    }

    public void delete(UUID id) {
        var baseline =
                baselineRepository.findById(id).orElseThrow(() -> new NotFoundException("Baseline not found: " + id));
        baselineRepository.delete(baseline);
        log.info("baseline_deleted: name={} id={}", baseline.getName(), baseline.getId());
    }

    @SuppressWarnings("unchecked")
    // Visible for testing (spy-based unit tests stub this Envers-dependent method)
    public List<Requirement> getRequirementsAtRevision(int revisionNumber, UUID projectId) {
        if (revisionNumber == 0) {
            return List.of();
        }

        // Project is @NotAudited, so Envers-reconstructed entities have null project.
        // Get the project's requirement IDs from the main table to filter.
        var projectReqIds = new java.util.HashSet<>(entityManager
                .createQuery("SELECT r.id FROM Requirement r WHERE r.project.id = :pid", UUID.class)
                .setParameter("pid", projectId)
                .getResultList());

        var auditReader = AuditReaderFactory.get(entityManager);

        List<Requirement> allAtRevision = auditReader
                .createQuery()
                .forEntitiesAtRevision(Requirement.class, revisionNumber)
                .getResultList();

        return allAtRevision.stream()
                .filter(r -> projectReqIds.contains(r.getId()))
                .filter(r -> r.getStatus() != Status.ARCHIVED)
                .toList();
    }

    // Visible for testing
    public boolean hasChanged(Requirement a, Requirement b) {
        return !equalsNullSafe(a.getTitle(), b.getTitle())
                || !equalsNullSafe(a.getStatement(), b.getStatement())
                || !equalsNullSafe(a.getRationale(), b.getRationale())
                || !equalsNullSafe(a.getRequirementType(), b.getRequirementType())
                || !equalsNullSafe(a.getPriority(), b.getPriority())
                || !equalsNullSafe(a.getStatus(), b.getStatus())
                || !equalsNullSafe(a.getWave(), b.getWave());
    }

    private boolean equalsNullSafe(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
