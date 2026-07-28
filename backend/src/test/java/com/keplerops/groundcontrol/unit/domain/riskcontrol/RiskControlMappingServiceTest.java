package com.keplerops.groundcontrol.unit.domain.riskcontrol;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.ObservationRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
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
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
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
                    null, // threatModelId
                    null, // operationalAssetId
                    "Prevent credential theft", // mappingObjective
                    MappingControlRole.PREVENTIVE, // controlRole
                    "Email gateway only", // mappingScope
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
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsNotFound_whenControlNotInProject() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.empty());

            var cmd = new CreateRiskControlMappingCommand(
                    projectId, controlId, null, scenarioId, null, null, null, MappingControlRole.DETECTIVE, null, null);

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
                    null);

            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsValidation_whenBothControlEndpointsNull() {
            var cmd = new CreateRiskControlMappingCommand(
                    projectId, null, null, scenarioId, null, null, null, MappingControlRole.PREVENTIVE, null, null);
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
                    null);
            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void throwsValidation_whenBothRiskEndpointsNull() {
            // Both analysis endpoints null → count=0 → DomainValidationException
            var cmd = new CreateRiskControlMappingCommand(
                    projectId, controlId, null, null, null, null, null, MappingControlRole.PREVENTIVE, null, null);
            assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(DomainValidationException.class);
        }

        @Test
        void throwsValidation_whenBothRiskEndpointsNonNull() {
            // riskScenarioId + threatModelId both set → count=2 → DomainValidationException
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
                    projectId, null, sciId, scenarioId, null, null, null, MappingControlRole.PREVENTIVE, null, null);

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
                    projectId, mappingId, "Updated objective", MappingControlRole.DETECTIVE, null, null);

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

            var cmd =
                    new UpdateRiskControlMappingCommand(projectId, mappingId, null, null, "Email perimeter only", null);

            var result = service.update(cmd);

            assertThat(result.getMappingScope()).isEqualTo("Email perimeter only");
        }

        @Test
        void updatesMethodologyInfluence() {
            var mappingId = UUID.randomUUID();
            var mapping =
                    RiskControlMapping.forControlScenario(project, control, scenario, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", mappingId);

            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.of(mapping));
            when(repository.save(any(RiskControlMapping.class))).thenAnswer(inv -> inv.getArgument(0));

            var influence = java.util.Map.<String, Object>of("note", "manual influence");
            var cmd = new UpdateRiskControlMappingCommand(projectId, mappingId, null, null, null, influence);

            var result = service.update(cmd);

            assertThat(result.getMethodologyInfluence()).isEqualTo(influence);
        }

        @Test
        void throwsNotFound_whenMappingNotInProject() {
            var mappingId = UUID.randomUUID();
            when(repository.findByIdAndProjectId(mappingId, projectId)).thenReturn(Optional.empty());

            var cmd = new UpdateRiskControlMappingCommand(projectId, mappingId, null, null, null, null);

            assertThatThrownBy(() -> service.update(cmd)).isInstanceOf(NotFoundException.class);
        }
    }
}
