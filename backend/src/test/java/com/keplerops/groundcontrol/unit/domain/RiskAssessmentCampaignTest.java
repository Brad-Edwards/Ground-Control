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

    // Cancel-from-PLANNING without any methodology bound: the advanceTo guard
    // for methodologyLocked() excludes CLOSED, so the cancel path must succeed
    // even though no methodology profile is bound. Asserts the no-methodology
    // cancel path explicitly so a future change tightening the guard to all
    // transitions (including PLANNING → CLOSED) would fail this test.
    @Test
    void canCancelFromPlanningWithoutMethodology() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        assertThat(campaign.getMethodologyProfile()).isNull();
        campaign.advanceTo(CampaignPhase.CLOSED);
        assertThat(campaign.getPhase()).isEqualTo(CampaignPhase.CLOSED);
        // Methodology binding remains null — the cancel path did not require
        // or fabricate one.
        assertThat(campaign.getMethodologyProfile()).isNull();
    }

    // GC-T006 audit-trail integrity: a campaign that reaches CLOSED with no
    // prior methodology binding can still have one set later (the
    // setMethodologyProfile short-circuit "this.methodologyProfile != null"
    // skips the lock check when the prior value is null). This pins down the
    // intentional asymmetry between methodologyImmutable() (locks at CLOSED)
    // and methodologyLocked() (used by advanceTo, excludes CLOSED).
    @Test
    void canSetMethodologyOnClosedCampaignWithoutPriorBinding() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        campaign.advanceTo(CampaignPhase.CLOSED);
        var profile = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        com.keplerops.groundcontrol.TestUtil.setField(profile, "id", java.util.UUID.randomUUID());
        // No throw — the lock only fires when a prior binding exists.
        campaign.setMethodologyProfile(profile);
        assertThat(campaign.getMethodologyProfile()).isSameAs(profile);
    }

    // GC-T006 audit-trail integrity: a campaign that bound a methodology and
    // then closed cannot swap to a different methodology — the audit trail of
    // which methodology produced the campaign's results stays frozen.
    @Test
    void cannotSwapMethodologyOnClosedCampaignWithPriorBinding() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        var profileA = new MethodologyProfile(project, "FAIR_V3_0", "FAIR", "3.0", MethodologyFamily.FAIR);
        var profileB =
                new MethodologyProfile(project, "NIST_SP800_30_R1", "NIST", "1", MethodologyFamily.NIST_SP800_30_R1);
        com.keplerops.groundcontrol.TestUtil.setField(profileA, "id", java.util.UUID.randomUUID());
        com.keplerops.groundcontrol.TestUtil.setField(profileB, "id", java.util.UUID.randomUUID());
        campaign.setMethodologyProfile(profileA);
        campaign.advanceTo(CampaignPhase.IDENTIFICATION);
        campaign.advanceTo(CampaignPhase.ANALYSIS);
        campaign.advanceTo(CampaignPhase.EVALUATION);
        campaign.advanceTo(CampaignPhase.TREATMENT);
        campaign.advanceTo(CampaignPhase.CLOSED);
        assertThatThrownBy(() -> campaign.setMethodologyProfile(profileB))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void closedIsTerminal() {
        var campaign = new RiskAssessmentCampaign(project, "C-1", "C1");
        campaign.advanceTo(CampaignPhase.CLOSED);
        assertThatThrownBy(() -> campaign.advanceTo(CampaignPhase.PLANNING))
                .isInstanceOf(DomainValidationException.class);
    }
}
