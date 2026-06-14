package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.qualitygates.repository.QualityGateRepository;
import com.keplerops.groundcontrol.domain.qualitygates.state.MetricType;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.RequirementRelation;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRelationRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementSpecifications;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.requirements.state.RelationType;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@SuppressWarnings("java:S125") // JML contract annotations are intentional, not dead code
public class RequirementService {

    private final RequirementRepository requirementRepository;
    private final RequirementRelationRepository relationRepository;
    private final ProjectRepository projectRepository;
    private final QualityGateRepository qualityGateRepository;
    private final TraceabilityLinkRepository traceabilityLinkRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RequirementService(
            RequirementRepository requirementRepository,
            RequirementRelationRepository relationRepository,
            ProjectRepository projectRepository,
            QualityGateRepository qualityGateRepository,
            TraceabilityLinkRepository traceabilityLinkRepository,
            ApplicationEventPublisher eventPublisher) {
        this.requirementRepository = requirementRepository;
        this.relationRepository = relationRepository;
        this.projectRepository = projectRepository;
        this.qualityGateRepository = qualityGateRepository;
        this.traceabilityLinkRepository = traceabilityLinkRepository;
        this.eventPublisher = eventPublisher;
    }

    public Requirement create(CreateRequirementCommand command) {
        Project project = projectRepository
                .findById(command.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found: " + command.projectId()));

        String normalizedUid = command.uid().toUpperCase(java.util.Locale.ROOT);
        if (requirementRepository.existsByProjectIdAndUidIgnoreCase(project.getId(), normalizedUid)) {
            throw new ConflictException(
                    "Requirement with UID '" + command.uid() + "' already exists in project (case-insensitive)");
        }

        var requirement = new Requirement(project, normalizedUid, command.title(), command.statement());
        if (command.rationale() != null) {
            requirement.setRationale(command.rationale());
        }
        if (command.requirementType() != null) {
            requirement.setRequirementType(command.requirementType());
        }
        if (command.priority() != null) {
            requirement.setPriority(command.priority());
        }
        requirement.setWave(command.wave());
        return requirementRepository.save(requirement);
    }

    @Transactional(readOnly = true)
    public Requirement getById(UUID id) {
        return requirementRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + id));
    }

    @Transactional(readOnly = true)
    public Requirement getByUid(UUID projectId, String uid) {
        return requirementRepository
                .findByProjectIdAndUidIgnoreCase(projectId, uid)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + uid));
    }

    public Requirement update(UUID id, UpdateRequirementCommand command) {
        var requirement = getById(id);
        boolean textChanged = false;
        if (command.title() != null && !command.title().equals(requirement.getTitle())) {
            requirement.setTitle(command.title());
            textChanged = true;
        }
        if (command.statement() != null && !command.statement().equals(requirement.getStatement())) {
            requirement.setStatement(command.statement());
            textChanged = true;
        }
        if (command.rationale() != null && !command.rationale().equals(requirement.getRationale())) {
            requirement.setRationale(command.rationale());
            textChanged = true;
        }
        if (command.requirementType() != null) {
            requirement.setRequirementType(command.requirementType());
        }
        if (command.priority() != null) {
            requirement.setPriority(command.priority());
        }
        if (command.wave() != null) {
            requirement.setWave(command.wave());
        }
        var saved = requirementRepository.save(requirement);
        if (textChanged) {
            eventPublisher.publishEvent(new RequirementTextChangedEvent(saved.getId()));
        }
        return saved;
    }

    /*@ requires id != null;
    @ requires newStatus != null;
    @ ensures \result.getStatus() == newStatus;
    @ signals (NotFoundException e) !requirementRepository.existsById(id);
    @ signals (DomainValidationException e) !\old(getById(id)).getStatus().canTransitionTo(newStatus); @*/
    public Requirement transitionStatus(UUID id, Status newStatus) {
        var requirement = getById(id);
        validateDocumentationCoverageForActivation(id, requirement, newStatus);
        requirement.transitionStatus(newStatus);
        return requirementRepository.save(requirement);
    }

    public BulkTransitionResult bulkTransitionStatus(List<UUID> ids, Status newStatus) {
        var succeeded = new ArrayList<Requirement>();
        var failed = new ArrayList<BulkFailureDetail>();
        for (UUID id : ids) {
            Requirement requirement = null;
            try {
                requirement = getById(id);
                if (!requirement.getStatus().canTransitionTo(newStatus)) {
                    failed.add(new BulkFailureDetail(
                            id.toString(),
                            requirement.getUid(),
                            "Cannot transition from " + requirement.getStatus() + " to " + newStatus));
                    continue;
                }
                validateDocumentationCoverageForActivation(id, requirement, newStatus);
                requirement.transitionStatus(newStatus);
                succeeded.add(requirementRepository.save(requirement));
            } catch (NotFoundException e) {
                failed.add(new BulkFailureDetail(id.toString(), null, e.getMessage()));
            } catch (DomainValidationException e) {
                failed.add(new BulkFailureDetail(
                        id.toString(), requirement != null ? requirement.getUid() : null, e.getMessage()));
            }
        }
        return new BulkTransitionResult(succeeded, failed);
    }

    /*@ requires id != null;
    @ ensures \result.getStatus() == Status.ARCHIVED;
    @ ensures \result.getArchivedAt() != null;
    @ signals (NotFoundException e) !requirementRepository.existsById(id);
    @ signals (DomainValidationException e) !\old(getById(id)).getStatus().canTransitionTo(Status.ARCHIVED); @*/
    public Requirement archive(UUID id) {
        var requirement = getById(id);
        requirement.archive();
        return requirementRepository.save(requirement);
    }

    /*@ requires sourceId != null && targetId != null && relationType != null;
    @ requires !sourceId.equals(targetId);
    @ ensures \result != null;
    @ signals (NotFoundException e) !requirementRepository.existsById(sourceId)
    @                             || !requirementRepository.existsById(targetId);
    @ signals (DomainValidationException e) sourceId.equals(targetId); @*/
    public RequirementRelation createRelation(UUID sourceId, UUID targetId, RelationType relationType) {
        if (sourceId.equals(targetId)) {
            throw new DomainValidationException("A requirement cannot relate to itself");
        }
        if (relationRepository.existsBySourceIdAndTargetIdAndRelationType(sourceId, targetId, relationType)) {
            throw new ConflictException(
                    "Relation " + relationType + " already exists between " + sourceId + " and " + targetId);
        }
        var source = getById(sourceId);
        var target = getById(targetId);
        if (!source.getProject().getId().equals(target.getProject().getId())) {
            throw new DomainValidationException("Cannot create relation between requirements in different projects");
        }
        var relation = new RequirementRelation(source, target, relationType);
        return relationRepository.save(relation);
    }

    @Transactional(readOnly = true)
    public List<RequirementRelation> getRelations(UUID requirementId) {
        // Verify requirement exists
        getById(requirementId);
        var outgoing = relationRepository.findBySourceIdWithEntities(requirementId);
        var incoming = relationRepository.findByTargetIdWithEntities(requirementId);
        var combined = new ArrayList<RequirementRelation>(outgoing);
        combined.addAll(incoming);
        return combined;
    }

    @Transactional(readOnly = true)
    public Page<Requirement> list(UUID projectId, Pageable pageable, RequirementFilter filter) {
        var spec = RequirementSpecifications.fromFilter(projectId, filter);
        return requirementRepository.findAll(spec, pageable);
    }

    public Requirement clone(UUID sourceId, CloneRequirementCommand command) {
        var source = getById(sourceId);
        var project = source.getProject();

        String normalizedUid = command.newUid().toUpperCase(java.util.Locale.ROOT);
        if (requirementRepository.existsByProjectIdAndUidIgnoreCase(project.getId(), normalizedUid)) {
            throw new ConflictException(
                    "Requirement with UID '" + command.newUid() + "' already exists in project (case-insensitive)");
        }

        var clone = new Requirement(project, normalizedUid, source.getTitle(), source.getStatement());
        clone.setRationale(source.getRationale());
        clone.setRequirementType(source.getRequirementType());
        clone.setPriority(source.getPriority());
        clone.setWave(source.getWave());
        clone = requirementRepository.save(clone);

        if (command.copyRelations()) {
            for (var rel : relationRepository.findBySourceIdWithEntities(sourceId)) {
                var clonedRelation = new RequirementRelation(clone, rel.getTarget(), rel.getRelationType());
                relationRepository.save(clonedRelation);
            }
        }

        return clone;
    }

    public void deleteRelation(UUID requirementId, UUID relationId) {
        var relation = relationRepository
                .findById(relationId)
                .orElseThrow(() -> new NotFoundException("Relation not found: " + relationId));

        var sourceId = relation.getSource().getId();
        var targetId = relation.getTarget().getId();
        if (!requirementId.equals(sourceId) && !requirementId.equals(targetId)) {
            // Ownership mismatch must be indistinguishable from a missing relation: same message,
            // no leak of the relation's real parent requirement. Mirrors getRelationHistory,
            // getTraceabilityLinkHistory, and deleteLink.
            throw new NotFoundException("Relation not found: " + relationId);
        }
        relationRepository.delete(relation);
    }

    private void validateDocumentationCoverageForActivation(UUID id, Requirement requirement, Status newStatus) {
        if (requirement.getStatus() != Status.DRAFT || newStatus != Status.ACTIVE) {
            return;
        }
        boolean documentsCoverageGateActive =
                qualityGateRepository.existsByProjectIdAndEnabledTrueAndMetricTypeAndMetricParamAndScopeStatus(
                        requirement.getProject().getId(),
                        MetricType.COVERAGE,
                        LinkType.DOCUMENTS.name(),
                        Status.ACTIVE);
        if (!documentsCoverageGateActive
                || traceabilityLinkRepository.existsByRequirementIdAndLinkType(id, LinkType.DOCUMENTS)) {
            return;
        }
        throw new DomainValidationException(
                "Cannot transition requirement " + requirement.getUid()
                        + " from DRAFT to ACTIVE while Active DOCUMENTS Coverage is enabled: missing DOCUMENTS traceability link",
                "documentation_link_missing",
                Map.of(
                        "requirementId",
                        id.toString(),
                        "uid",
                        requirement.getUid(),
                        "missingLinkType",
                        LinkType.DOCUMENTS.name()));
    }
}
