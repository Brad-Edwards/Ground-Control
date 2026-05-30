package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceFrameworkMappingService;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.grcanalysis.service.CompliancePostureService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompliancePostureServiceTest {

    @Mock
    private ComplianceFrameworkMappingService mappingService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private CompliancePostureService service;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Project project;
    private Requirement requirement;
    private Control control;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        requirement = new Requirement(project, "GC-Q001", "Req", "Statement");
        setField(requirement, "id", UUID.fromString("00000000-0000-0000-0000-000000000010"));
        control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        setField(control, "id", UUID.fromString("00000000-0000-0000-0000-000000000020"));
    }

    private ComplianceFrameworkMapping requirementMapping(
            String element, CoverageLevel level, ComplianceFrameworkIdentifier framework) {
        var m = ComplianceFrameworkMapping.forRequirement(project, requirement, framework, element, level);
        setField(m, "id", UUID.randomUUID());
        return m;
    }

    private ComplianceFrameworkMapping controlMapping(
            String element, CoverageLevel level, ComplianceFrameworkIdentifier framework) {
        var m = ComplianceFrameworkMapping.forControl(project, control, framework, element, level);
        setField(m, "id", UUID.randomUUID());
        return m;
    }

    @Test
    void groupsMappingsByFrameworkAndElement() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID))
                .thenReturn(List.of(
                        requirementMapping("CC1.1", CoverageLevel.FULL, ComplianceFrameworkIdentifier.SOC2),
                        controlMapping("CC1.1", CoverageLevel.PARTIAL, ComplianceFrameworkIdentifier.SOC2),
                        controlMapping("A.5.1", CoverageLevel.FULL, ComplianceFrameworkIdentifier.ISO_27001)));

        var result = service.analyze(PROJECT_ID, null, null);

        assertThat(result.analysisKind()).isEqualTo("compliance_posture");
        assertThat(result.frameworks()).hasSize(2);
        // FULL element wins over PARTIAL within the same element
        var soc2 = result.frameworks().stream()
                .filter(f -> f.framework() == ComplianceFrameworkIdentifier.SOC2)
                .findFirst()
                .orElseThrow();
        assertThat(soc2.elements()).hasSize(1);
        assertThat(soc2.elements().get(0).coverageLevel()).isEqualTo(CoverageLevel.FULL);
        assertThat(soc2.elements().get(0).mappings()).hasSize(2);
    }

    @Test
    void filterByFramework_invokesScopedFetch() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByFramework(PROJECT_ID, ComplianceFrameworkIdentifier.SOC2))
                .thenReturn(List.of(
                        requirementMapping("CC1.1", CoverageLevel.COMPENSATING, ComplianceFrameworkIdentifier.SOC2)));

        var result = service.analyze(PROJECT_ID, null, ComplianceFrameworkIdentifier.SOC2);

        assertThat(result.frameworks()).hasSize(1);
        assertThat(result.frameworks().get(0).framework()).isEqualTo(ComplianceFrameworkIdentifier.SOC2);
        assertThat(result.frameworks().get(0).elements().get(0).coverageLevel()).isEqualTo(CoverageLevel.COMPENSATING);
    }

    @Test
    void externalIdentifier_appendsLimitation() {
        var mapping = requirementMapping("CC1.1", CoverageLevel.FULL, ComplianceFrameworkIdentifier.SOC2);
        mapping.setFrameworkIdentifier("Acme Custom Framework");
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID)).thenReturn(List.of(mapping));

        var result = service.analyze(PROJECT_ID, null, null);

        assertThat(result.limitations()).hasSize(1);
        assertThat(result.limitations().get(0)).contains("External framework identifier");
    }

    @Test
    void externalIdentifierWithNewline_isSanitizedInLimitation() {
        var mapping = requirementMapping("CC1.1\nINJECT", CoverageLevel.FULL, ComplianceFrameworkIdentifier.SOC2);
        mapping.setFrameworkIdentifier("ext");
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID)).thenReturn(List.of(mapping));

        var result = service.analyze(PROJECT_ID, null, null);

        assertThat(result.limitations()).hasSize(1);
        assertThat(result.limitations().get(0)).doesNotContain("\n");
        assertThat(result.limitations().get(0)).contains("CC1.1_INJECT");
    }

    @Test
    void countsByCoverageLevel_summed() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID))
                .thenReturn(List.of(
                        requirementMapping("CC1.1", CoverageLevel.FULL, ComplianceFrameworkIdentifier.SOC2),
                        controlMapping("CC2.1", CoverageLevel.PARTIAL, ComplianceFrameworkIdentifier.SOC2),
                        controlMapping("CC3.1", CoverageLevel.COMPENSATING, ComplianceFrameworkIdentifier.SOC2)));

        var result = service.analyze(PROJECT_ID, null, null);

        assertThat(result.counts().totalMappings()).isEqualTo(3);
        assertThat(result.counts().coverageLevelCounts()).containsEntry("FULL", 1);
        assertThat(result.counts().coverageLevelCounts()).containsEntry("PARTIAL", 1);
        assertThat(result.counts().coverageLevelCounts()).containsEntry("COMPENSATING", 1);
    }
}
