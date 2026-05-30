package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
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
