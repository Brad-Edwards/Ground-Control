package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.repository.ComplianceFrameworkMappingRepository;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.service.ComplianceFrameworkMappingGraphProjectionContributor;
import com.keplerops.groundcontrol.domain.projects.model.Project;
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
class ComplianceFrameworkMappingGraphProjectionContributorTest {

    @Mock
    private ComplianceFrameworkMappingRepository repository;

    @InjectMocks
    private ComplianceFrameworkMappingGraphProjectionContributor contributor;

    private Project project;
    private UUID projectId;
    private Requirement requirement;
    private Control control;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        setField(project, "id", projectId);
        requirement = new Requirement(project, "GC-Q001", "Req", "Statement");
        setField(requirement, "id", UUID.fromString("00000000-0000-0000-0000-000000000010"));
        control = new Control(project, "CTRL-001", "Access", ControlFunction.PREVENTIVE);
        setField(control, "id", UUID.fromString("00000000-0000-0000-0000-000000000020"));
    }

    @Test
    void requirementMapping_emitsMapsRequirementEdge() {
        var mapping = ComplianceFrameworkMapping.forRequirement(
                project, requirement, ComplianceFrameworkIdentifier.SOC2, "CC1.1", CoverageLevel.FULL);
        setField(mapping, "id", UUID.fromString("00000000-0000-0000-0000-000000000aaa"));
        when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));

        var nodes = contributor.contributeNodes(projectId);
        var edges = contributor.contributeEdges(projectId);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).entityType()).isEqualTo(GraphEntityType.COMPLIANCE_FRAMEWORK_MAPPING);
        assertThat(nodes.get(0).properties()).containsEntry("framework", "SOC2");
        assertThat(nodes.get(0).properties()).containsEntry("frameworkElement", "CC1.1");
        assertThat(nodes.get(0).properties()).containsEntry("coverageLevel", "FULL");

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).edgeType()).isEqualTo("MAPS_REQUIREMENT");
        assertThat(edges.get(0).sourceEntityType()).isEqualTo(GraphEntityType.COMPLIANCE_FRAMEWORK_MAPPING);
        assertThat(edges.get(0).targetEntityType()).isEqualTo(GraphEntityType.REQUIREMENT);
    }

    @Test
    void controlMapping_emitsMapsControlEdge() {
        var mapping = ComplianceFrameworkMapping.forControl(
                project, control, ComplianceFrameworkIdentifier.ISO_27001, "A.5.1", CoverageLevel.PARTIAL);
        setField(mapping, "id", UUID.fromString("00000000-0000-0000-0000-000000000bbb"));
        when(repository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(mapping));

        var edges = contributor.contributeEdges(projectId);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).edgeType()).isEqualTo("MAPS_CONTROL_TO_FRAMEWORK");
        assertThat(edges.get(0).targetEntityType()).isEqualTo(GraphEntityType.CONTROL);
    }
}
