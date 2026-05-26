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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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

    @Autowired
    private DataSource dataSource;

    private Project project;
    private MethodologyProfile profile;

    @BeforeEach
    void seedProjectAndProfile() {
        // Use the V012-seeded `ground-control` project rather than creating a new one per
        // test. Other integration tests (RequirementController, RequirementsE2E,
        // TraceabilityLinkController) call `/api/v1/requirements` without a `project` query
        // param and rely on the "exactly one project" branch in `ProjectService.resolveProjectId`.
        // Adding new projects breaks that contract across the shared Testcontainers DB.
        transactionTemplate.executeWithoutResult(status -> {
            project = projectRepository
                    .findByIdentifier("ground-control")
                    .orElseThrow(() -> new IllegalStateException("V012-seeded 'ground-control' project missing"));
            profile = methodologyProfileRepository.save(new MethodologyProfile(
                    project, "MP-C8-" + UUID.randomUUID(), "Profile", "1.0", MethodologyFamily.CUSTOM));
        });
    }

    @AfterEach
    void cleanupReassessmentRows() throws Exception {
        // Hard cleanup via JDBC: integration tests share a single Testcontainers Postgres
        // across the suite (BaseIntegrationTest static singleton), so leaking risk_assessment
        // rows or links between tests would poison other suites that scan for orphans.
        // Order matches FK direction.
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM control_link WHERE control_id IN "
                    + "(SELECT id FROM control WHERE uid LIKE 'CTRL-%')");
            stmt.executeUpdate("DELETE FROM asset_link WHERE asset_id IN "
                    + "(SELECT id FROM operational_asset WHERE uid LIKE 'ASSET-%')");
            stmt.executeUpdate("DELETE FROM risk_assessment_result_audit WHERE id IN "
                    + "(SELECT id FROM risk_assessment_result WHERE risk_scenario_id IN "
                    + "(SELECT id FROM risk_scenario WHERE uid LIKE 'RS-%'))");
            stmt.executeUpdate("DELETE FROM risk_assessment_result WHERE risk_scenario_id IN "
                    + "(SELECT id FROM risk_scenario WHERE uid LIKE 'RS-%')");
            stmt.executeUpdate("DELETE FROM treatment_plan_audit WHERE id IN "
                    + "(SELECT id FROM treatment_plan WHERE uid LIKE 'TP-%')");
            stmt.executeUpdate("DELETE FROM treatment_plan WHERE uid LIKE 'TP-%'");
            stmt.executeUpdate("DELETE FROM risk_register_record_scenario WHERE risk_register_record_id IN "
                    + "(SELECT id FROM risk_register_record WHERE uid LIKE 'RR-%')");
            stmt.executeUpdate("DELETE FROM risk_register_record_audit WHERE id IN "
                    + "(SELECT id FROM risk_register_record WHERE uid LIKE 'RR-%')");
            stmt.executeUpdate("DELETE FROM risk_register_record WHERE uid LIKE 'RR-%'");
            stmt.executeUpdate("DELETE FROM risk_scenario_audit WHERE id IN "
                    + "(SELECT id FROM risk_scenario WHERE uid LIKE 'RS-%')");
            stmt.executeUpdate("DELETE FROM risk_scenario WHERE uid LIKE 'RS-%'");
            stmt.executeUpdate(
                    "DELETE FROM control_audit WHERE id IN " + "(SELECT id FROM control WHERE uid LIKE 'CTRL-%')");
            stmt.executeUpdate("DELETE FROM control WHERE uid LIKE 'CTRL-%'");
            stmt.executeUpdate("DELETE FROM operational_asset_audit WHERE id IN "
                    + "(SELECT id FROM operational_asset WHERE uid LIKE 'ASSET-%')");
            stmt.executeUpdate("DELETE FROM operational_asset WHERE uid LIKE 'ASSET-%'");
            stmt.executeUpdate("DELETE FROM methodology_profile_audit WHERE id IN "
                    + "(SELECT id FROM methodology_profile WHERE profile_key LIKE 'MP-C8-%')");
            stmt.executeUpdate("DELETE FROM methodology_profile WHERE profile_key LIKE 'MP-C8-%'");
        }
    }

    @Test
    void treatmentPlanTransitionMarksAssessmentResultsLinkedThroughRegisterRecord() {
        var fixture = seedScenarioRecordAssessment("RS-TP", "RR-TP");
        var plan = createPlan("TP-TRANS", fixture.registerRecord(), null);

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
                fixture.registerRecord().getId(),
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
                fixture.registerRecord().getId(),
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
                fixture.registerRecord().getId(),
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

    private record Fixture(RiskScenario scenario, RiskRegisterRecord registerRecord, RiskAssessmentResult assessment) {}

    private Fixture seedScenarioRecordAssessment(String scenarioUid, String recordUid) {
        return transactionTemplate.execute(status -> {
            var scenario =
                    new RiskScenario(project, scenarioUid, "Scenario", "Actor", "Event", "Object", "Consequence");
            scenario.setTimeHorizon("12 months");
            var savedScenario = riskScenarioRepository.save(scenario);

            var registerRecord = new RiskRegisterRecord(project, recordUid, "Record");
            registerRecord.replaceRiskScenarios(java.util.List.of(savedScenario));
            var savedRecord = riskRegisterRecordRepository.save(registerRecord);

            var assessment = new RiskAssessmentResult(project, savedScenario, profile);
            assessment.setRiskRegisterRecord(savedRecord);
            var savedAssessment = assessmentRepository.save(assessment);
            return new Fixture(savedScenario, savedRecord, savedAssessment);
        });
    }

    private com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan createPlan(
            String uid, RiskRegisterRecord registerRecord, RiskScenario scenario) {
        return treatmentPlanService.create(new CreateTreatmentPlanCommand(
                project.getId(),
                uid,
                "Plan " + uid,
                registerRecord.getId(),
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
