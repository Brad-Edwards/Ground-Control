package com.keplerops.groundcontrol.unit.domain.grcanalysis;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceFrameworkMappingService;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import com.keplerops.groundcontrol.domain.compliance.state.GapSeverity;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.grcanalysis.service.CrossFrameworkGapService;
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
class CrossFrameworkGapServiceTest {

    @Mock
    private ComplianceFrameworkMappingService mappingService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private CrossFrameworkGapService service;

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

    private ComplianceFrameworkMapping requirementMapping(String element, CoverageLevel level) {
        var m = ComplianceFrameworkMapping.forRequirement(
                project, requirement, ComplianceFrameworkIdentifier.SOC2, element, level);
        setField(m, "id", UUID.randomUUID());
        return m;
    }

    private ComplianceFrameworkMapping controlMapping(String element, CoverageLevel level) {
        var m = ComplianceFrameworkMapping.forControl(
                project, control, ComplianceFrameworkIdentifier.SOC2, element, level);
        setField(m, "id", UUID.randomUUID());
        return m;
    }

    @Test
    void fullCoverage_setsSeverityNone() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID))
                .thenReturn(List.of(requirementMapping("CC1.1", CoverageLevel.FULL)));

        var result = service.analyze(PROJECT_ID, null, null, null);

        var element = result.frameworks().get(0).elementGaps().get(0);
        assertThat(element.severity()).isEqualTo(GapSeverity.NONE);
        assertThat(element.coverageStatus()).isEqualTo("FULL");
    }

    @Test
    void partialOnly_setsSeverityHigh() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID))
                .thenReturn(List.of(controlMapping("CC2.1", CoverageLevel.PARTIAL)));

        var result = service.analyze(PROJECT_ID, null, null, null);

        var element = result.frameworks().get(0).elementGaps().get(0);
        assertThat(element.severity()).isEqualTo(GapSeverity.HIGH);
        assertThat(element.coverageStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void partialWithCompensating_setsSeverityMedium() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID))
                .thenReturn(List.of(
                        controlMapping("CC3.1", CoverageLevel.PARTIAL),
                        controlMapping("CC3.1", CoverageLevel.COMPENSATING)));

        var result = service.analyze(PROJECT_ID, null, null, null);

        var element = result.frameworks().get(0).elementGaps().get(0);
        assertThat(element.severity()).isEqualTo(GapSeverity.MEDIUM);
    }

    @Test
    void compensatingOnly_setsSeverityLow() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID))
                .thenReturn(List.of(controlMapping("CC4.1", CoverageLevel.COMPENSATING)));

        var result = service.analyze(PROJECT_ID, null, null, null);

        var element = result.frameworks().get(0).elementGaps().get(0);
        assertThat(element.severity()).isEqualTo(GapSeverity.LOW);
        assertThat(element.coverageStatus()).isEqualTo("COMPENSATING_ONLY");
    }

    @Test
    void fullWithCompensating_setsSeverityLow() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID))
                .thenReturn(List.of(
                        controlMapping("CC5.1", CoverageLevel.FULL),
                        controlMapping("CC5.1", CoverageLevel.COMPENSATING)));

        var result = service.analyze(PROJECT_ID, null, null, null);

        var element = result.frameworks().get(0).elementGaps().get(0);
        assertThat(element.severity()).isEqualTo(GapSeverity.LOW);
        assertThat(element.coverageStatus()).isEqualTo("FULL");
    }

    @Test
    void minSeverityFilter_excludesLessSevereElements() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID))
                .thenReturn(List.of(
                        controlMapping("CC1.1", CoverageLevel.FULL), // NONE
                        controlMapping("CC2.1", CoverageLevel.PARTIAL))); // HIGH

        var result = service.analyze(PROJECT_ID, null, null, GapSeverity.HIGH);

        // Only HIGH-or-more-severe should remain. (CRITICAL > HIGH > MEDIUM > LOW > NONE
        // in declaration order; the filter keeps severities <= minSeverity index.)
        assertThat(result.frameworks().get(0).elementGaps()).hasSize(1);
        assertThat(result.frameworks().get(0).elementGaps().get(0).severity()).isEqualTo(GapSeverity.HIGH);
    }

    @Test
    void countsBySeverity_includeAllBuckets() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(mappingService.listByProject(PROJECT_ID)).thenReturn(List.of(controlMapping("CC1.1", CoverageLevel.FULL)));

        var result = service.analyze(PROJECT_ID, null, null, null);

        assertThat(result.frameworks().get(0).bySeverity())
                .containsKey("CRITICAL")
                .containsKey("HIGH")
                .containsKey("MEDIUM")
                .containsKey("LOW")
                .containsKey("NONE");
    }
}
