package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentCampaign;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CampaignPhase;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import org.junit.jupiter.api.Test;

class RiskAssessmentCampaignTest {

    private final Project project = new Project("p", "P");

    @Test
    void startsInPlanning() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        assertThat(campaign.getPhase()).isEqualTo(CampaignPhase.PLANNING);
    }

    @Test
    void canAdvanceForwardOneStep() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        campaign.advanceTo(CampaignPhase.IDENTIFICATION);
        assertThat(campaign.getPhase()).isEqualTo(CampaignPhase.IDENTIFICATION);
    }

    @Test
    void cannotSkipPhases() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        assertThatThrownBy(() -> campaign.advanceTo(CampaignPhase.EVALUATION))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void cannotMoveBackward() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        campaign.setMethodologyProfile(
                new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR));
        campaign.advanceTo(CampaignPhase.IDENTIFICATION);
        campaign.advanceTo(CampaignPhase.ANALYSIS);
        campaign.advanceTo(CampaignPhase.EVALUATION);
        assertThatThrownBy(() -> campaign.advanceTo(CampaignPhase.ANALYSIS))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void cannotReachEvaluationWithoutMethodologyBinding() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        campaign.advanceTo(CampaignPhase.IDENTIFICATION);
        campaign.advanceTo(CampaignPhase.ANALYSIS);
        assertThatThrownBy(() -> campaign.advanceTo(CampaignPhase.EVALUATION))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("methodology profile");
    }

    @Test
    void methodologyProfileLockedAfterEvaluation() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        var profileA = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        var profileB =
                new MethodologyProfile(project, "NIST_SP800_30_R1", "NIST", "1", MethodologyFamily.NIST_SP800_30_R1);
        // Stamp IDs so the locked-equality check uses them.
        com.keplerops.groundcontrol.TestUtil.setField(profileA, "id", java.util.UUID.randomUUID());
        com.keplerops.groundcontrol.TestUtil.setField(profileB, "id", java.util.UUID.randomUUID());
        campaign.setMethodologyProfile(profileA);
        campaign.advanceTo(CampaignPhase.IDENTIFICATION);
        campaign.advanceTo(CampaignPhase.ANALYSIS);
        campaign.advanceTo(CampaignPhase.EVALUATION);
        assertThatThrownBy(() -> campaign.setMethodologyProfile(profileB))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void canCancelFromPlanning() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        campaign.advanceTo(CampaignPhase.CLOSED);
        assertThat(campaign.getPhase()).isEqualTo(CampaignPhase.CLOSED);
    }

    @Test
    void closedIsTerminal() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        campaign.advanceTo(CampaignPhase.CLOSED);
        assertThatThrownBy(() -> campaign.advanceTo(CampaignPhase.PLANNING))
                .isInstanceOf(DomainValidationException.class);
    }
}
