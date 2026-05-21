package com.keplerops.groundcontrol.unit.domain.riskcontrol;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.Observation;
import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.ObservationCategory;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.service.CreateRiskControlMappingCommand;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingService;
import com.keplerops.groundcontrol.domain.riskcontrol.service.UpdateRiskControlMappingCommand;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for RiskControlMappingService — covers C1, C2, C3, and uniqueness (ConflictException). */
@ExtendWith(MockitoExtension.class)
class RiskControlMappingServiceTest {

    @Mock
    private RiskControlMappingRepository repository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository
            riskRegisterRecordRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository
            scopedControlImplementationRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.riskscenarios.repository.MethodologyProfileRepository
            methodologyProfileRepository;

    @InjectMocks
    private RiskControlMappingService service;

    private Project project;
    private UUID projectId;
    private Control control;
    private UUID controlId;
    private RiskScenario scenario;
    private UUID scenarioId;

    @BeforeEach
    void setUp() {
        project = new Project("test-project", "Test Project");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);

        control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        controlId = UUID.randomUUID();
        setField(control, "id", controlId);

        scenario = new RiskScenario(
                project, "RS-001", "Phishing", "Attacker", "Phishing email", "User credentials", "Data breach");
        scenarioId = UUID.randomUUID();
        setField(scenario, "id", scenarioId);
    }

    @Nested
    class Create {

        @Test
        void createsMapping_controlToScenario_withAllC3Fields() {
            // Arrange
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId))
                    .thenReturn(Optional.of(scenario));
            when(repository.existsByControlIdAndRiskScenarioIdAndOperationalAssetId(controlId, scenarioId, null))
                    .thenReturn(false);
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId, // controlId
                    null, // scopedImplementationId
                    scenarioId, // riskScenarioId
                    null, // riskRegisterRecordId
                    null, // operationalAssetId
                    "Prevent credential theft", // mappingObjective
                    MappingControlRole.PREVENTIVE, // controlRole
                    "Email gateway only", // mappingScope
                    null, // methodologyProfileId
                    null // methodologyInfluence
                    );

            // Act
            var result = service.create(cmd);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getControl()).isEqualTo(control);
            assertThat(result.getRiskScenario()).isEqualTo(scenario);
            assertThat(result.getMappingObjective()).isEqualTo("Prevent credential theft");
            assertThat(result.getControlRole()).isEqualTo(MappingControlRole.PREVENTIVE);
            assertThat(result.getMappingScope()).isEqualTo("Email gateway only");
            verify(repository).save(any(RiskControlMapping.class));
        }

        @Test
        void throwsConflict_whenDuplicateMappingExists() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId))
                    .thenReturn(Optional.of(scenario));
            when(repository.existsByControlIdAndRiskScenarioIdAndOperationalAssetId(controlId, scenarioId, null))
                    .thenReturn(true);

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    scenarioId,
                    null,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsNotFound_whenControlNotInProject() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.empty());

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    scenarioId,
                    null,
                    null,
                    null,
                    MappingControlRole.DETECTIVE,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFound_whenScenarioNotInProject() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId))
                    .thenReturn(Optional.empty());

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    scenarioId,
                    null,
                    null,
                    null,
                    MappingControlRole.CORRECTIVE,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void createsMapping_withAssetContext_C2() {
            // C2: asset context on the mapping
            var asset = new OperationalAsset(project, "ASSET-001", "Web Server");
            var assetId = UUID.randomUUID();
            setField(asset, "id", assetId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId))
                    .thenReturn(Optional.of(scenario));
            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.of(asset));
            when(repository.existsByControlIdAndRiskScenarioIdAndOperationalAssetId(controlId, scenarioId, assetId))
                    .thenReturn(false);
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    scenarioId,
                    null,
                    assetId,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);

            var result = service.create(cmd);

            assertThat(result.getOperationalAsset()).isEqualTo(asset);
        }

        @Test
        void throwsNotFound_whenAssetNotInProject() {
            var assetId = UUID.randomUUID();
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId))
                    .thenReturn(Optional.of(scenario));
            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.empty());

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    scenarioId,
                    null,
                    assetId,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsValidation_whenBothControlEndpointsNull() {
            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    null,
                    null,
                    scenarioId,
                    null,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);
            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void throwsValidation_whenBothControlEndpointsNonNull() {
            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    UUID.randomUUID(),
                    scenarioId,
                    null,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);
            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void throwsValidation_whenBothRiskEndpointsNull() {
            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);
            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void throwsValidation_whenBothRiskEndpointsNonNull() {
            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    scenarioId,
                    UUID.randomUUID(),
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);
            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void createsMapping_scopedImplementationToScenario_C1() {
            var sciId = UUID.randomUUID();
            var sci = new com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation(
                    project, "SCI-001", control, "Email Gateway Implementation");
            setField(sci, "id", sciId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(scopedControlImplementationRepository.findByIdAndProjectId(sciId, projectId))
                    .thenReturn(Optional.of(sci));
            when(riskScenarioRepository.findByIdAndProjectId(scenarioId, projectId))
                    .thenReturn(Optional.of(scenario));
            when(repository.existsByScopedImplementationIdAndRiskScenarioIdAndOperationalAssetId(
                            sciId, scenarioId, null))
                    .thenReturn(false);
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    null,
                    sciId,
                    scenarioId,
                    null,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);

            var result = service.create(cmd);

            assertThat(result.getScopedImplementation()).isEqualTo(sci);
            assertThat(result.getRiskScenario()).isEqualTo(scenario);
            verify(repository).save(any(RiskControlMapping.class));
        }
    }

    @Nested
    class ReverseQueries {

        @Test
        void listByScenario_delegatesToRepository() {
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            when(repository.findByProjectIdAndRiskScenarioId(projectId, scenarioId))
                    .thenReturn(List.of(mapping));

            var result = service.listByScenario(projectId, scenarioId);

            assertThat(result).containsExactly(mapping);
            verify(repository).findByProjectIdAndRiskScenarioId(projectId, scenarioId);
        }

        @Test
        void listByControl_delegatesToRepository() {
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.DETECTIVE);
            when(repository.findByProjectIdAndControlId(projectId, controlId)).thenReturn(List.of(mapping));

            var result = service.listByControl(projectId, controlId);

            assertThat(result).containsExactly(mapping);
            verify(repository).findByProjectIdAndControlId(projectId, controlId);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesObjectiveAndRole() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateRiskControlMappingCommand(
                    projectId, mappingId, "Updated objective", MappingControlRole.DETECTIVE, null, null, null);

            var result = service.update(cmd);

            assertThat(result.getMappingObjective()).isEqualTo("Updated objective");
            assertThat(result.getControlRole()).isEqualTo(MappingControlRole.DETECTIVE);
            verify(repository).save(mapping);
        }

        @Test
        void updatesMappingScope() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateRiskControlMappingCommand(
                    projectId, mappingId, null, null, "Email perimeter only", null, null);

            var result = service.update(cmd);

            assertThat(result.getMappingScope()).isEqualTo("Email perimeter only");
        }

        @Test
        void throwsNotFound_whenMappingNotInProject() {
            var mappingId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.empty());

            var cmd = new UpdateRiskControlMappingCommand(projectId, mappingId, null, null, null, null, null);

            assertThatThrownBy(() -> service.update(cmd)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class CreateWithRecord {

        @Test
        void createsMapping_controlToRecord() {
            var riskRecord = new RiskRegisterRecord(project, "RR-001", "Risk Entry");
            var recordId = UUID.randomUUID();
            setField(riskRecord, "id", recordId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                    .thenReturn(Optional.of(riskRecord));
            when(repository.existsByControlIdAndRiskRegisterRecordIdAndOperationalAssetId(controlId, recordId, null))
                    .thenReturn(false);
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    null,
                    recordId,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);

            var result = service.create(cmd);

            assertThat(result.getControl()).isEqualTo(control);
            assertThat(result.getRiskRegisterRecord()).isEqualTo(riskRecord);
            verify(repository).save(any(RiskControlMapping.class));
        }

        @Test
        void createsMapping_scopedImplToRecord() {
            var riskRecord = new RiskRegisterRecord(project, "RR-001", "Risk Entry");
            var recordId = UUID.randomUUID();
            setField(riskRecord, "id", recordId);

            var sciId = UUID.randomUUID();
            var sci = new com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation(
                    project, "SCI-001", control, "Email Gateway");
            setField(sci, "id", sciId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(scopedControlImplementationRepository.findByIdAndProjectId(sciId, projectId))
                    .thenReturn(Optional.of(sci));
            when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                    .thenReturn(Optional.of(riskRecord));
            when(repository.existsByScopedImplementationIdAndRiskRegisterRecordIdAndOperationalAssetId(
                            sciId, recordId, null))
                    .thenReturn(false);
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    null,
                    sciId,
                    null,
                    recordId,
                    null,
                    null,
                    MappingControlRole.CORRECTIVE,
                    null,
                    null,
                    null);

            var result = service.create(cmd);

            assertThat(result.getScopedImplementation()).isEqualTo(sci);
            assertThat(result.getRiskRegisterRecord()).isEqualTo(riskRecord);
        }

        @Test
        void throwsNotFound_whenRecordNotInProject() {
            var recordId = UUID.randomUUID();
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                    .thenReturn(Optional.empty());

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    null,
                    recordId,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsConflict_whenDuplicateScopedToRecordExists() {
            var riskRecord = new RiskRegisterRecord(project, "RR-001", "Risk Entry");
            var recordId = UUID.randomUUID();
            setField(riskRecord, "id", recordId);

            var sciId = UUID.randomUUID();
            var sci = new com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation(
                    project, "SCI-001", control, "Email Gateway");
            setField(sci, "id", sciId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(scopedControlImplementationRepository.findByIdAndProjectId(sciId, projectId))
                    .thenReturn(Optional.of(sci));
            when(riskRegisterRecordRepository.findByIdAndProjectIdWithScenarios(recordId, projectId))
                    .thenReturn(Optional.of(riskRecord));
            when(repository.existsByScopedImplementationIdAndRiskRegisterRecordIdAndOperationalAssetId(
                            sciId, recordId, null))
                    .thenReturn(true);

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    null,
                    sciId,
                    null,
                    recordId,
                    null,
                    null,
                    MappingControlRole.CORRECTIVE,
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class DeleteAndRead {

        @Test
        void deleteRemovesMapping() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));

            service.delete(projectId, mappingId);

            verify(repository).delete(mapping);
        }

        @Test
        void delete_throwsNotFound_whenAbsent() {
            var mappingId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(projectId, mappingId)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void getById_returnsMapping() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));

            var result = service.getById(projectId, mappingId);

            assertThat(result).isEqualTo(mapping);
        }

        @Test
        void getById_throwsNotFound_whenAbsent() {
            var mappingId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(projectId, mappingId)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void listByProject_delegatesToRepository() {
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));

            var result = service.listByProject(projectId);

            assertThat(result).containsExactly(mapping);
        }

        @Test
        void listByRecord_delegatesToRepository() {
            var recordId = UUID.randomUUID();
            var riskRecord = new RiskRegisterRecord(project, "RR-001", "Risk Entry");
            setField(riskRecord, "id", recordId);
            var mapping =
                    RiskControlMapping.forControlRecord(project, control, riskRecord, MappingControlRole.PREVENTIVE);

            when(repository.findByProjectIdAndRiskRegisterRecordId(projectId, recordId))
                    .thenReturn(List.of(mapping));

            var result = service.listByRecord(projectId, recordId);

            assertThat(result).containsExactly(mapping);
        }

        @Test
        void listByScopedImplementation_delegatesToRepository() {
            var sciId = UUID.randomUUID();
            var sci = new com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation(
                    project, "SCI-001", control, "Email Gateway");
            setField(sci, "id", sciId);
            var mapping = RiskControlMapping.forScopedScenario(project, sci, scenario, MappingControlRole.PREVENTIVE);

            when(repository.findByProjectIdAndScopedImplementationId(projectId, sciId))
                    .thenReturn(List.of(mapping));

            var result = service.listByScopedImplementation(projectId, sciId);

            assertThat(result).containsExactly(mapping);
        }
    }

    @Nested
    class C8ObservationsAndEvidence {

        @Test
        void attachObservation_addsObservationToMapping() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);

            var asset = new OperationalAsset(project, "ASSET-001", "Server");
            setField(asset, "id", UUID.randomUUID());

            var observationId = UUID.randomUUID();
            var observation = new Observation(
                    asset,
                    ObservationCategory.CONFIGURATION,
                    "key",
                    "value",
                    "scanner",
                    Instant.parse("2026-05-01T00:00:00Z"));
            setField(observation, "id", observationId);

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));
            when(observationRepository.findByIdWithAssetAndProjectId(observationId, projectId))
                    .thenReturn(Optional.of(observation));
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.attachObservation(projectId, mappingId, observationId);

            assertThat(result.getObservations()).contains(observation);
            verify(repository).save(mapping);
        }

        @Test
        void detachObservation_removesObservationFromMapping() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);

            var asset = new OperationalAsset(project, "ASSET-001", "Server");
            setField(asset, "id", UUID.randomUUID());

            var observationId = UUID.randomUUID();
            var observation = new Observation(
                    asset,
                    ObservationCategory.CONFIGURATION,
                    "key",
                    "value",
                    "scanner",
                    Instant.parse("2026-05-01T00:00:00Z"));
            setField(observation, "id", observationId);
            mapping.addObservation(observation);

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));
            when(observationRepository.findByIdWithAssetAndProjectId(observationId, projectId))
                    .thenReturn(Optional.of(observation));
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = service.detachObservation(projectId, mappingId, observationId);

            assertThat(result.getObservations()).isEmpty();
        }

        @Test
        void addEvidenceRef_addsRefToMapping() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var result =
                    service.addEvidenceRef(projectId, mappingId, "https://evidence.example.com", "Test note", null);

            assertThat(result.getEvidenceRefs()).hasSize(1);
            assertThat(result.getEvidenceRefs().get(0).getEvidenceRef()).isEqualTo("https://evidence.example.com");
            assertThat(result.getEvidenceRefs().get(0).getEvidenceNote()).isEqualTo("Test note");
        }

        @Test
        void attachObservation_throwsNotFound_whenMappingAbsent() {
            var mappingId = UUID.randomUUID();
            var observationId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.attachObservation(projectId, mappingId, observationId))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void attachObservation_throwsNotFound_whenObservationAbsent() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);
            var observationId = UUID.randomUUID();

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));
            when(observationRepository.findByIdWithAssetAndProjectId(observationId, projectId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.attachObservation(projectId, mappingId, observationId))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
