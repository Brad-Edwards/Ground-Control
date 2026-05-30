package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentCampaign;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAppetiteProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentCampaignRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateRiskAssessmentCampaignCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAssessmentCampaignService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateRiskAssessmentCampaignCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CampaignPhase;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentCampaignServiceTest {

    @Mock
    private RiskAssessmentCampaignRepository repository;

    @Mock
    private ProjectService projectService;

    @Mock
    private MethodologyProfileRepository methodologyProfileRepository;

    @Mock
    private RiskAppetiteProfileRepository riskAppetiteProfileRepository;

    @InjectMocks
    private RiskAssessmentCampaignService service;

    private Project project;
    private UUID projectId;
    private UUID campaignId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        campaignId = UUID.randomUUID();
    }

    private RiskAssessmentCampaign makeCampaign() {
        var campaign = new RiskAssessmentCampaign(project, "CMP-001", "FY26 Q1 Risk Campaign");
        setField(campaign, "id", campaignId);
        return campaign;
    }

    @Test
    void createBuildsCampaignAndPersists() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "CMP-001")).thenReturn(false);
        when(repository.save(any(RiskAssessmentCampaign.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create(new CreateRiskAssessmentCampaignCommand(
                projectId,
                "CMP-001",
                "FY26 Q1 Risk Campaign",
                "CISO",
                "Q1 enterprise risk review",
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        assertThat(result.getUid()).isEqualTo("CMP-001");
        assertThat(result.getTitle()).isEqualTo("FY26 Q1 Risk Campaign");
        assertThat(result.getOwner()).isEqualTo("CISO");
        assertThat(result.getPhase()).isEqualTo(CampaignPhase.PLANNING);
    }

    @Test
    void createRejectsDuplicateUid() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "CMP-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateRiskAssessmentCampaignCommand(
                        projectId, "CMP-001", "Title", null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CMP-001");
    }

    @Test
    void getByIdReturnsWhenPresent() {
        var campaign = makeCampaign();
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.of(campaign));

        var result = service.getById(projectId, campaignId);

        assertThat(result.getUid()).isEqualTo("CMP-001");
    }

    @Test
    void getByIdThrowsNotFoundWhenAbsent() {
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(projectId, campaignId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(campaignId.toString());
    }

    @Test
    void updateMutatesFieldsAndPersists() {
        var campaign = makeCampaign();
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.of(campaign));
        when(repository.save(campaign)).thenReturn(campaign);

        var result = service.update(
                projectId,
                campaignId,
                new UpdateRiskAssessmentCampaignCommand(
                        "Updated Title", "New Owner", "Updated objective", null, null, null, null, null, null, null));

        assertThat(result.getTitle()).isEqualTo("Updated Title");
        assertThat(result.getOwner()).isEqualTo("New Owner");
        assertThat(result.getObjective()).isEqualTo("Updated objective");
    }

    @Test
    void updateThrowsNotFoundWhenCampaignAbsent() {
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                        projectId,
                        campaignId,
                        new UpdateRiskAssessmentCampaignCommand(
                                null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateBindsMethodologyProfileWhenIdProvided() {
        var campaign = makeCampaign();
        var methodologyId = UUID.randomUUID();
        var methodology = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        setField(methodology, "id", methodologyId);
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.of(campaign));
        when(methodologyProfileRepository.findByIdAndProjectId(methodologyId, projectId))
                .thenReturn(Optional.of(methodology));
        when(repository.save(campaign)).thenReturn(campaign);

        var result = service.update(
                projectId,
                campaignId,
                new UpdateRiskAssessmentCampaignCommand(
                        null, null, null, methodologyId, null, null, null, null, null, null));

        assertThat(result.getMethodologyProfile()).isEqualTo(methodology);
    }

    @Test
    void advancePhaseTransitionsCampaignState() {
        var campaign = makeCampaign();
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.of(campaign));
        when(repository.save(campaign)).thenReturn(campaign);

        var result = service.advancePhase(projectId, campaignId, CampaignPhase.IDENTIFICATION);

        assertThat(result.getPhase()).isEqualTo(CampaignPhase.IDENTIFICATION);
    }

    @Test
    void advancePhaseRejectsInvalidTransition() {
        var campaign = makeCampaign();
        // Campaign starts in PLANNING; jumping to TREATMENT is invalid
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.advancePhase(projectId, campaignId, CampaignPhase.TREATMENT))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("transition");
    }

    @Test
    void advancePhaseThrowsNotFoundWhenCampaignAbsent() {
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.advancePhase(projectId, campaignId, CampaignPhase.IDENTIFICATION))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRemovesCampaign() {
        var campaign = makeCampaign();
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.of(campaign));

        service.delete(projectId, campaignId);

        verify(repository).delete(campaign);
    }

    @Test
    void deleteThrowsNotFoundWhenAbsent() {
        when(repository.findByIdAndProjectId(campaignId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(projectId, campaignId)).isInstanceOf(NotFoundException.class);
    }
}
