package com.keplerops.groundcontrol.domain.riskscenarios.service;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentCampaign;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAppetiteProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentCampaignRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CampaignPhase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-T006: Risk Assessment Campaign service.
 *
 * <p>Owns the phase state machine via {@link RiskAssessmentCampaign#advanceTo(CampaignPhase)}
 * — moving the campaign past EVALUATION without a bound methodology profile is rejected
 * by the aggregate (L2 contract per CODING_STANDARDS). The service-side guard prevents
 * binding a different methodology once the campaign reaches EVALUATION (or beyond)
 * via {@link RiskAssessmentCampaign#setMethodologyProfile}.
 */
@Service
@Transactional
public class RiskAssessmentCampaignService {

    private final RiskAssessmentCampaignRepository repository;
    private final ProjectService projectService;
    private final MethodologyProfileRepository methodologyProfileRepository;
    private final RiskAppetiteProfileRepository riskAppetiteProfileRepository;

    public RiskAssessmentCampaignService(
            RiskAssessmentCampaignRepository repository,
            ProjectService projectService,
            MethodologyProfileRepository methodologyProfileRepository,
            RiskAppetiteProfileRepository riskAppetiteProfileRepository) {
        this.repository = repository;
        this.projectService = projectService;
        this.methodologyProfileRepository = methodologyProfileRepository;
        this.riskAppetiteProfileRepository = riskAppetiteProfileRepository;
    }

    public RiskAssessmentCampaign create(CreateRiskAssessmentCampaignCommand command) {
        var project = projectService.getById(command.projectId());
        if (repository.existsByProjectIdAndUid(project.getId(), command.uid())) {
            throw new ConflictException("Risk assessment campaign with UID " + command.uid() + " already exists");
        }
        var campaign = new RiskAssessmentCampaign(project, command.uid(), command.title());
        applyUpdates(
                campaign,
                project.getId(),
                command.owner(),
                command.objective(),
                command.methodologyProfileId(),
                command.appetiteProfileId(),
                command.scheduledStart(),
                command.scheduledEnd(),
                command.scope(),
                command.approvalMetadata(),
                command.scopedAssetIds());
        return repository.save(campaign);
    }

    @Transactional(readOnly = true)
    public List<RiskAssessmentCampaign> listByProject(UUID projectId) {
        return repository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public RiskAssessmentCampaign getById(UUID projectId, UUID id) {
        return repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Risk assessment campaign not found: " + id));
    }

    public RiskAssessmentCampaign update(UUID projectId, UUID id, UpdateRiskAssessmentCampaignCommand command) {
        // Resolve directly via the repository rather than via getById() to avoid
        // the @Transactional self-invocation pattern Sonar S6809 flags — the
        // proxy is bypassed and any per-method tx semantics would be lost. The
        // class-level @Transactional covers this method, so behaviour is unchanged.
        var campaign = repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Risk assessment campaign not found: " + id));
        if (command.title() != null) {
            campaign.setTitle(command.title());
        }
        applyUpdates(
                campaign,
                projectId,
                command.owner(),
                command.objective(),
                command.methodologyProfileId(),
                command.appetiteProfileId(),
                command.scheduledStart(),
                command.scheduledEnd(),
                command.scope(),
                command.approvalMetadata(),
                command.scopedAssetIds());
        return repository.save(campaign);
    }

    public RiskAssessmentCampaign advancePhase(UUID projectId, UUID id, CampaignPhase target) {
        // Resolve directly via the repository rather than via getById() to avoid
        // the @Transactional self-invocation pattern Sonar S6809 flags — the
        // proxy is bypassed and any per-method tx semantics would be lost. The
        // class-level @Transactional covers this method, so behaviour is unchanged.
        var campaign = repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Risk assessment campaign not found: " + id));
        campaign.advanceTo(target);
        return repository.save(campaign);
    }

    public void delete(UUID projectId, UUID id) {
        // Resolve directly via the repository rather than via getById() to avoid
        // the @Transactional self-invocation pattern Sonar S6809 flags — the
        // proxy is bypassed and any per-method tx semantics would be lost. The
        // class-level @Transactional covers this method, so behaviour is unchanged.
        var campaign = repository
                .findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Risk assessment campaign not found: " + id));
        repository.delete(campaign);
    }

    @SuppressWarnings("java:S107") // shared updater mirrors the command DTO surface
    private void applyUpdates(
            RiskAssessmentCampaign campaign,
            UUID projectId,
            String owner,
            String objective,
            UUID methodologyProfileId,
            UUID appetiteProfileId,
            Instant scheduledStart,
            Instant scheduledEnd,
            Map<String, Object> scope,
            Map<String, Object> approvalMetadata,
            List<String> scopedAssetIds) {
        if (owner != null) {
            campaign.setOwner(owner);
        }
        if (objective != null) {
            campaign.setObjective(objective);
        }
        if (methodologyProfileId != null) {
            var profile = methodologyProfileRepository
                    .findByIdAndProjectId(methodologyProfileId, projectId)
                    .orElseThrow(() -> new NotFoundException("Methodology profile not found: " + methodologyProfileId));
            campaign.setMethodologyProfile(profile);
        }
        if (appetiteProfileId != null) {
            var appetite = riskAppetiteProfileRepository
                    .findByIdAndProjectId(appetiteProfileId, projectId)
                    .orElseThrow(() -> new NotFoundException("Risk appetite profile not found: " + appetiteProfileId));
            campaign.setAppetiteProfile(appetite);
        }
        if (scheduledStart != null) {
            campaign.setScheduledStart(scheduledStart);
        }
        if (scheduledEnd != null) {
            campaign.setScheduledEnd(scheduledEnd);
        }
        if (scope != null) {
            campaign.setScope(scope);
        }
        if (approvalMetadata != null) {
            campaign.setApprovalMetadata(approvalMetadata);
        }
        if (scopedAssetIds != null) {
            campaign.setScopedAssetIds(scopedAssetIds);
        }
    }
}
