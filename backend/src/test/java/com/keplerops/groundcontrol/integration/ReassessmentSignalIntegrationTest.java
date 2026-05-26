package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.assets.model.AssetLink;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.service.AssetService;
import com.keplerops.groundcontrol.domain.assets.service.CreateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.service.UpdateAssetCommand;
import com.keplerops.groundcontrol.domain.assets.state.AssetCriticality;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkType;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.controls.model.ControlLink;
import com.keplerops.groundcontrol.domain.controls.repository.ControlLinkRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.service.ControlService;
import com.keplerops.groundcontrol.domain.controls.service.CreateControlCommand;
import com.keplerops.groundcontrol.domain.controls.service.UpdateControlCommand;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkType;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateTreatmentPlanCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.TreatmentPlanService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GC-T004 / C8 (#863): end-to-end coverage of the reassessment signal listener
 * through the real {@code ApplicationContext} and {@code @TransactionalEventListener}.
 * Each test exercises one mutation path covered by the publisher matrix and asserts
 * that the listener wrote {@code reassessmentRequiredAt} on the affected assessment
 * row after the publishing transaction commits.
 */
class ReassessmentSignalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private MethodologyProfileRepository methodologyProfileRepository;

    @Autowired
    private RiskScenarioRepository riskScenarioRepository;

    @Autowired
    private RiskRegisterRecordRepository riskRegisterRecordRepository;

    @Autowired
    private RiskAssessmentResultRepository assessmentRepository;

    @Autowired
    private OperationalAssetRepository assetRepository;

    @Autowired
    private AssetLinkRepository assetLinkRepository;

    @Autowired
    private AssetService assetService;

    @Autowired
    private ControlRepository controlRepository;

    @Autowired
    private ControlLinkRepository controlLinkRepository;

    @Autowired
    private ControlService controlService;

    @Autowired
    private TreatmentPlanRepository treatmentPlanRepository;

    @Autowired
    private TreatmentPlanService treatmentPlanService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Project project;
    private MethodologyProfile profile;

    @BeforeEach
    void seedProjectAndProfile() {
        transactionTemplate.executeWithoutResult(status -> {
            project = projectRepository.save(new Project("gc-c8-" + UUID.randomUUID(), "GC-T004 C8 test project"));
            profile = methodologyProfileRepository.save(
                    new MethodologyProfile(project, "MP-C8", "Profile", "1.0", MethodologyFamily.CUSTOM));
        });
    }

    @Test
    void treatmentPlanTransitionMarksAssessmentResultsLinkedThroughRegisterRecord() {
        var fixture = seedScenarioRecordAssessment("RS-TP", "RR-TP");
        var plan = createPlan("TP-TRANS", fixture.record(), null);

        treatmentPlanService.transitionStatus(project.getId(), plan.getId(), TreatmentPlanStatus.IN_PROGRESS);

        flushAndExpire();
        var refreshed =
                assessmentRepository.findById(fixture.assessment().getId()).orElseThrow();
        assertThat(refreshed.getReassessmentRequiredAt()).isNotNull();
    }

    @Test
    void treatmentPlanCreateWithLifecycleStatusTransitionMarksAssessmentResults() {
        // Constructor seeds plan in PLANNED then transitionStatus runs to IN_PROGRESS
        // when status field is supplied — same publisher path as the explicit transition.
        var fixture = seedScenarioRecordAssessment("RS-TPC", "RR-TPC");
        var plan = treatmentPlanService.create(new CreateTreatmentPlanCommand(
                project.getId(),
                "TP-CREATE",
                "Plan",
                fixture.record().getId(),
                fixture.scenario().getId(),
                TreatmentStrategy.MITIGATE,
                "owner",
                null,
                null,
                TreatmentPlanStatus.IN_PROGRESS,
                null,
                null,
                null,
                null));

        flushAndExpire();
        var refreshed =
                assessmentRepository.findById(fixture.assessment().getId()).orElseThrow();
        assertThat(refreshed.getReassessmentRequiredAt()).isNotNull();
        assertThat(plan.getId()).isNotNull();
    }

    @Test
    void assetUpdateOfRiskBearingFieldMarksLinkedAssessmentResult() {
        var fixture = seedScenarioRecordAssessment("RS-ASSET", "RR-ASSET");
        var asset = assetService.create(
                new CreateAssetCommand(project.getId(), "ASSET-RB", "Web API", "edge api", AssetType.SERVICE));
        // Link asset → scenario so the listener walks AssetLink → RISK_SCENARIO.
        assetLinkRepository.save(new AssetLink(
                asset, AssetLinkTargetType.RISK_SCENARIO, fixture.scenario().getId(), null, AssetLinkType.ASSOCIATED));

        assetService.update(
                project.getId(),
                asset.getId(),
                new UpdateAssetCommand(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        AssetCriticality.CRITICAL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false));

        flushAndExpire();
        var refreshed =
                assessmentRepository.findById(fixture.assessment().getId()).orElseThrow();
        assertThat(refreshed.getReassessmentRequiredAt()).isNotNull();
    }

    @Test
    void assetArchiveMarksLinkedAssessmentResult() {
        var fixture = seedScenarioRecordAssessment("RS-ARCH", "RR-ARCH");
        var asset = assetService.create(
                new CreateAssetCommand(project.getId(), "ASSET-ARCH", "API", "to archive", AssetType.SERVICE));
        assetLinkRepository.save(new AssetLink(
                asset,
                AssetLinkTargetType.RISK_REGISTER_RECORD,
                fixture.record().getId(),
                null,
                AssetLinkType.ASSOCIATED));

        assetService.archive(project.getId(), asset.getId());

        flushAndExpire();
        var refreshed =
                assessmentRepository.findById(fixture.assessment().getId()).orElseThrow();
        assertThat(refreshed.getReassessmentRequiredAt()).isNotNull();
    }

    @Test
    void controlTransitionMarksLinkedAssessmentResult() {
        var fixture = seedScenarioRecordAssessment("RS-CTRL", "RR-CTRL");
        var control = controlService.create(new CreateControlCommand(
                project.getId(),
                "CTRL-1",
                "Access Control",
                ControlFunction.PREVENTIVE,
                "desc",
                "obj",
                "owner",
                "scope",
                Map.of(),
                Map.of("rating", "LOW"),
                "cat",
                "src"));
        controlLinkRepository.save(new ControlLink(
                control,
                ControlLinkTargetType.RISK_REGISTER_RECORD,
                fixture.record().getId(),
                null,
                ControlLinkType.MITIGATES));

        controlService.transitionStatus(project.getId(), control.getId(), ControlStatus.PROPOSED);

        flushAndExpire();
        var refreshed =
                assessmentRepository.findById(fixture.assessment().getId()).orElseThrow();
        assertThat(refreshed.getReassessmentRequiredAt()).isNotNull();
    }

    @Test
    void controlEffectivenessUpdateMarksLinkedAssessmentResult() {
        var fixture = seedScenarioRecordAssessment("RS-EFF", "RR-EFF");
        var control = controlService.create(new CreateControlCommand(
                project.getId(),
                "CTRL-EFF",
                "Effectiveness",
                ControlFunction.PREVENTIVE,
                "desc",
                "obj",
                "owner",
                "scope",
                Map.of(),
                Map.of("rating", "LOW"),
                "cat",
                "src"));
        controlLinkRepository.save(new ControlLink(
                control,
                ControlLinkTargetType.RISK_SCENARIO,
                fixture.scenario().getId(),
                null,
                ControlLinkType.MITIGATES));

        controlService.update(
                project.getId(),
                control.getId(),
                new UpdateControlCommand(
                        null, null, null, null, null, null, null, Map.of("rating", "HIGH"), null, null));

        flushAndExpire();
        var refreshed =
                assessmentRepository.findById(fixture.assessment().getId()).orElseThrow();
        assertThat(refreshed.getReassessmentRequiredAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private record Fixture(RiskScenario scenario, RiskRegisterRecord record, RiskAssessmentResult assessment) {}

    private Fixture seedScenarioRecordAssessment(String scenarioUid, String recordUid) {
        return transactionTemplate.execute(status -> {
            var scenario =
                    new RiskScenario(project, scenarioUid, "Scenario", "Actor", "Event", "Object", "Consequence");
            scenario.setTimeHorizon("12 months");
            var savedScenario = riskScenarioRepository.save(scenario);

            var record = new RiskRegisterRecord(project, recordUid, "Record");
            record.replaceRiskScenarios(java.util.List.of(savedScenario));
            var savedRecord = riskRegisterRecordRepository.save(record);

            var assessment = new RiskAssessmentResult(project, savedScenario, profile);
            assessment.setRiskRegisterRecord(savedRecord);
            var savedAssessment = assessmentRepository.save(assessment);
            return new Fixture(savedScenario, savedRecord, savedAssessment);
        });
    }

    private com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan createPlan(
            String uid, RiskRegisterRecord record, RiskScenario scenario) {
        return treatmentPlanService.create(new CreateTreatmentPlanCommand(
                project.getId(),
                uid,
                "Plan " + uid,
                record.getId(),
                scenario == null ? null : scenario.getId(),
                TreatmentStrategy.MITIGATE,
                "owner",
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    }

    /** Force the persistence context to drop any cached entities so we observe the listener's write. */
    private void flushAndExpire() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.flush();
            entityManager.clear();
        });
    }
}
