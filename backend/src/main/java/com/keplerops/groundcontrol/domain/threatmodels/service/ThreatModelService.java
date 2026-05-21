package com.keplerops.groundcontrol.domain.threatmodels.service;

import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ThreatModelService {

    private static final Logger log = LoggerFactory.getLogger(ThreatModelService.class);

    private final ThreatModelRepository threatModelRepository;
    private final ThreatModelLinkRepository threatModelLinkRepository;
    private final ProjectService projectService;
    private final AssetLinkRepository assetLinkRepository;
    private final RiskScenarioLinkRepository riskScenarioLinkRepository;
    private final RequirementRepository requirementRepository;

    public ThreatModelService(
            ThreatModelRepository threatModelRepository,
            ThreatModelLinkRepository threatModelLinkRepository,
            ProjectService projectService,
            AssetLinkRepository assetLinkRepository,
            RiskScenarioLinkRepository riskScenarioLinkRepository,
            RequirementRepository requirementRepository) {
        this.threatModelRepository = threatModelRepository;
        this.threatModelLinkRepository = threatModelLinkRepository;
        this.projectService = projectService;
        this.assetLinkRepository = assetLinkRepository;
        this.riskScenarioLinkRepository = riskScenarioLinkRepository;
        this.requirementRepository = requirementRepository;
    }

    public ThreatModel create(CreateThreatModelCommand command) {
        var project = projectService.getById(command.projectId());

        if (threatModelRepository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("Threat model with UID '" + command.uid() + "' already exists in project "
                    + project.getIdentifier());
        }

        var threatModel = new ThreatModel(
                project,
                command.uid(),
                command.title(),
                command.threatSource(),
                command.threatEvent(),
                command.effect());
        threatModel.setStride(command.stride());
        threatModel.setNarrative(command.narrative());
        threatModel.setCreatedBy(ActorHolder.get());

        var saved = threatModelRepository.save(threatModel);
        log.info(
                "threat_model_created: project={} uid={} title={} id={}",
                project.getIdentifier(),
                saved.getUid(),
                saved.getTitle(),
                saved.getId());
        return saved;
    }

    public ThreatModel update(UUID projectId, UUID id, UpdateThreatModelCommand command) {
        var threatModel = findByIdOrThrow(projectId, id);

        // Required fields: present-and-non-blank or absent. Reject blank strings so
        // partial updates can't corrupt records that the create path would refuse.
        rejectBlankIfPresent("title", command.title());
        rejectBlankIfPresent("threatSource", command.threatSource());
        rejectBlankIfPresent("threatEvent", command.threatEvent());
        rejectBlankIfPresent("effect", command.effect());

        if (command.title() != null) {
            threatModel.setTitle(command.title());
        }
        if (command.threatSource() != null) {
            threatModel.setThreatSource(command.threatSource());
        }
        if (command.threatEvent() != null) {
            threatModel.setThreatEvent(command.threatEvent());
        }
        if (command.effect() != null) {
            threatModel.setEffect(command.effect());
        }

        // Optional fields: explicit clear flag wins over a non-null value so callers
        // can null an incorrect classification or stale narrative without resorting
        // to direct database edits.
        if (command.clearStride()) {
            threatModel.setStride(null);
        } else if (command.stride() != null) {
            threatModel.setStride(command.stride());
        }
        if (command.clearNarrative()) {
            threatModel.setNarrative(null);
        } else if (command.narrative() != null) {
            threatModel.setNarrative(command.narrative());
        }

        var saved = threatModelRepository.save(threatModel);
        log.info("threat_model_updated: id={} uid={}", saved.getId(), saved.getUid());
        return saved;
    }

    private static void rejectBlankIfPresent(String fieldName, String value) {
        if (value != null && value.isBlank()) {
            throw new com.keplerops.groundcontrol.domain.exception.DomainValidationException(
                    fieldName + " must not be blank when provided",
                    "validation_error",
                    java.util.Map.of("field", fieldName));
        }
    }

    @Transactional(readOnly = true)
    public ThreatModel getById(UUID projectId, UUID id) {
        return findByIdOrThrow(projectId, id);
    }

    @Transactional(readOnly = true)
    public ThreatModel getByUid(String uid, UUID projectId) {
        return threatModelRepository
                .findByProjectIdAndUid(projectId, uid)
                .orElseThrow(() -> new NotFoundException("Threat model not found: " + uid));
    }

    @Transactional(readOnly = true)
    public List<ThreatModel> listByProject(UUID projectId) {
        return threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public ThreatModel transitionStatus(UUID projectId, UUID id, ThreatModelStatus newStatus) {
        var threatModel = findByIdOrThrow(projectId, id);
        threatModel.transitionStatus(newStatus);
        var saved = threatModelRepository.save(threatModel);
        log.info(
                "threat_model_status_changed: id={} uid={} status={}",
                saved.getId(),
                saved.getUid(),
                saved.getStatus());
        return saved;
    }

    public void delete(UUID projectId, UUID id) {
        var threatModel = findByIdOrThrow(projectId, id);

        var assetUids = assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                AssetLinkTargetType.THREAT_MODEL_ENTRY, id, projectId);
        var scenarioUids = riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                RiskScenarioLinkTargetType.THREAT_MODEL, id, projectId);
        if (!assetUids.isEmpty() || !scenarioUids.isEmpty()) {
            Map<String, Serializable> detail = new LinkedHashMap<>();
            detail.put("threatModelUid", threatModel.getUid());
            detail.put("assetCount", assetUids.size());
            detail.put("scenarioCount", scenarioUids.size());
            detail.put("assetUids", new java.util.ArrayList<>(assetUids));
            detail.put("scenarioUids", new java.util.ArrayList<>(scenarioUids));
            throw new ConflictException(
                    "Threat model " + threatModel.getUid()
                            + " cannot be deleted while reverse links exist. Remove the AssetLink and"
                            + " RiskScenarioLink references first, then retry.",
                    "threat_model_referenced",
                    detail);
        }

        // Delete outbound links through the repository before the parent so Envers
        // writes delete revisions for each ThreatModelLink. The migration's FK has
        // ON DELETE CASCADE only as a defense-in-depth fallback; relying on it
        // would bypass Hibernate and leave threat_model_link_audit incomplete
        // for the parent-delete path.
        var outboundLinks = threatModelLinkRepository.findByThreatModelId(id);
        threatModelLinkRepository.deleteAll(outboundLinks);
        threatModelRepository.delete(threatModel);
        log.info(
                "threat_model_deleted: id={} uid={} outbound_links_deleted={}",
                threatModel.getId(),
                threatModel.getUid(),
                outboundLinks.size());
    }

    @Transactional(readOnly = true)
    public List<Requirement> findLinkedRequirements(UUID projectId, UUID id) {
        findByIdOrThrow(projectId, id);
        var links =
                threatModelLinkRepository.findByThreatModelIdAndTargetType(id, ThreatModelLinkTargetType.REQUIREMENT);
        return links.stream()
                .map(link -> requirementRepository.findByIdAndProjectId(link.getTargetEntityId(), projectId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private ThreatModel findByIdOrThrow(UUID projectId, UUID id) {
        return threatModelRepository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Threat model not found: " + id));
    }
}
