package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetType;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskAssessmentResult;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskAssessmentResultRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateRiskAssessmentResultCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskAssessmentResultService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateRiskAssessmentResultCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskAssessmentApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentResultServiceTest {

    @Mock
    private RiskAssessmentResultRepository repository;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private MethodologyProfileRepository methodologyProfileRepository;

    @Mock
    private RiskRegisterRecordRepository riskRegisterRecordRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private RiskAssessmentResultService service;

    private Project project;
    private UUID projectId;
    private RiskScenario scenario;
    private UUID scenarioId;
    private MethodologyProfile profile;
    private UUID profileId;
    private RiskRegisterRecord record;
    private UUID recordId;
    private Observation observation;
    private UUID observationId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);

        scenario = new RiskScenario(project, "RS-1", "Gateway risk", "Actor", "Exploit", "Gateway", "Outage");
        scenario.setTimeHorizon("12 months");
        scenarioId = UUID.randomUUID();
        setField(scenario, "id", scenarioId);

        profile = new MethodologyProfile(
                project, "FAIR_V3_0", "Open FAIR", "O-RT 3.0.1 / O-RA 2.0.1", MethodologyFamily.FAIR);
        profileId = UUID.randomUUID();
        setField(profile, "id", profileId);

        record = new RiskRegisterRecord(project, "RR-1", "Gateway record");
        record.replaceRiskScenarios(List.of(scenario));
        recordId = UUID.randomUUID();
        setField(record, "id", recordId);

        var asset = new OperationalAsset(project, "ASSET-1", "Gateway");
        setField(asset, "id", UUID.randomUUID());
        asset.setAssetType(AssetType.SERVICE);
        observation = new Observation(
                asset,
                ObservationCategory.EXPOSURE,
                "public_access",
                "true",
                "scanner",
                Instant.parse("2026-04-02T00:00:00Z"));
        observationId = UUID.randomUUID();
        setField(observation, "id", observationId);
    }

    @Test
    void createPersistsAssessmentWithResolvedLinksAndObservations() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId)).thenReturn(Optional.of(scenario));
        when(methodologyProfileRepository.findByIdAndProjectId(profileId, projectId))
                .thenReturn(Optional.of(profile));
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(observationRepository.findAllByIdInAndProjectId(List.of(observationId), projectId))
                .thenReturn(List.of(observation));
        when(repository.save(any(RiskAssessmentResult.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(new CreateRiskAssessmentResultCommand(
                projectId,
                scenarioId,
                recordId,
                profileId,
                "Security lead",
                "Assumption",
                Map.of("lossEventFrequency", "moderate"),
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-02T12:00:00Z"),
                "12 months",
                "HIGH",
                Map.of("uncertainty", "medium"),
                Map.of("risk", "high"),
                List.of("EVID-1"),
                "Notes",
                List.of(observationId)));

        assertThat(result.getRiskScenario()).isSameAs(scenario);
        assertThat(result.getMethodologyProfile()).isSameAs(profile);
        assertThat(result.getRiskRegisterRecord()).isSameAs(record);
        assertThat(result.getObservations()).containsExactly(observation);
        assertThat(result.getEvidenceRefs()).containsExactly("EVID-1");
    }

    @Test
    void listByScenarioRequiresScenarioInProject() {
        when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listByScenario(projectId, scenarioId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listByRiskRegisterRecordRequiresRecordInProject() {
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listByRiskRegisterRecord(projectId, recordId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getByIdThrowsWhenResultMissing() {
        var resultId = UUID.randomUUID();
        when(repository.findByIdAndProjectIdWithObservations(resultId, projectId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(projectId, resultId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateRejectsRiskRegisterRecordThatDoesNotTrackScenario() {
        var otherScenario = new RiskScenario(project, "RS-2", "Other risk", "Actor", "Exploit", "App", "Outage");
        otherScenario.setTimeHorizon("12 months");
        setField(otherScenario, "id", UUID.randomUUID());
        var mismatchedRecord = new RiskRegisterRecord(project, "RR-2", "Other record");
        mismatchedRecord.replaceRiskScenarios(List.of(otherScenario));
        var result = new RiskAssessmentResult(project, scenario, profile);
        var resultId = UUID.randomUUID();
        setField(result, "id", resultId);

        when(repository.findByIdAndProjectIdWithObservations(resultId, projectId))
                .thenReturn(Optional.of(result));
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(mismatchedRecord));

        assertThatThrownBy(() -> service.update(
                        projectId,
                        resultId,
                        new UpdateRiskAssessmentResultCommand(
                                recordId, null, null, null, null, null, null, null, null, null, null, null, null,
                                null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("is not linked to scenario");
    }

    @Test
    void updateRejectsObservationIdsOutsideProject() {
        var result = new RiskAssessmentResult(project, scenario, profile);
        var resultId = UUID.randomUUID();
        setField(result, "id", resultId);
        when(repository.findByIdAndProjectIdWithObservations(resultId, projectId))
                .thenReturn(Optional.of(result));
        when(observationRepository.findAllByIdInAndProjectId(List.of(observationId), projectId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.update(
                        projectId,
                        resultId,
                        new UpdateRiskAssessmentResultCommand(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of(observationId))))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("observations");
    }

    @Test
    void transitionApprovalStateUsesAssessmentStateMachine() {
        var result = new RiskAssessmentResult(project, scenario, profile);
        var resultId = UUID.randomUUID();
        setField(result, "id", resultId);
        when(repository.findByIdAndProjectIdWithObservations(resultId, projectId))
                .thenReturn(Optional.of(result));
        when(repository.save(result)).thenReturn(result);

        var transitioned = service.transitionApprovalState(projectId, resultId, RiskAssessmentApprovalStatus.SUBMITTED);

        assertThat(transitioned.getApprovalState()).isEqualTo(RiskAssessmentApprovalStatus.SUBMITTED);
    }

    @Test
    void transitionApprovalStateRejectsIllegalTransition() {
        var result = new RiskAssessmentResult(project, scenario, profile);
        result.transitionApprovalState(RiskAssessmentApprovalStatus.SUBMITTED);
        result.transitionApprovalState(RiskAssessmentApprovalStatus.APPROVED);
        var resultId = UUID.randomUUID();
        setField(result, "id", resultId);
        when(repository.findByIdAndProjectIdWithObservations(resultId, projectId))
                .thenReturn(Optional.of(result));

        assertThatThrownBy(() ->
                        service.transitionApprovalState(projectId, resultId, RiskAssessmentApprovalStatus.SUBMITTED))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("APPROVED")
                .hasMessageContaining("SUBMITTED");
    }

    @Test
    void listByProjectReturnsRepositoryResults() {
        var result = new RiskAssessmentResult(project, scenario, profile);
        var resultId = UUID.randomUUID();
        setField(result, "id", resultId);
        when(repository.findByProjectIdWithObservationsOrderByCreatedAtDesc(projectId))
                .thenReturn(List.of(result));

        var results = service.listByProject(projectId);

        assertThat(results).containsExactly(result);
    }

    @Test
    void deleteRemovesResolvedAssessment() {
        var result = new RiskAssessmentResult(project, scenario, profile);
        var resultId = UUID.randomUUID();
        setField(result, "id", resultId);
        when(repository.findByIdAndProjectIdWithObservations(resultId, projectId))
                .thenReturn(Optional.of(result));

        service.delete(projectId, resultId);

        verify(repository).delete(result);
    }
}
