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
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
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

/** Split from RiskControlMappingServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class RiskControlMappingServiceDeleteAndReadTest {
    @Mock
    private RiskControlMappingRepository repository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.riskcontrol.repository.ScopedControlImplementationRepository
            scopedControlImplementationRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository threatModelRepository;

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

    @Nested
    class CreateWithThreatModel {

        @Test
        void createsMapping_controlToThreat() {
            var threatModel = new com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel(
                    project, "TM-001", "SQL Injection", "Attacker", "Inject SQL", "Data exfiltration");
            var threatModelId = UUID.randomUUID();
            setField(threatModel, "id", threatModelId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(threatModelRepository.findByIdAndProjectId(threatModelId, projectId))
                    .thenReturn(Optional.of(threatModel));
            when(repository.existsByControlIdAndThreatModelIdAndOperationalAssetId(controlId, threatModelId, null))
                    .thenReturn(false);
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId, // controlId
                    null, // scopedImplementationId
                    null, // riskScenarioId
                    threatModelId, // threatModelId
                    null, // operationalAssetId
                    null, // mappingObjective
                    MappingControlRole.PREVENTIVE, // controlRole
                    null, // mappingScope
                    null // methodologyInfluence
                    );

            var result = service.create(cmd);

            assertThat(result.getControl()).isEqualTo(control);
            assertThat(result.getThreatModel()).isEqualTo(threatModel);
            assertThat(result.isThreatSide()).isTrue();
            verify(repository).save(any(RiskControlMapping.class));
        }

        @Test
        void createsMapping_scopedImplToThreat() {
            var sciId = UUID.randomUUID();
            var sci = new com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation(
                    project, "SCI-001", control, "Email Gateway");
            setField(sci, "id", sciId);

            var threatModel = new com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel(
                    project, "TM-001", "SQL Injection", "Attacker", "Inject SQL", "Data exfiltration");
            var threatModelId = UUID.randomUUID();
            setField(threatModel, "id", threatModelId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(scopedControlImplementationRepository.findByIdAndProjectId(sciId, projectId))
                    .thenReturn(Optional.of(sci));
            when(threatModelRepository.findByIdAndProjectId(threatModelId, projectId))
                    .thenReturn(Optional.of(threatModel));
            when(repository.existsByScopedImplementationIdAndThreatModelIdAndOperationalAssetId(
                            sciId, threatModelId, null))
                    .thenReturn(false);
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    null, // controlId
                    sciId, // scopedImplementationId
                    null, // riskScenarioId
                    threatModelId, // threatModelId
                    null,
                    null,
                    MappingControlRole.DETECTIVE,
                    null,
                    null);

            var result = service.create(cmd);
            assertThat(result.getScopedImplementation()).isEqualTo(sci);
            assertThat(result.getThreatModel()).isEqualTo(threatModel);
        }

        @Test
        void throwsNotFound_whenThreatModelNotInProject() {
            var threatModelId = UUID.randomUUID();
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(threatModelRepository.findByIdAndProjectId(threatModelId, projectId))
                    .thenReturn(Optional.empty());

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    null,
                    threatModelId,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsConflict_whenDuplicateControlThreatExists() {
            var threatModel = new com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel(
                    project, "TM-001", "SQL Injection", "Attacker", "Inject SQL", "Data exfiltration");
            var threatModelId = UUID.randomUUID();
            setField(threatModel, "id", threatModelId);

            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(threatModelRepository.findByIdAndProjectId(threatModelId, projectId))
                    .thenReturn(Optional.of(threatModel));
            when(repository.existsByControlIdAndThreatModelIdAndOperationalAssetId(controlId, threatModelId, null))
                    .thenReturn(true);

            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    null,
                    threatModelId,
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsValidation_whenBothAnalysisEndpointsNull() {
            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    null, // riskScenarioId
                    null, // threatModelId
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null);
            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void throwsValidation_whenTwoAnalysisEndpointsProvided() {
            var cmd = new CreateRiskControlMappingCommand(
                    projectId,
                    controlId,
                    null,
                    scenarioId, // riskScenarioId
                    UUID.randomUUID(), // threatModelId — both provided!
                    null,
                    null,
                    MappingControlRole.PREVENTIVE,
                    null,
                    null);
            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }
    }
}
