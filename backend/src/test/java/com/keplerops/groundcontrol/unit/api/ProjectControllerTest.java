package com.keplerops.groundcontrol.unit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.api.projects.ProjectController;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.CreateProjectCommand;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.projects.service.UpdateProjectCommand;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContributionType;
import com.keplerops.groundcontrol.domain.research.model.IntendedOutput;
import com.keplerops.groundcontrol.domain.research.model.ResearchIntake;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchIntakeService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ProjectController.class)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private ResearchIntakeService researchIntakeService;

    @BeforeEach
    void defaultStubs() {
        when(researchIntakeService.findByProject(any())).thenReturn(Optional.empty());
    }

    private Project makeProject(String identifier, String name) {
        return makeProject(identifier, name, ProjectType.SOFTWARE);
    }

    private Project makeProject(String identifier, String name, ProjectType type) {
        var project = new Project(identifier, name, type);
        TestUtil.setField(project, "id", UUID.randomUUID());
        return project;
    }

    private ResearchIntake makeIntake(Project project) {
        var intake = new ResearchIntake(
                project,
                "Investigate research goal",
                ContributionType.REVIEW,
                IntendedOutput.SCOPING_REVIEW,
                AutonomyLevel.COPILOT,
                List.of("cite_resolve", "zotero_search"));
        TestUtil.setField(intake, "id", UUID.randomUUID());
        return intake;
    }

    @Nested
    class Create {

        @Test
        void returns201() throws Exception {
            var project = makeProject("my-project", "My Project");
            when(projectService.create(any(CreateProjectCommand.class))).thenReturn(project);

            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "my-project",
                              "name": "My Project"
                            }
                            """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.identifier", is("my-project")))
                    .andExpect(jsonPath("$.name", is("My Project")))
                    .andExpect(jsonPath("$.type", is("SOFTWARE")));
        }

        @Test
        void blankIdentifier_returns422() throws Exception {
            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "",
                              "name": "My Project"
                            }
                            """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void invalidIdentifierPattern_returns422() throws Exception {
            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "My Project!",
                              "name": "My Project"
                            }
                            """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void duplicateIdentifier_returns409() throws Exception {
            when(projectService.create(any(CreateProjectCommand.class)))
                    .thenThrow(new ConflictException("Project with identifier 'my-project' already exists"));

            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "my-project",
                              "name": "My Project"
                            }
                            """))
                    .andExpect(status().isConflict());
        }

        @Test
        void researchType_withIntake_returns201() throws Exception {
            var project = makeProject("research-project", "Research Project", ProjectType.RESEARCH);
            when(projectService.create(any(CreateProjectCommand.class))).thenReturn(project);
            when(researchIntakeService.findByProject(project)).thenReturn(Optional.of(makeIntake(project)));

            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "research-project",
                              "name": "Research Project",
                              "type": "RESEARCH",
                              "researchIntake": {
                                "goal": "Investigate citation hallucination",
                                "contributionType": "REVIEW",
                                "intendedOutput": "SCOPING_REVIEW",
                                "autonomyLevel": "COPILOT",
                                "allowedTools": ["cite_resolve", "zotero_search"]
                              }
                            }
                            """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type", is("RESEARCH")))
                    .andExpect(jsonPath("$.researchIntake.goal", is("Investigate research goal")))
                    .andExpect(jsonPath("$.researchIntake.contributionType", is("REVIEW")))
                    .andExpect(jsonPath("$.researchIntake.intendedOutput", is("SCOPING_REVIEW")))
                    .andExpect(jsonPath("$.researchIntake.autonomyLevel", is("COPILOT")))
                    .andExpect(jsonPath(
                            "$.researchIntake.allowedTools", containsInAnyOrder("cite_resolve", "zotero_search")));

            // Verify the controller actually forwards the request-body intake
            // into the CreateProjectCommand (closes Step 6.6 test-quality
            // finding #3): any() would let a controller bug that drops
            // request.researchIntake() pass undetected.
            var captor = ArgumentCaptor.forClass(CreateProjectCommand.class);
            verify(projectService).create(captor.capture());
            var cmd = captor.getValue();
            assertThat(cmd.identifier()).isEqualTo("research-project");
            assertThat(cmd.type()).isEqualTo(ProjectType.RESEARCH);
            assertThat(cmd.researchIntake()).isNotNull();
            assertThat(cmd.researchIntake().goal()).isEqualTo("Investigate citation hallucination");
            assertThat(cmd.researchIntake().contributionType().name()).isEqualTo("REVIEW");
            assertThat(cmd.researchIntake().intendedOutput().name()).isEqualTo("SCOPING_REVIEW");
            assertThat(cmd.researchIntake().autonomyLevel().name()).isEqualTo("COPILOT");
            assertThat(cmd.researchIntake().allowedTools()).containsExactlyInAnyOrder("cite_resolve", "zotero_search");
        }

        @Test
        void researchType_missingIntake_returns422() throws Exception {
            when(projectService.create(any(CreateProjectCommand.class)))
                    .thenThrow(new DomainValidationException(
                            "type=RESEARCH requires researchIntake to be present",
                            "research_intake_required",
                            Map.of("type", "RESEARCH")));

            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "research-project",
                              "name": "Research Project",
                              "type": "RESEARCH"
                            }
                            """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void softwareType_withIntake_returns422() throws Exception {
            when(projectService.create(any(CreateProjectCommand.class)))
                    .thenThrow(new DomainValidationException(
                            "researchIntake is only allowed when type=RESEARCH",
                            "research_intake_not_allowed",
                            Map.of("type", "SOFTWARE")));

            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "sw-project",
                              "name": "SW Project",
                              "type": "SOFTWARE",
                              "researchIntake": {
                                "goal": "x",
                                "contributionType": "REVIEW",
                                "intendedOutput": "SCOPING_REVIEW",
                                "autonomyLevel": "COPILOT",
                                "allowedTools": []
                              }
                            }
                            """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void researchType_blankGoal_returns422() throws Exception {
            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "research-project",
                              "name": "Research Project",
                              "type": "RESEARCH",
                              "researchIntake": {
                                "goal": "",
                                "contributionType": "REVIEW",
                                "intendedOutput": "SCOPING_REVIEW",
                                "autonomyLevel": "COPILOT",
                                "allowedTools": []
                              }
                            }
                            """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void researchType_invalidEnum_returns422() throws Exception {
            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "research-project",
                              "name": "Research Project",
                              "type": "RESEARCH",
                              "researchIntake": {
                                "goal": "x",
                                "contributionType": "BOGUS",
                                "intendedOutput": "SCOPING_REVIEW",
                                "autonomyLevel": "COPILOT",
                                "allowedTools": []
                              }
                            }
                            """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void researchType_missingAllowedTools_returns422() throws Exception {
            mockMvc.perform(
                            post("/api/v1/projects")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "identifier": "research-project",
                              "name": "Research Project",
                              "type": "RESEARCH",
                              "researchIntake": {
                                "goal": "x",
                                "contributionType": "REVIEW",
                                "intendedOutput": "SCOPING_REVIEW",
                                "autonomyLevel": "COPILOT"
                              }
                            }
                            """))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    class ListProjects {

        @Test
        void returns200() throws Exception {
            when(projectService.list()).thenReturn(List.of(makeProject("p1", "P1"), makeProject("p2", "P2")));

            mockMvc.perform(get("/api/v1/projects"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].identifier", is("p1")))
                    .andExpect(jsonPath("$[0].type", is("SOFTWARE")));
        }
    }

    @Nested
    class GetByIdentifier {

        @Test
        void returns200() throws Exception {
            when(projectService.getByIdentifier("my-project")).thenReturn(makeProject("my-project", "My Project"));

            mockMvc.perform(get("/api/v1/projects/my-project"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.identifier", is("my-project")))
                    .andExpect(jsonPath("$.type", is("SOFTWARE")));
        }

        @Test
        void researchProject_includesIntake() throws Exception {
            var project = makeProject("research-p", "Research", ProjectType.RESEARCH);
            when(projectService.getByIdentifier("research-p")).thenReturn(project);
            when(researchIntakeService.findByProject(project)).thenReturn(Optional.of(makeIntake(project)));

            mockMvc.perform(get("/api/v1/projects/research-p"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type", is("RESEARCH")))
                    .andExpect(jsonPath("$.researchIntake.goal", is("Investigate research goal")));
        }

        @Test
        void notFound_returns404() throws Exception {
            when(projectService.getByIdentifier("nonexistent"))
                    .thenThrow(new NotFoundException("Project not found: nonexistent"));

            mockMvc.perform(get("/api/v1/projects/nonexistent")).andExpect(status().isNotFound());
        }
    }

    @Nested
    class Update {

        @Test
        void returns200() throws Exception {
            var updated = makeProject("my-project", "Updated Name");
            when(projectService.updateByIdentifier(eq("my-project"), any(UpdateProjectCommand.class)))
                    .thenReturn(updated);

            mockMvc.perform(
                            put("/api/v1/projects/my-project")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "name": "Updated Name"
                            }
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", is("Updated Name")));
        }
    }

    @Nested
    class ReplaceResearchIntake {

        @Test
        void returns200() throws Exception {
            var project = makeProject("research-p", "Research", ProjectType.RESEARCH);
            var intake = makeIntake(project);
            when(projectService.getByIdentifier("research-p")).thenReturn(project);
            when(researchIntakeService.replace(eq(project), any(ResearchIntakeCommand.class)))
                    .thenReturn(intake);

            mockMvc.perform(
                            put("/api/v1/projects/research-p/research-intake")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "goal": "Updated goal",
                              "contributionType": "TAXONOMY",
                              "intendedOutput": "TAXONOMY_PAPER",
                              "autonomyLevel": "AUTONOMOUS",
                              "allowedTools": ["cite_resolve"]
                            }
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.goal", is("Investigate research goal")));
        }

        @Test
        void notResearchType_returns422() throws Exception {
            var project = makeProject("sw-p", "SW", ProjectType.SOFTWARE);
            when(projectService.getByIdentifier("sw-p")).thenReturn(project);
            when(researchIntakeService.replace(eq(project), any(ResearchIntakeCommand.class)))
                    .thenThrow(new DomainValidationException(
                            "ResearchIntake can only be replaced on RESEARCH projects",
                            "research_intake_type_mismatch",
                            Map.of("project_type", "SOFTWARE")));

            mockMvc.perform(
                            put("/api/v1/projects/sw-p/research-intake")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "goal": "x",
                              "contributionType": "REVIEW",
                              "intendedOutput": "SCOPING_REVIEW",
                              "autonomyLevel": "COPILOT",
                              "allowedTools": []
                            }
                            """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void noIntakeExists_returns404() throws Exception {
            var project = makeProject("research-p", "Research", ProjectType.RESEARCH);
            when(projectService.getByIdentifier("research-p")).thenReturn(project);
            when(researchIntakeService.replace(eq(project), any(ResearchIntakeCommand.class)))
                    .thenThrow(new NotFoundException("ResearchIntake not found for project research-p"));

            mockMvc.perform(
                            put("/api/v1/projects/research-p/research-intake")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "goal": "x",
                              "contributionType": "REVIEW",
                              "intendedOutput": "SCOPING_REVIEW",
                              "autonomyLevel": "COPILOT",
                              "allowedTools": []
                            }
                            """))
                    .andExpect(status().isNotFound());
        }

        @Test
        void projectNotFound_returns404() throws Exception {
            when(projectService.getByIdentifier("nonexistent"))
                    .thenThrow(new NotFoundException("Project not found: nonexistent"));

            mockMvc.perform(
                            put("/api/v1/projects/nonexistent/research-intake")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                            {
                              "goal": "x",
                              "contributionType": "REVIEW",
                              "intendedOutput": "SCOPING_REVIEW",
                              "autonomyLevel": "COPILOT",
                              "allowedTools": []
                            }
                            """))
                    .andExpect(status().isNotFound());
        }
    }
}
