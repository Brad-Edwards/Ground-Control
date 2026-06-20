package com.keplerops.groundcontrol.unit.domain.riskcontrol;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.model.ControlEffectivenessAssessment;
import com.keplerops.groundcontrol.domain.controls.repository.ControlEffectivenessAssessmentRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlEffectivenessRating;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlCoverageService;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskRegisterRecordRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import java.time.LocalDate;
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

    @Mock
    private ThreatModelRepository threatModelRepository;

    @Mock
    private ControlEffectivenessAssessmentRepository effectivenessRepository;

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

    @Nested
    class ThreatsInsufficientEffectiveness {

        @Test
        void returnsEmptyWhenNoThreatMappingsExist() {
            when(effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                            any(UUID.class), any(LocalDate.class)))
                    .thenReturn(List.of());
            when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of());

            var result = service.findThreatsWithInsufficientControlEffectiveness(projectId, null, null, null);
            assertThat(result).isEmpty();
        }

        @Test
        void flagsThreatWhenNoControlPassesBar() {
            var threatModel = makeThreat("TM-001");
            var threatId = UUID.randomUUID();
            setField(threatModel, "id", threatId);

            var ctrl = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
            var ctrlId = UUID.randomUUID();
            setField(ctrl, "id", ctrlId);

            var mapping =
                    RiskControlMapping.forControlThreat(project, ctrl, threatModel, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", UUID.randomUUID());

            var assessment = makeAssessment(ctrl, ControlEffectivenessRating.INEFFECTIVE, LocalDate.of(2026, 6, 1));

            when(effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                            eq(projectId), any(LocalDate.class)))
                    .thenReturn(List.of(assessment));
            when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(mapping));
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(threatModel));

            var result = service.findThreatsWithInsufficientControlEffectiveness(
                    projectId, ControlEffectivenessRating.EFFECTIVE, LocalDate.of(2026, 6, 20), 90);

            assertThat(result).containsExactly(threatModel);
        }

        @Test
        void doesNotFlagThreatWhenControlPassesBar() {
            var threatModel = makeThreat("TM-001");
            var threatId = UUID.randomUUID();
            setField(threatModel, "id", threatId);

            var ctrl = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
            var ctrlId = UUID.randomUUID();
            setField(ctrl, "id", ctrlId);

            var mapping =
                    RiskControlMapping.forControlThreat(project, ctrl, threatModel, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", UUID.randomUUID());

            var assessment = makeAssessment(ctrl, ControlEffectivenessRating.EFFECTIVE, LocalDate.of(2026, 6, 1));

            when(effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                            eq(projectId), any(LocalDate.class)))
                    .thenReturn(List.of(assessment));
            when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(mapping));

            var result = service.findThreatsWithInsufficientControlEffectiveness(
                    projectId, ControlEffectivenessRating.EFFECTIVE, LocalDate.of(2026, 6, 20), 90);

            assertThat(result).isEmpty();
        }

        @Test
        void flagsThreatWhenOnlyPassingControlAssessmentIsStale() {
            // A stale EFFECTIVE assessment (outside freshnessWindowDays before asOf) must NOT count
            // as demonstrated coverage. Without the freshness filter the control's EFFECTIVE rating
            // would wrongly exempt the threat; with it, the stale assessment is dropped and the
            // threat is flagged. This exercises the freshness window directly.
            var threatModel = makeThreat("TM-001");
            setField(threatModel, "id", UUID.randomUUID());

            var ctrl = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
            setField(ctrl, "id", UUID.randomUUID());

            var mapping =
                    RiskControlMapping.forControlThreat(project, ctrl, threatModel, MappingControlRole.PREVENTIVE);
            setField(mapping, "id", UUID.randomUUID());

            // EFFECTIVE, but assessed 2025-01-01 — well outside a 90-day window before 2026-06-20.
            var staleAssessment = makeAssessment(ctrl, ControlEffectivenessRating.EFFECTIVE, LocalDate.of(2025, 1, 1));

            when(effectivenessRepository.findByProjectIdAndAssessedAtLessThanEqualOrderByControlIdAscAssessedAtDesc(
                            eq(projectId), any(LocalDate.class)))
                    .thenReturn(List.of(staleAssessment));
            when(mappingRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(mapping));
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(threatModel));

            var result = service.findThreatsWithInsufficientControlEffectiveness(
                    projectId, ControlEffectivenessRating.EFFECTIVE, LocalDate.of(2026, 6, 20), 90);

            assertThat(result).containsExactly(threatModel);
        }
    }

    // ---- Helpers ----

    private RiskScenario makeScenario(String uid) {
        return new RiskScenario(project, uid, "Scenario " + uid, "Attacker", "Phishing", "User", "Data breach");
    }

    private RiskRegisterRecord makeRecord(String uid) {
        return new RiskRegisterRecord(project, uid, "Record " + uid);
    }

    private ThreatModel makeThreat(String uid) {
        return new ThreatModel(project, uid, "Threat " + uid, "Attacker", "Attack", "Impact");
    }

    private ControlEffectivenessAssessment makeAssessment(
            Control ctrl, ControlEffectivenessRating rating, LocalDate assessedAt) {
        var assessment = new ControlEffectivenessAssessment(
                project, ctrl, "CEA-" + uid(), rating, rating, assessedAt, "Test assessor");
        setField(assessment, "id", UUID.randomUUID());
        return assessment;
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
