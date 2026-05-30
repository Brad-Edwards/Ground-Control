package com.keplerops.groundcontrol.unit.domain.interchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.interchange.service.GrcInterchangeExporter;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrcInterchangeExporterTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000910");

    private ProjectService projectService;
    private OperationalAssetRepository assetRepository;
    private RiskScenarioRepository riskScenarioRepository;
    private ControlRepository controlRepository;
    private FindingRepository findingRepository;
    private EvidenceArtifactRepository evidenceArtifactRepository;
    private GrcInterchangeExporter exporter;
    private Project project;

    @BeforeEach
    void setup() {
        projectService = mock(ProjectService.class);
        assetRepository = mock(OperationalAssetRepository.class);
        riskScenarioRepository = mock(RiskScenarioRepository.class);
        controlRepository = mock(ControlRepository.class);
        findingRepository = mock(FindingRepository.class);
        evidenceArtifactRepository = mock(EvidenceArtifactRepository.class);
        exporter = new GrcInterchangeExporter(
                projectService,
                assetRepository,
                riskScenarioRepository,
                controlRepository,
                findingRepository,
                evidenceArtifactRepository);
        project = new Project("ground-control", "Ground Control");
        TestUtil.setField(project, "id", PROJECT_ID);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(assetRepository.findByProjectIdAndArchivedAtIsNull(any())).thenReturn(List.of());
        when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(findingRepository.findByProjectIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(evidenceArtifactRepository.findByProjectIdOrderByDerivedAtDesc(any()))
                .thenReturn(List.of());
    }

    @Test
    void controlPayloadEncodesFunctionAndCategoryNotLifecycleStatus() {
        var control = new Control(project, "CTL-1", "Encryption at rest", ControlFunction.PREVENTIVE);
        control.setCategory("crypto");
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(control));

        var bundle = exporter.export(PROJECT_ID);

        // Round-trip integrity: controlType is a taxonomy classifier, not a
        // lifecycle status like DRAFT/ACTIVE/RETIRED.
        assertThat(bundle.controls()).hasSize(1);
        assertThat(bundle.controls().get(0).controlType()).isEqualTo("PREVENTIVE:crypto");
    }

    @Test
    void controlPayloadFunctionOnlyWhenCategoryAbsent() {
        var control = new Control(project, "CTL-2", "Detective monitor", ControlFunction.DETECTIVE);
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(control));

        var bundle = exporter.export(PROJECT_ID);

        assertThat(bundle.controls().get(0).controlType()).isEqualTo("DETECTIVE");
    }

    @Test
    void controlPayloadBlankCategoryFallsBackToFunctionAlone() {
        var control = new Control(project, "CTL-3", "Corrective rollback", ControlFunction.CORRECTIVE);
        control.setCategory("   ");
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(control));

        var bundle = exporter.export(PROJECT_ID);

        assertThat(bundle.controls().get(0).controlType()).isEqualTo("CORRECTIVE");
    }
}
