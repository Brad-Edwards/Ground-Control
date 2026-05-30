package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.compliance.ComplianceFrameworkMappingController;
import com.keplerops.groundcontrol.domain.compliance.model.ComplianceFrameworkMapping;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceFrameworkMappingService;
import com.keplerops.groundcontrol.domain.compliance.service.CreateComplianceFrameworkMappingCommand;
import com.keplerops.groundcontrol.domain.compliance.service.UpdateComplianceFrameworkMappingCommand;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceFrameworkIdentifier;
import com.keplerops.groundcontrol.domain.compliance.state.CoverageLevel;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ComplianceFrameworkMappingController.class)
class ComplianceFrameworkMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ComplianceFrameworkMappingService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REQUIREMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-0000-0000-000000000300");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private ComplianceFrameworkMapping makeRequirementMapping() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var requirement = new Requirement(project, "GC-Q001", "Some requirement", "Statement");
        setField(requirement, "id", REQUIREMENT_ID);

        var mapping = ComplianceFrameworkMapping.forRequirement(
                project, requirement, ComplianceFrameworkIdentifier.SOC2, "CC1.1", CoverageLevel.PARTIAL);
        mapping.setFrameworkVersion("2017 TSC");
        mapping.setRationale("Documented governance");
        setField(mapping, "id", MAPPING_ID);
        setField(mapping, "createdAt", NOW);
        setField(mapping, "updatedAt", NOW);
        return mapping;
    }

    @Test
    void createRequirementMappingReturns201WithFrameworkFields() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeRequirementMapping());

        mockMvc.perform(
                        post("/api/v1/compliance-framework-mappings")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "requirementId": "00000000-0000-0000-0000-000000000200",
                                  "framework": "SOC2",
                                  "frameworkIdentifier": "Acme SOC2",
                                  "frameworkVersion": "2017 TSC",
                                  "frameworkElement": "CC1.1",
                                  "coverageLevel": "PARTIAL",
                                  "rationale": "Documented governance"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(MAPPING_ID.toString())))
                .andExpect(jsonPath("$.requirementId", is(REQUIREMENT_ID.toString())))
                .andExpect(jsonPath("$.framework", is("SOC2")))
                .andExpect(jsonPath("$.frameworkElement", is("CC1.1")))
                .andExpect(jsonPath("$.coverageLevel", is("PARTIAL")));

        // Cluster-744 finding #6: assert the request->command field-pass-through
        // contract directly. The old `any()` form passed even if the controller
        // dropped a field before constructing the command, because the response
        // jsonPath assertions only re-read the hard-coded fixture.
        var captor = ArgumentCaptor.forClass(CreateComplianceFrameworkMappingCommand.class);
        verify(service).create(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.projectId()).isEqualTo(PROJECT_ID);
        assertThat(cmd.requirementId()).isEqualTo(REQUIREMENT_ID);
        assertThat(cmd.controlId()).isNull();
        assertThat(cmd.framework()).isEqualTo(ComplianceFrameworkIdentifier.SOC2);
        assertThat(cmd.frameworkIdentifier()).isEqualTo("Acme SOC2");
        assertThat(cmd.frameworkVersion()).isEqualTo("2017 TSC");
        assertThat(cmd.frameworkElement()).isEqualTo("CC1.1");
        assertThat(cmd.coverageLevel()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(cmd.rationale()).isEqualTo("Documented governance");
    }

    @Test
    void createControlMappingPassesControlIdAndNoRequirementId() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeRequirementMapping());

        mockMvc.perform(
                        post("/api/v1/compliance-framework-mappings")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "controlId": "00000000-0000-0000-0000-000000000201",
                                  "framework": "ISO_27001",
                                  "frameworkElement": "A.5.1",
                                  "coverageLevel": "FULL"
                                }
                                """))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(CreateComplianceFrameworkMappingCommand.class);
        verify(service).create(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.requirementId()).isNull();
        assertThat(cmd.controlId()).isEqualTo(CONTROL_ID);
        assertThat(cmd.framework()).isEqualTo(ComplianceFrameworkIdentifier.ISO_27001);
        assertThat(cmd.frameworkElement()).isEqualTo("A.5.1");
        assertThat(cmd.coverageLevel()).isEqualTo(CoverageLevel.FULL);
    }

    @Test
    void createWithBothEndpointsThrows() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any()))
                .thenThrow(new DomainValidationException("Exactly one of requirementId or controlId must be provided"));

        mockMvc.perform(
                        post("/api/v1/compliance-framework-mappings")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "requirementId": "00000000-0000-0000-0000-000000000200",
                                  "controlId": "00000000-0000-0000-0000-000000000201",
                                  "framework": "SOC2",
                                  "frameworkElement": "CC1.1",
                                  "coverageLevel": "PARTIAL"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createWithoutEndpointThrows() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any()))
                .thenThrow(new DomainValidationException("Exactly one of requirementId or controlId must be provided"));

        mockMvc.perform(
                        post("/api/v1/compliance-framework-mappings")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "framework": "SOC2",
                                  "frameworkElement": "CC1.1",
                                  "coverageLevel": "PARTIAL"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createWithMissingFrameworkReturns400() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/compliance-framework-mappings")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "requirementId": "00000000-0000-0000-0000-000000000200",
                                  "frameworkElement": "CC1.1",
                                  "coverageLevel": "PARTIAL"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createDuplicateReturns409() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenThrow(new ConflictException("Duplicate"));

        mockMvc.perform(
                        post("/api/v1/compliance-framework-mappings")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "requirementId": "00000000-0000-0000-0000-000000000200",
                                  "framework": "SOC2",
                                  "frameworkElement": "CC1.1",
                                  "coverageLevel": "PARTIAL"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void listAllReturns200() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID)).thenReturn(List.of(makeRequirementMapping()));

        mockMvc.perform(get("/api/v1/compliance-framework-mappings").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is(MAPPING_ID.toString())))
                .andExpect(jsonPath("$[0].framework", is("SOC2")));
    }

    @Test
    void listByFrameworkInvokesFilteredFetch() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByFramework(PROJECT_ID, ComplianceFrameworkIdentifier.SOC2))
                .thenReturn(List.of(makeRequirementMapping()));

        mockMvc.perform(get("/api/v1/compliance-framework-mappings")
                        .param("project", "ground-control")
                        .param("framework", "SOC2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].framework", is("SOC2")));

        verify(service).listByFramework(PROJECT_ID, ComplianceFrameworkIdentifier.SOC2);
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, MAPPING_ID)).thenReturn(makeRequirementMapping());

        mockMvc.perform(get("/api/v1/compliance-framework-mappings/{id}", MAPPING_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(MAPPING_ID.toString())));
    }

    @Test
    void updateReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.update(any())).thenReturn(makeRequirementMapping());

        mockMvc.perform(
                        put("/api/v1/compliance-framework-mappings/{id}", MAPPING_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "coverageLevel": "FULL",
                                  "rationale": "Now satisfies fully"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(MAPPING_ID.toString())));

        // Cluster-744 finding #6: assert the request->command field-pass-through.
        var captor = ArgumentCaptor.forClass(UpdateComplianceFrameworkMappingCommand.class);
        verify(service).update(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.projectId()).isEqualTo(PROJECT_ID);
        assertThat(cmd.mappingId()).isEqualTo(MAPPING_ID);
        assertThat(cmd.coverageLevel()).isEqualTo(CoverageLevel.FULL);
        assertThat(cmd.rationale()).isEqualTo("Now satisfies fully");
        // Null means "no change" — fields not supplied in the PUT body must
        // arrive as null on the command, not a default value.
        assertThat(cmd.framework()).isNull();
        assertThat(cmd.frameworkIdentifier()).isNull();
        assertThat(cmd.frameworkVersion()).isNull();
        assertThat(cmd.frameworkElement()).isNull();
    }

    @Test
    void updatePropagatesAllSuppliedFieldsToCommand() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.update(any())).thenReturn(makeRequirementMapping());

        mockMvc.perform(
                        put("/api/v1/compliance-framework-mappings/{id}", MAPPING_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "framework": "ISO_27001",
                                  "frameworkIdentifier": "Acme ISO",
                                  "frameworkVersion": "2022",
                                  "frameworkElement": "A.8.1",
                                  "coverageLevel": "COMPENSATING",
                                  "rationale": "Compensating control documented"
                                }
                                """))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(UpdateComplianceFrameworkMappingCommand.class);
        verify(service).update(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.framework()).isEqualTo(ComplianceFrameworkIdentifier.ISO_27001);
        assertThat(cmd.frameworkIdentifier()).isEqualTo("Acme ISO");
        assertThat(cmd.frameworkVersion()).isEqualTo("2022");
        assertThat(cmd.frameworkElement()).isEqualTo("A.8.1");
        assertThat(cmd.coverageLevel()).isEqualTo(CoverageLevel.COMPENSATING);
        assertThat(cmd.rationale()).isEqualTo("Compensating control documented");
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/compliance-framework-mappings/{id}", MAPPING_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(service).delete(PROJECT_ID, MAPPING_ID);
    }
}
