package com.keplerops.groundcontrol.unit.domain.riskcontrol;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlCoverageService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for C5a and C6 coverage queries. */
@ExtendWith(MockitoExtension.class)
class RiskControlCoverageServiceTest {

    @Mock
    private RiskControlMappingRepository mappingRepository;

    @Mock
    private RiskScenarioRepository scenarioRepository;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ThreatModelRepository threatModelRepository;

    @InjectMocks
    private RiskControlCoverageService service;

    private UUID projectId;
    private Project project;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        project = new Project("test", "Test");
        setField(project, "id", projectId);
    }

    @Nested
    class C5a_UnmappedScenarios {

        @Test
        void returnsEmptyWhenAllScenariosMapped() {
            when(mappingRepository.findUnmappedScenarioIds(projectId)).thenReturn(List.of());
            assertThat(service.findUnmappedScenarios(projectId)).isEmpty();
        }

        @Test
        void returnsUnmappedScenarioObjects() {
            var scenarioId = UUID.randomUUID();
            var scenario = makeScenario("RS-001");
            setField(scenario, "id", scenarioId);

            when(mappingRepository.findUnmappedScenarioIds(projectId)).thenReturn(List.of(scenarioId));
            when(scenarioRepository.findByIdInAndProjectId(List.of(scenarioId), projectId))
                    .thenReturn(List.of(scenario));

            var result = service.findUnmappedScenarios(projectId);
            assertThat(result).containsExactly(scenario);
        }
    }

    @Nested
    class C6_UnmappedControls {

        @Test
        void returnsEmptyWhenAllControlsMapped() {
            when(mappingRepository.findUnmappedControlIds(projectId)).thenReturn(List.of());
            assertThat(service.findUnmappedControls(projectId)).isEmpty();
        }

        @Test
        void returnsUnmappedControlObjects() {
            var controlId = UUID.randomUUID();
            var control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
            setField(control, "id", controlId);

            when(mappingRepository.findUnmappedControlIds(projectId)).thenReturn(List.of(controlId));
            when(controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(control));

            var result = service.findUnmappedControls(projectId);
            assertThat(result).containsExactly(control);
        }

        @Test
        void excludesMappedControlsPresentInRepoResult() {
            // The stream filter `c -> ids.contains(c.getId())` must actually discriminate.
            // Place a mapped control alongside the unmapped one so the filter is proven
            // to reject a non-matching item.
            var unmappedId = UUID.randomUUID();
            var unmappedControl = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
            setField(unmappedControl, "id", unmappedId);

            var mappedId = UUID.randomUUID();
            var mappedControl = new Control(project, "CTRL-002", "Incident Response", ControlFunction.CORRECTIVE);
            setField(mappedControl, "id", mappedId);

            // Only the unmapped control's ID is in the unmapped set.
            when(mappingRepository.findUnmappedControlIds(projectId)).thenReturn(List.of(unmappedId));
            // Repository returns both; filter must keep only unmappedControl.
            when(controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(unmappedControl, mappedControl));

            var result = service.findUnmappedControls(projectId);
            assertThat(result).containsExactly(unmappedControl);
        }
    }

    @Nested
    class ThreatUnmappedThreats {

        @Test
        void returnsEmptyWhenAllThreatsMapped() {
            when(mappingRepository.findUnmappedThreatIds(projectId)).thenReturn(List.of());
            assertThat(service.findUnmappedThreats(projectId)).isEmpty();
        }

        @Test
        void returnsUnmappedThreatObjects() {
            var threatId = UUID.randomUUID();
            var threat = makeThreat("TM-001");
            setField(threat, "id", threatId);

            when(mappingRepository.findUnmappedThreatIds(projectId)).thenReturn(List.of(threatId));
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(threat));

            var result = service.findUnmappedThreats(projectId);
            assertThat(result).containsExactly(threat);
        }

        @Test
        void excludesMappedThreatsPresentInRepoResult() {
            // The stream filter `t -> ids.contains(t.getId())` must actually discriminate.
            // Place a mapped threat (not in the unmapped-ID set) alongside the unmapped one so the
            // filter is proven to reject a non-matching item — removing it would be undetected.
            var unmappedId = UUID.randomUUID();
            var unmappedThreat = makeThreat("TM-001");
            setField(unmappedThreat, "id", unmappedId);

            var mappedId = UUID.randomUUID();
            var mappedThreat = makeThreat("TM-002");
            setField(mappedThreat, "id", mappedId);

            // Only the unmapped threat's ID is in the unmapped set.
            when(mappingRepository.findUnmappedThreatIds(projectId)).thenReturn(List.of(unmappedId));
            // Repository returns both; filter must keep only unmappedThreat.
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(unmappedThreat, mappedThreat));

            var result = service.findUnmappedThreats(projectId);
            assertThat(result).containsExactly(unmappedThreat);
        }
    }

    @Nested
    class ThreatUnmappedControls {

        @Test
        void returnsEmptyWhenAllControlsMappedToThreats() {
            when(mappingRepository.findControlIdsUnmappedToThreats(projectId)).thenReturn(List.of());
            assertThat(service.findControlsUnmappedToThreats(projectId)).isEmpty();
        }

        @Test
        void returnsControlsNotMappedToAnyThreat() {
            var controlId = UUID.randomUUID();
            var ctrl = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
            setField(ctrl, "id", controlId);

            when(mappingRepository.findControlIdsUnmappedToThreats(projectId)).thenReturn(List.of(controlId));
            when(controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(ctrl));

            var result = service.findControlsUnmappedToThreats(projectId);
            assertThat(result).containsExactly(ctrl);
        }

        @Test
        void excludesControlsMappedToThreatsPresentInRepoResult() {
            // The stream filter `c -> ids.contains(c.getId())` must actually discriminate.
            // Place a threat-mapped control (not in the unmapped-ID set) alongside the unmapped
            // one so the filter is proven to reject a non-matching item.
            var unmappedId = UUID.randomUUID();
            var unmappedControl = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
            setField(unmappedControl, "id", unmappedId);

            var mappedId = UUID.randomUUID();
            var mappedControl = new Control(project, "CTRL-002", "Incident Response", ControlFunction.CORRECTIVE);
            setField(mappedControl, "id", mappedId);

            // Only the unmapped control's ID is in the unmapped-to-threats set.
            when(mappingRepository.findControlIdsUnmappedToThreats(projectId)).thenReturn(List.of(unmappedId));
            // Repository returns both; filter must keep only unmappedControl.
            when(controlRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(unmappedControl, mappedControl));

            var result = service.findControlsUnmappedToThreats(projectId);
            assertThat(result).containsExactly(unmappedControl);
        }
    }

    // ---- Helpers ----

    private RiskScenario makeScenario(String uid) {
        return new RiskScenario(project, uid, "Scenario " + uid, "Attacker", "Phishing", "User", "Data breach");
    }

    private ThreatModel makeThreat(String uid) {
        return new ThreatModel(project, uid, "Threat " + uid, "Attacker", "Attack", "Impact");
    }
}
