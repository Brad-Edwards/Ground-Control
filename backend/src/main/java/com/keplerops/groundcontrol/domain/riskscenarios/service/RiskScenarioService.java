package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository;
import com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository;
import com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RiskScenarioService {

    private static final Logger log = LoggerFactory.getLogger(RiskScenarioService.class);

    private final RiskScenarioRepository riskScenarioRepository;
    private final RiskScenarioLinkRepository riskScenarioLinkRepository;
    private final FindingLinkRepository findingLinkRepository;
    private final AuditLinkRepository auditLinkRepository;
    private final ProjectService projectService;
    private final RequirementRepository requirementRepository;

    public RiskScenarioService(
            RiskScenarioRepository riskScenarioRepository,
            RiskScenarioLinkRepository riskScenarioLinkRepository,
            FindingLinkRepository findingLinkRepository,
            AuditLinkRepository auditLinkRepository,
            ProjectService projectService,
            RequirementRepository requirementRepository) {
        this.riskScenarioRepository = riskScenarioRepository;
        this.riskScenarioLinkRepository = riskScenarioLinkRepository;
        this.findingLinkRepository = findingLinkRepository;
        this.auditLinkRepository = auditLinkRepository;
        this.projectService = projectService;
        this.requirementRepository = requirementRepository;
    }

    public RiskScenario create(CreateRiskScenarioCommand command) {
        var project = projectService.getById(command.projectId());

        if (riskScenarioRepository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("Risk scenario with UID '" + command.uid() + "' already exists in project "
                    + project.getIdentifier());
        }

        var scenario = new RiskScenario(
                project,
                command.uid(),
                command.title(),
                command.threatSource(),
                command.threatEvent(),
                command.affectedObject(),
                command.consequence());
        scenario.setTimeHorizon(command.timeHorizon());
        scenario.setCreatedBy(ActorHolder.get());
        if (command.vulnerability() != null) {
            scenario.setVulnerability(command.vulnerability());
        }

        var saved = riskScenarioRepository.save(scenario);
        log.info(
                "risk_scenario_created: project={} uid={} title={} id={}",
                project.getIdentifier(),
                saved.getUid(),
                saved.getTitle(),
                saved.getId());
        return saved;
    }

    public RiskScenario update(UUID projectId, UUID id, UpdateRiskScenarioCommand command) {
        var scenario = findByIdOrThrow(projectId, id);

        // Required-on-create fields must reject blank strings when present, or a
        // partial update could corrupt records the create path would refuse.
        // Mirrors ThreatModelService.update (issue #876).
        rejectBlankIfPresent("title", command.title());
        rejectBlankIfPresent("threatSource", command.threatSource());
        rejectBlankIfPresent("threatEvent", command.threatEvent());
        rejectBlankIfPresent("affectedObject", command.affectedObject());
        rejectBlankIfPresent("consequence", command.consequence());
        rejectBlankIfPresent("timeHorizon", command.timeHorizon());

        if (command.title() != null) {
            scenario.setTitle(command.title());
        }
        if (command.threatSource() != null) {
            scenario.setThreatSource(command.threatSource());
        }
        if (command.threatEvent() != null) {
            scenario.setThreatEvent(command.threatEvent());
        }
        if (command.affectedObject() != null) {
            scenario.setAffectedObject(command.affectedObject());
        }
        if (command.vulnerability() != null) {
            scenario.setVulnerability(command.vulnerability());
        }
        if (command.consequence() != null) {
            scenario.setConsequence(command.consequence());
        }
        if (command.timeHorizon() != null) {
            scenario.setTimeHorizon(command.timeHorizon());
        }

        var saved = riskScenarioRepository.save(scenario);
        log.info("risk_scenario_updated: id={} uid={}", saved.getId(), saved.getUid());
        return saved;
    }

    private static void rejectBlankIfPresent(String fieldName, String value) {
        if (value != null && value.isBlank()) {
            throw new DomainValidationException(
                    fieldName + " must not be blank when provided",
                    "validation_error",
                    java.util.Map.of("field", fieldName));
        }
    }

    @Deprecated(forRemoval = false)
    public RiskScenario update(UUID id, UpdateRiskScenarioCommand command) {
        var scenario = riskScenarioRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Risk scenario not found: " + id));
        return update(scenario.getProject().getId(), id, command);
    }

    @Transactional(readOnly = true)
    public RiskScenario getById(UUID projectId, UUID id) {
        return findByIdOrThrow(projectId, id);
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public RiskScenario getById(UUID id) {
        return riskScenarioRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Risk scenario not found: " + id));
    }

    @Transactional(readOnly = true)
    public RiskScenario getByUid(String uid, UUID projectId) {
        return riskScenarioRepository
                .findByProjectIdAndUid(projectId, uid)
                .orElseThrow(() -> new NotFoundException("Risk scenario not found: " + uid));
    }

    @Transactional(readOnly = true)
    public List<RiskScenario> listByProject(UUID projectId) {
        return riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public RiskScenario transitionStatus(UUID projectId, UUID id, RiskScenarioStatus newStatus) {
        var scenario = findByIdOrThrow(projectId, id);
        scenario.transitionStatus(newStatus);
        var saved = riskScenarioRepository.save(scenario);
        log.info(
                "risk_scenario_status_changed: id={} uid={} status={}",
                saved.getId(),
                saved.getUid(),
                saved.getStatus());
        return saved;
    }

    @Deprecated(forRemoval = false)
    public RiskScenario transitionStatus(UUID id, RiskScenarioStatus newStatus) {
        var scenario = getById(id);
        return transitionStatus(scenario.getProject().getId(), id, newStatus);
    }

    public void delete(UUID projectId, UUID id) {
        var scenario = findByIdOrThrow(projectId, id);

        // Reject delete while inbound FindingLink rows still target this scenario.
        // FindingLink.targetEntityId is not a database FK, so a delete here would
        // leave dangling rows that FindingLinkController.list and the graph
        // projection would happily surface (ADR-038 / cycle-3 codex review).
        var inboundFindingUids = findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                FindingLinkTargetType.RISK_SCENARIO, id, projectId);
        if (!inboundFindingUids.isEmpty()) {
            java.util.Map<String, java.io.Serializable> detail = new java.util.LinkedHashMap<>();
            detail.put("riskScenarioUid", scenario.getUid());
            detail.put("findingCount", inboundFindingUids.size());
            detail.put("findingUids", new java.util.ArrayList<>(inboundFindingUids));
            throw new ConflictException(
                    "Risk scenario " + scenario.getUid()
                            + " cannot be deleted while inbound FindingLink references exist. Remove the"
                            + " FindingLink references first, then retry.",
                    "risk_scenario_referenced",
                    detail);
        }

        var inboundAuditUids = auditLinkRepository.findAuditUidsByTargetTypeAndTargetEntityIdAndProjectId(
                AuditLinkTargetType.RISK_SCENARIO, id, projectId);
        if (!inboundAuditUids.isEmpty()) {
            java.util.Map<String, java.io.Serializable> detail = new java.util.LinkedHashMap<>();
            detail.put("riskScenarioUid", scenario.getUid());
            detail.put("auditCount", inboundAuditUids.size());
            detail.put("auditUids", new java.util.ArrayList<>(inboundAuditUids));
            throw new ConflictException(
                    "Risk scenario " + scenario.getUid()
                            + " cannot be deleted while inbound AuditLink references exist. Remove the"
                            + " AuditLink references first, then retry.",
                    "risk_scenario_referenced",
                    detail);
        }

        // Delete outbound links through the repository before the parent so Envers
        // writes delete revisions for each RiskScenarioLink. The migration's FK has
        // ON DELETE CASCADE only as a defense-in-depth fallback; relying on it
        // would bypass Hibernate and leave risk_scenario_link_audit incomplete
        // for the parent-delete path.
        var outboundLinks = riskScenarioLinkRepository.findByRiskScenarioId(id);
        riskScenarioLinkRepository.deleteAll(outboundLinks);
        riskScenarioRepository.delete(scenario);
        log.info(
                "risk_scenario_deleted: id={} uid={} outbound_links_deleted={}",
                scenario.getId(),
                scenario.getUid(),
                outboundLinks.size());
    }

    @Deprecated(forRemoval = false)
    public void delete(UUID id) {
        var scenario = getById(id);
        delete(scenario.getProject().getId(), id);
    }

    @Transactional(readOnly = true)
    public List<Requirement> findLinkedRequirements(UUID projectId, UUID id) {
        findByIdOrThrow(projectId, id);
        var links = riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                id, RiskScenarioLinkTargetType.REQUIREMENT);
        return links.stream()
                .map(link -> requirementRepository.findByIdAndProjectId(link.getTargetEntityId(), projectId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    @Deprecated(forRemoval = false)
    @Transactional(readOnly = true)
    public List<Requirement> findLinkedRequirements(UUID id) {
        var scenario = getById(id);
        return findLinkedRequirements(scenario.getProject().getId(), id);
    }

    private RiskScenario findByIdOrThrow(UUID projectId, UUID id) {
        return riskScenarioRepository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Risk scenario not found: " + id));
    }
}
