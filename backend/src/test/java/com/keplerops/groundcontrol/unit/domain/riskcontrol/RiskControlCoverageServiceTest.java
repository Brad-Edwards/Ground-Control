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
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for C5a, C5b, and C6 coverage queries. */
@ExtendWith(MockitoExtension.class)
class RiskControlCoverageServiceTest {

    @Mock
    private RiskControlMappingRepository mappingRepository;

    @Mock
    private RiskScenarioRepository scenarioRepository;

    @Mock
    private RiskRegisterRecordRepository recordRepository;

    @Mock
    private ControlRepository controlRepository;

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
    class C5b_UnmappedRecords {

        @Test
        void returnsEmptyWhenAllRecordsMapped() {
            when(mappingRepository.findDirectlyUnmappedRecordIds(projectId)).thenReturn(List.of());
            assertThat(service.findUnmappedRecords(projectId, false)).isEmpty();
            assertThat(service.findUnmappedRecords(projectId, true)).isEmpty();
        }

        @Test
        void directMode_returnsDirectlyUnmappedRecords() {
            var recordId = UUID.randomUUID();
            var riskRecord = makeRecord("RR-001");
            setField(riskRecord, "id", recordId);

            when(mappingRepository.findDirectlyUnmappedRecordIds(projectId)).thenReturn(List.of(recordId));
            when(recordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(riskRecord));

            var result = service.findUnmappedRecords(projectId, false);
            assertThat(result).containsExactly(riskRecord);
        }

        @Test
        void directMode_excludesMappedRecordsPresentInRepoResult() {
            // The stream filter `r -> directlyUnmappedIds.contains(r.getId())` must
            // actually discriminate. This test places a mapped record (not in the
            // unmapped-ID set) alongside an unmapped record so the filter is exercised
            // against a non-matching item.
            var unmappedId = UUID.randomUUID();
            var unmappedRecord = makeRecord("RR-001");
            setField(unmappedRecord, "id", unmappedId);

            var mappedId = UUID.randomUUID();
            var mappedRecord = makeRecord("RR-002");
            setField(mappedRecord, "id", mappedId);

            // Only the unmapped record's ID is in the directly-unmapped set.
            when(mappingRepository.findDirectlyUnmappedRecordIds(projectId)).thenReturn(List.of(unmappedId));
            // Repository returns both records; filter must keep only unmappedRecord.
            when(recordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(unmappedRecord, mappedRecord));

            var result = service.findUnmappedRecords(projectId, false);
            assertThat(result).containsExactly(unmappedRecord);
        }

        @Test
        void transitiveMode_excludesRecordsWhoseAllScenariosAreMapped() {
            // Record has one scenario, and that scenario IS mapped (not in unmappedScenarioIds)
            var scenarioId = UUID.randomUUID();
            var scenario = makeScenario("RS-001");
            setField(scenario, "id", scenarioId);

            var recordId = UUID.randomUUID();
            var riskRecord = makeRecord("RR-001");
            setField(riskRecord, "id", recordId);
            riskRecord.replaceRiskScenarios(List.of(scenario));

            when(mappingRepository.findDirectlyUnmappedRecordIds(projectId)).thenReturn(List.of(recordId));
            when(recordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(riskRecord));
            // Scenario IS mapped — not in unmapped list
            when(mappingRepository.findUnmappedScenarioIds(projectId)).thenReturn(List.of());

            var result = service.findUnmappedRecords(projectId, true);
            // Record is transitively covered because all its scenarios are mapped
            assertThat(result).isEmpty();
        }

        @Test
        void transitiveMode_includesRecordsWhoseSomeScenariosAreUnmapped() {
            var scenarioId = UUID.randomUUID();
            var scenario = makeScenario("RS-001");
            setField(scenario, "id", scenarioId);

            var recordId = UUID.randomUUID();
            var riskRecord = makeRecord("RR-001");
            setField(riskRecord, "id", recordId);
            riskRecord.replaceRiskScenarios(List.of(scenario));

            when(mappingRepository.findDirectlyUnmappedRecordIds(projectId)).thenReturn(List.of(recordId));
            when(recordRepository.findByProjectIdWithScenariosOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(riskRecord));
            // Scenario is NOT mapped — still in unmapped list
            when(mappingRepository.findUnmappedScenarioIds(projectId)).thenReturn(List.of(scenarioId));

            var result = service.findUnmappedRecords(projectId, true);
            assertThat(result).containsExactly(riskRecord);
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

    // ---- Helpers ----

    private RiskScenario makeScenario(String uid) {
        return new RiskScenario(project, uid, "Scenario " + uid, "Attacker", "Phishing", "User", "Data breach");
    }

    private RiskRegisterRecord makeRecord(String uid) {
        return new RiskRegisterRecord(project, uid, "Record " + uid);
    }
}
