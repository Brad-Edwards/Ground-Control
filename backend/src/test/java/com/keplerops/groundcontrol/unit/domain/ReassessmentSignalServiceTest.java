package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.AssetLink;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkType;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlLink;
import com.keplerops.groundcontrol.domain.controls.repository.ControlLinkRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkTargetType;
import com.keplerops.groundcontrol.domain.controls.state.ControlLinkType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.events.AssetStateChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ControlStateChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSignal;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSourceEntityType;
import com.keplerops.groundcontrol.domain.riskscenarios.events.TreatmentProgressChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.ReassessmentSignalService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReassessmentSignalServiceTest {

    @Mock
    private RiskAssessmentResultRepository assessmentRepository;

    @Mock
    private TreatmentPlanRepository treatmentPlanRepository;

    @Mock
    private AssetLinkRepository assetLinkRepository;

    @Mock
    private ControlLinkRepository controlLinkRepository;

    @Mock
    private RiskScenarioLinkRepository riskScenarioLinkRepository;

    @InjectMocks
    private ReassessmentSignalService listener;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private RiskAssessmentResult makeAssessment(UUID id) {
        var scenario = new RiskScenario(project, "RS-1", "Scenario", "Actor", "Event", "Object", "Consequence");
        scenario.setTimeHorizon("12 months");
        setField(scenario, "id", UUID.randomUUID());
        var profile = new MethodologyProfile(project, "MP", "Profile", "1.0", MethodologyFamily.CUSTOM);
        setField(profile, "id", UUID.randomUUID());
        var result = new RiskAssessmentResult(project, scenario, profile);
        setField(result, "id", id);
        return result;
    }

    private TreatmentPlan makePlan(RiskRegisterRecord registerRecord, RiskScenario scenario) {
        var plan = new TreatmentPlan(project, "TP-1", "Plan", registerRecord, TreatmentStrategy.MITIGATE);
        setField(plan, "id", UUID.randomUUID());
        plan.setRiskScenario(scenario);
        return plan;
    }

    private RiskRegisterRecord makeRecord() {
        var registerRecord = new RiskRegisterRecord(project, "RR-1", "Record");
        setField(registerRecord, "id", UUID.randomUUID());
        return registerRecord;
    }

    private RiskScenario makeScenario(String uid) {
        var scenario = new RiskScenario(project, uid, "Scenario", "Actor", "Event", "Object", "Consequence");
        scenario.setTimeHorizon("12 months");
        setField(scenario, "id", UUID.randomUUID());
        return scenario;
    }

    private OperationalAsset makeAsset(String uid) {
        var asset = new OperationalAsset(project, uid, "Asset name");
        setField(asset, "id", UUID.randomUUID());
        return asset;
    }

    private Control makeControl(String uid) {
        var control = new Control(project, uid, "Control title", ControlFunction.PREVENTIVE);
        setField(control, "id", UUID.randomUUID());
        return control;
    }

    private ReassessmentSignal signalFor(ReassessmentSourceEntityType type, UUID entityId) {
        return new ReassessmentSignal(
                projectId,
                type == ReassessmentSourceEntityType.ASSET
                        ? ReassessmentTriggerCategory.ASSET_STATE_CHANGED
                        : (type == ReassessmentSourceEntityType.CONTROL
                                ? ReassessmentTriggerCategory.CONTROL_STATE_CHANGED
                                : ReassessmentTriggerCategory.TREATMENT_PROGRESS_CHANGED),
                type,
                entityId,
                Set.of("status"),
                new HashMap<>(),
                new HashMap<>(),
                Instant.now());
    }

    @Test
    void treatmentPlanEventMarksAssessmentResultsLinkedThroughRegisterRecord() {
        var registerRecord = makeRecord();
        var scenario = makeScenario("RS-1");
        var plan = makePlan(registerRecord, scenario);
        var result = makeAssessment(UUID.randomUUID());

        when(treatmentPlanRepository.findByIdAndProjectId(plan.getId(), projectId))
                .thenReturn(Optional.of(plan));
        when(assessmentRepository.findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(
                        projectId, registerRecord.getId()))
                .thenReturn(List.of(result));
        when(assessmentRepository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(projectId, scenario.getId()))
                .thenReturn(List.of());
        when(assessmentRepository.findByIdAndProjectId(result.getId(), projectId))
                .thenReturn(Optional.of(result));

        listener.onTreatmentProgressChanged(new TreatmentProgressChangedEvent(
                signalFor(ReassessmentSourceEntityType.TREATMENT_PLAN, plan.getId())));

        verify(assessmentRepository).save(result);
        assertThat(result.getReassessmentRequiredAt()).isNotNull();
    }

    @Test
    void treatmentPlanEventDeduplicatesScenarioVsRecordPaths() {
        // Both findByRecord and findByScenario return the SAME assessment row —
        // the listener must dedup and call save() exactly once for that id.
        var registerRecord = makeRecord();
        var scenario = makeScenario("RS-1");
        var plan = makePlan(registerRecord, scenario);
        var result = makeAssessment(UUID.randomUUID());

        when(treatmentPlanRepository.findByIdAndProjectId(plan.getId(), projectId))
                .thenReturn(Optional.of(plan));
        when(assessmentRepository.findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(
                        projectId, registerRecord.getId()))
                .thenReturn(List.of(result));
        when(assessmentRepository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(projectId, scenario.getId()))
                .thenReturn(List.of(result));
        when(assessmentRepository.findByIdAndProjectId(result.getId(), projectId))
                .thenReturn(Optional.of(result));

        listener.onTreatmentProgressChanged(new TreatmentProgressChangedEvent(
                signalFor(ReassessmentSourceEntityType.TREATMENT_PLAN, plan.getId())));

        verify(assessmentRepository, org.mockito.Mockito.times(1)).save(result);
    }

    @Test
    void assetEventWalksAssetLinkToRiskScenarioAndMarksAssessments() {
        var asset = makeAsset("ASSET-1");
        var scenario = makeScenario("RS-1");
        var result = makeAssessment(UUID.randomUUID());

        var link = new AssetLink(
                asset, AssetLinkTargetType.RISK_SCENARIO, scenario.getId(), null, AssetLinkType.ASSOCIATED);
        when(assetLinkRepository.findByAssetId(asset.getId())).thenReturn(List.of(link));
        when(assessmentRepository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(projectId, scenario.getId()))
                .thenReturn(List.of(result));
        when(assessmentRepository.findByIdAndProjectId(result.getId(), projectId))
                .thenReturn(Optional.of(result));

        listener.onAssetStateChanged(
                new AssetStateChangedEvent(signalFor(ReassessmentSourceEntityType.ASSET, asset.getId())));

        verify(assessmentRepository).save(result);
    }

    @Test
    void assetEventMarksDirectRiskAssessmentResultLink() {
        var asset = makeAsset("ASSET-1");
        var result = makeAssessment(UUID.randomUUID());
        var link = new AssetLink(
                asset, AssetLinkTargetType.RISK_ASSESSMENT_RESULT, result.getId(), null, AssetLinkType.ASSOCIATED);

        when(assetLinkRepository.findByAssetId(asset.getId())).thenReturn(List.of(link));
        when(assessmentRepository.findByIdAndProjectId(result.getId(), projectId))
                .thenReturn(Optional.of(result));

        listener.onAssetStateChanged(
                new AssetStateChangedEvent(signalFor(ReassessmentSourceEntityType.ASSET, asset.getId())));

        verify(assessmentRepository).save(result);
    }

    @Test
    void assetEventWithNoLinksDoesNothing() {
        var asset = makeAsset("ASSET-1");
        when(assetLinkRepository.findByAssetId(asset.getId())).thenReturn(List.of());

        listener.onAssetStateChanged(
                new AssetStateChangedEvent(signalFor(ReassessmentSourceEntityType.ASSET, asset.getId())));

        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void assetEventIgnoresNonRiskRoutedLinkTargets() {
        // AssetLink → EXTERNAL is not a risk-routing surface; the listener
        // walks but does not mark anything from it.
        var asset = makeAsset("ASSET-1");
        var link = new AssetLink(asset, AssetLinkTargetType.EXTERNAL, null, "ext://foo", AssetLinkType.ASSOCIATED);
        when(assetLinkRepository.findByAssetId(asset.getId())).thenReturn(List.of(link));

        listener.onAssetStateChanged(
                new AssetStateChangedEvent(signalFor(ReassessmentSourceEntityType.ASSET, asset.getId())));

        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void controlEventWalksControlLinkToRegisterRecordAndMarksAssessments() {
        var control = makeControl("CTRL-1");
        var registerRecord = makeRecord();
        var result = makeAssessment(UUID.randomUUID());

        var link = new ControlLink(
                control,
                ControlLinkTargetType.RISK_REGISTER_RECORD,
                registerRecord.getId(),
                null,
                ControlLinkType.MITIGATES);
        when(controlLinkRepository.findByControlId(control.getId())).thenReturn(List.of(link));
        when(assessmentRepository.findByProjectIdAndRiskRegisterRecordIdOrderByCreatedAtDesc(
                        projectId, registerRecord.getId()))
                .thenReturn(List.of(result));
        when(assessmentRepository.findByIdAndProjectId(result.getId(), projectId))
                .thenReturn(Optional.of(result));

        listener.onControlStateChanged(
                new ControlStateChangedEvent(signalFor(ReassessmentSourceEntityType.CONTROL, control.getId())));

        verify(assessmentRepository).save(result);
    }

    @Test
    void controlEventAlsoFollowsInverseRiskScenarioLink() {
        // RiskScenarioLink → CONTROL: when a scenario links to a control as
        // mitigation, a change to the control implicates that scenario's
        // assessments via the inverse direction.
        var control = makeControl("CTRL-1");
        var scenario = makeScenario("RS-1");
        var result = makeAssessment(UUID.randomUUID());

        var scenarioLink = new com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink(
                scenario,
                com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType.CONTROL,
                control.getId(),
                null,
                com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkType.MITIGATED_BY);
        when(controlLinkRepository.findByControlId(control.getId())).thenReturn(List.of());
        when(riskScenarioLinkRepository.findByTargetTypeAndTargetEntityIdAndProjectId(
                        com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType.CONTROL,
                        control.getId(),
                        projectId))
                .thenReturn(List.of(scenarioLink));
        when(assessmentRepository.findByProjectIdAndRiskScenarioIdOrderByCreatedAtDesc(projectId, scenario.getId()))
                .thenReturn(List.of(result));
        when(assessmentRepository.findByIdAndProjectId(result.getId(), projectId))
                .thenReturn(Optional.of(result));

        listener.onControlStateChanged(
                new ControlStateChangedEvent(signalFor(ReassessmentSourceEntityType.CONTROL, control.getId())));

        verify(assessmentRepository).save(result);
    }

    @Test
    void noMatchingAssessmentRowIsTolerated() {
        // If the link points at a result id that no longer exists, the listener
        // skips it without throwing.
        var asset = makeAsset("ASSET-1");
        var deletedResultId = UUID.randomUUID();
        var link = new AssetLink(
                asset, AssetLinkTargetType.RISK_ASSESSMENT_RESULT, deletedResultId, null, AssetLinkType.ASSOCIATED);
        when(assetLinkRepository.findByAssetId(asset.getId())).thenReturn(List.of(link));
        when(assessmentRepository.findByIdAndProjectId(deletedResultId, projectId))
                .thenReturn(Optional.empty());

        listener.onAssetStateChanged(
                new AssetStateChangedEvent(signalFor(ReassessmentSourceEntityType.ASSET, asset.getId())));

        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void idempotencyAcrossSuccessiveEvents() {
        // Two events back-to-back must both mark and both call save() — the
        // listener is not silently skipping the "already-marked" case. The
        // governance contract is "the most recent change wins"; suppressing
        // the second call would hide a real subsequent mutation.
        var asset = makeAsset("ASSET-1");
        var result = makeAssessment(UUID.randomUUID());
        var link = new AssetLink(
                asset, AssetLinkTargetType.RISK_ASSESSMENT_RESULT, result.getId(), null, AssetLinkType.ASSOCIATED);
        when(assetLinkRepository.findByAssetId(asset.getId())).thenReturn(List.of(link));
        when(assessmentRepository.findByIdAndProjectId(result.getId(), projectId))
                .thenReturn(Optional.of(result));

        var event = new AssetStateChangedEvent(signalFor(ReassessmentSourceEntityType.ASSET, asset.getId()));
        listener.onAssetStateChanged(event);
        listener.onAssetStateChanged(event);

        verify(assessmentRepository, org.mockito.Mockito.times(2)).save(result);
    }
}
