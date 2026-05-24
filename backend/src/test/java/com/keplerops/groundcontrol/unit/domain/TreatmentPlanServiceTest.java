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
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.TreatmentPlanRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateTreatmentPlanCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.TreatmentPlanService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateTreatmentPlanCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
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
class TreatmentPlanServiceTest {

    @Mock
    private TreatmentPlanRepository repository;

    @Mock
    private RiskRegisterRecordRepository riskRegisterRecordRepository;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private MethodologyProfileRepository methodologyProfileRepository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private TreatmentPlanService service;

    private Project project;
    private UUID projectId;
    private RiskScenario scenario;
    private UUID scenarioId;
    private RiskRegisterRecord record;
    private UUID recordId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
        scenario = new RiskScenario(project, "RS-1", "Gateway risk", "Actor", "Exploit", "Gateway", "Outage");
        scenario.setTimeHorizon("12 months");
        scenarioId = UUID.randomUUID();
        setField(scenario, "id", scenarioId);
        record = new RiskRegisterRecord(project, "RR-1", "Gateway record");
        record.replaceRiskScenarios(List.of(scenario));
        recordId = UUID.randomUUID();
        setField(record, "id", recordId);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private MethodologyProfile makeProfileWithVocabulary(String... keys) {
        var profile = new MethodologyProfile(project, "CUSTOM_KEY", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        var vocab = new java.util.HashMap<String, Object>();
        for (var k : keys) {
            vocab.put(k, Map.of("description", k));
        }
        profile.setTreatmentStrategyVocabulary(vocab);
        return profile;
    }

    private CreateTreatmentPlanCommand createOtherCommand(UUID profileId, String strategyKey) {
        return new CreateTreatmentPlanCommand(
                projectId,
                "TP-1",
                "Custom plan",
                recordId,
                null,
                TreatmentStrategy.OTHER,
                "Owner",
                "Rationale",
                null,
                null,
                null,
                null,
                profileId,
                strategyKey);
    }

    private UpdateTreatmentPlanCommand updateOtherCommand(UUID profileId, String strategyKey) {
        return new UpdateTreatmentPlanCommand(
                null, null, TreatmentStrategy.OTHER, null, null, null, null, null, profileId, strategyKey);
    }

    // -------------------------------------------------------------------------
    // existing tests (updated command constructors)
    // -------------------------------------------------------------------------

    @Test
    void createBuildsPlanAndTransitionsRequestedStatus() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId)).thenReturn(Optional.of(scenario));
        when(repository.save(any(TreatmentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(new CreateTreatmentPlanCommand(
                projectId,
                "TP-1",
                "Mitigate gateway",
                recordId,
                scenarioId,
                TreatmentStrategy.MITIGATE,
                "Owner",
                "Rationale",
                Instant.parse("2026-06-01T00:00:00Z"),
                TreatmentPlanStatus.IN_PROGRESS,
                List.of(Map.of("step", "Enable WAF")),
                List.of("New exposure"),
                null,
                null));

        assertThat(result.getUid()).isEqualTo("TP-1");
        assertThat(result.getStatus()).isEqualTo(TreatmentPlanStatus.IN_PROGRESS);
        assertThat(result.getRiskScenario()).isSameAs(scenario);
        assertThat(result.getActionItems()).hasSize(1);
    }

    @Test
    void createRejectsDuplicateUid() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateTreatmentPlanCommand(
                        projectId,
                        "TP-1",
                        "Mitigate gateway",
                        recordId,
                        null,
                        TreatmentStrategy.MITIGATE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void listByRiskRegisterRecordRequiresRecordInProject() {
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listByRiskRegisterRecord(projectId, recordId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateRejectsScenarioOutsideLinkedRecord() {
        var otherScenario = new RiskScenario(project, "RS-2", "Other", "Actor", "Exploit", "App", "Outage");
        otherScenario.setTimeHorizon("12 months");
        var otherScenarioId = UUID.randomUUID();
        setField(otherScenario, "id", otherScenarioId);
        var plan = new TreatmentPlan(project, "TP-1", "Mitigate gateway", record, TreatmentStrategy.MITIGATE);
        var planId = UUID.randomUUID();
        setField(plan, "id", planId);
        when(repository.findByIdAndProjectId(planId, projectId)).thenReturn(Optional.of(plan));
        when(riskScenarioRepository.findByIdAndProjectId(otherScenarioId, projectId))
                .thenReturn(Optional.of(otherScenario));

        assertThatThrownBy(() -> service.update(
                        projectId,
                        planId,
                        new UpdateTreatmentPlanCommand(
                                "Updated title",
                                otherScenarioId,
                                TreatmentStrategy.AVOID,
                                "Owner",
                                "Rationale",
                                Instant.parse("2026-06-01T00:00:00Z"),
                                List.of(),
                                List.of(),
                                null,
                                null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("must belong");
    }

    @Test
    void transitionStatusUsesPlanStateMachine() {
        var plan = new TreatmentPlan(project, "TP-1", "Mitigate gateway", record, TreatmentStrategy.MITIGATE);
        var planId = UUID.randomUUID();
        setField(plan, "id", planId);
        when(repository.findByIdAndProjectId(planId, projectId)).thenReturn(Optional.of(plan));
        when(repository.save(plan)).thenReturn(plan);

        var transitioned = service.transitionStatus(projectId, planId, TreatmentPlanStatus.IN_PROGRESS);

        assertThat(transitioned.getStatus()).isEqualTo(TreatmentPlanStatus.IN_PROGRESS);
    }

    @Test
    void deleteRemovesResolvedPlan() {
        var plan = new TreatmentPlan(project, "TP-1", "Mitigate gateway", record, TreatmentStrategy.MITIGATE);
        var planId = UUID.randomUUID();
        setField(plan, "id", planId);
        when(repository.findByIdAndProjectId(planId, projectId)).thenReturn(Optional.of(plan));

        service.delete(projectId, planId);

        verify(repository).delete(plan);
    }

    // -------------------------------------------------------------------------
    // C5: methodology binding — create path
    // -------------------------------------------------------------------------

    @Test
    void createOtherWithValidBindingPersistsBinding() {
        var profile = makeProfileWithVocabulary("RESIDUAL_TRANSFER");
        var profileId = profile.getId();
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(methodologyProfileRepository.findByIdAndProjectId(profileId, projectId))
                .thenReturn(Optional.of(profile));
        when(repository.save(any(TreatmentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(createOtherCommand(profileId, "RESIDUAL_TRANSFER"));

        assertThat(result.getMethodologyProfile()).isSameAs(profile);
        assertThat(result.getMethodologyStrategyKey()).isEqualTo("RESIDUAL_TRANSFER");
    }

    @Test
    void createOtherWithoutProfileIdThrowsDomainValidation() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));

        var command = createOtherCommand(null, "KEY");
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("methodologyProfileId");
    }

    @Test
    void createOtherWithoutStrategyKeyThrowsDomainValidation() {
        var profile = makeProfileWithVocabulary("KEY");
        var profileId = profile.getId();
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(methodologyProfileRepository.findByIdAndProjectId(profileId, projectId))
                .thenReturn(Optional.of(profile));

        var command = createOtherCommand(profileId, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("methodologyProfileId");
    }

    @Test
    void createOtherWithBlankStrategyKeyThrowsDomainValidation() {
        var profile = makeProfileWithVocabulary("KEY");
        var profileId = profile.getId();
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(methodologyProfileRepository.findByIdAndProjectId(profileId, projectId))
                .thenReturn(Optional.of(profile));

        var command = createOtherCommand(profileId, "  ");
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("methodologyProfileId");
    }

    @Test
    void createOtherWithKeyAbsentFromVocabularyThrowsDomainValidation() {
        var profile = makeProfileWithVocabulary("KEY_A", "KEY_B");
        var profileId = profile.getId();
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(methodologyProfileRepository.findByIdAndProjectId(profileId, projectId))
                .thenReturn(Optional.of(profile));

        var command = createOtherCommand(profileId, "UNKNOWN_KEY");
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("UNKNOWN_KEY");
    }

    @Test
    void createOtherWithNullVocabularyThrowsDomainValidation() {
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        var profileId = UUID.randomUUID();
        setField(profile, "id", profileId);
        // treatmentStrategyVocabulary left null
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(methodologyProfileRepository.findByIdAndProjectId(profileId, projectId))
                .thenReturn(Optional.of(profile));

        var command = createOtherCommand(profileId, "KEY");
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("KEY");
    }

    @Test
    void createOtherWithCrossProjectProfileIdThrowsNotFoundException() {
        var profileId = UUID.randomUUID();
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(methodologyProfileRepository.findByIdAndProjectId(profileId, projectId))
                .thenReturn(Optional.empty());

        var command = createOtherCommand(profileId, "KEY");
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(profileId.toString());
    }

    @Test
    void createCanonicalStrategyClearsBinding() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(repository.save(any(TreatmentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(new CreateTreatmentPlanCommand(
                projectId,
                "TP-1",
                "Mitigate",
                recordId,
                null,
                TreatmentStrategy.MITIGATE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        assertThat(result.getMethodologyProfile()).isNull();
        assertThat(result.getMethodologyStrategyKey()).isNull();
    }

    @Test
    void createCanonicalStrategyWithMethodologyFieldsIgnoresFields() {
        // canonical strategy + methodology fields supplied → accepted, fields ignored
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsByProjectIdAndUid(projectId, "TP-1")).thenReturn(false);
        when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                .thenReturn(Optional.of(record));
        when(repository.save(any(TreatmentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var ignoredProfileId = UUID.randomUUID();

        var result = service.create(new CreateTreatmentPlanCommand(
                projectId,
                "TP-1",
                "Mitigate",
                recordId,
                null,
                TreatmentStrategy.MITIGATE,
                null,
                null,
                null,
                null,
                null,
                null,
                ignoredProfileId,
                "IGNORED_KEY"));

        assertThat(result.getMethodologyProfile()).isNull();
        assertThat(result.getMethodologyStrategyKey()).isNull();
    }

    // -------------------------------------------------------------------------
    // C5: methodology binding — update path
    // -------------------------------------------------------------------------

    @Test
    void updateOtherWithValidBindingPersistsBinding() {
        var profile = makeProfileWithVocabulary("CUSTOM_TRANSFER");
        var profileId = profile.getId();
        var plan = new TreatmentPlan(project, "TP-1", "Plan", record, TreatmentStrategy.MITIGATE);
        var planId = UUID.randomUUID();
        setField(plan, "id", planId);
        when(repository.findByIdAndProjectId(planId, projectId)).thenReturn(Optional.of(plan));
        when(methodologyProfileRepository.findByIdAndProjectId(profileId, projectId))
                .thenReturn(Optional.of(profile));
        when(repository.save(any(TreatmentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.update(projectId, planId, updateOtherCommand(profileId, "CUSTOM_TRANSFER"));

        assertThat(result.getMethodologyProfile()).isSameAs(profile);
        assertThat(result.getMethodologyStrategyKey()).isEqualTo("CUSTOM_TRANSFER");
    }

    @Test
    void updateSwitchingOtherToMitigateClearsBinding() {
        var profile = makeProfileWithVocabulary("K");
        var plan = new TreatmentPlan(project, "TP-1", "Plan", record, TreatmentStrategy.OTHER);
        var planId = UUID.randomUUID();
        setField(plan, "id", planId);
        plan.setMethodologyProfile(profile);
        plan.setMethodologyStrategyKey("K");
        when(repository.findByIdAndProjectId(planId, projectId)).thenReturn(Optional.of(plan));
        when(repository.save(any(TreatmentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.update(
                projectId,
                planId,
                new UpdateTreatmentPlanCommand(
                        null, null, TreatmentStrategy.MITIGATE, null, null, null, null, null, null, null));

        assertThat(result.getMethodologyProfile()).isNull();
        assertThat(result.getMethodologyStrategyKey()).isNull();
    }

    @Test
    void updateChangingKeyOnOtherPlanSucceeds() {
        var profile = makeProfileWithVocabulary("KEY_A", "KEY_B");
        var plan = new TreatmentPlan(project, "TP-1", "Plan", record, TreatmentStrategy.OTHER);
        var planId = UUID.randomUUID();
        setField(plan, "id", planId);
        plan.setMethodologyProfile(profile);
        plan.setMethodologyStrategyKey("KEY_A");
        when(repository.findByIdAndProjectId(planId, projectId)).thenReturn(Optional.of(plan));
        // no new profileId supplied — reuse existing profile
        when(repository.save(any(TreatmentPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.update(
                projectId,
                planId,
                new UpdateTreatmentPlanCommand(
                        null, null, TreatmentStrategy.OTHER, null, null, null, null, null, null, "KEY_B"));

        assertThat(result.getMethodologyStrategyKey()).isEqualTo("KEY_B");
        assertThat(result.getMethodologyProfile()).isSameAs(profile);
    }
}
