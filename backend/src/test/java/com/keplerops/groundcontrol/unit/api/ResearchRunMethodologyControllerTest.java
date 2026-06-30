package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.research.ResearchRunController;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
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

/**
 * GC-RSCH-F006 — controller slice for the methodology selection and source endpoints
 * on {@link ResearchRunController}.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ResearchRunController.class)
class ResearchRunMethodologyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchRunService researchRunService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SELECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final Instant NOW = Instant.parse("2026-06-25T00:00:00Z");

    private ResearchRun makeRun() {
        var project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        setField(project, "id", PROJECT_ID);
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        setField(run, "id", RUN_ID);
        return run;
    }

    private ResearchRunMethodologySelection makeSelection() {
        var run = makeRun();
        var sel = new ResearchRunMethodologySelection(run, "systematic", "test-actor");
        sel.setMethodLabel("Systematic Review");
        sel.setProfileVersion("1.0");
        sel.setCatalogVersion("2026-06");
        setField(sel, "id", SELECTION_ID);
        setField(sel, "createdAt", NOW);
        setField(sel, "updatedAt", NOW);
        return sel;
    }

    private ResearchRunMethodologySource makeSource(ResearchRunMethodologySelection sel) {
        // Sources created via recordMethodologySource are always optional (required=false).
        var src = new ResearchRunMethodologySource(sel, "doi:10.1234/example", false, "test-actor");
        src.setSourceLabel("Example Paper");
        setField(src, "id", SOURCE_ID);
        setField(src, "createdAt", NOW);
        setField(src, "updatedAt", NOW);
        return src;
    }

    // -----------------------------------------------------------------------
    // POST /{id}/methodology/selection
    // -----------------------------------------------------------------------

    @Test
    void selectMethodology_happyPath_returns201() throws Exception {
        var sel = makeSelection();
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.selectMethodology(eq(PROJECT_ID), eq(RUN_ID), any()))
                .thenReturn(sel);

        mockMvc.perform(post("/api/v1/research-runs/{id}/methodology/selection", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methodKey\":\"systematic\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SELECTION_ID.toString()))
                .andExpect(jsonPath("$.methodKey").value("systematic"))
                .andExpect(jsonPath("$.methodLabel").value("Systematic Review"));
    }

    @Test
    void selectMethodology_blankMethodKey_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/research-runs/{id}/methodology/selection", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methodKey\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void selectMethodology_unknownMethod_returns422() throws Exception {
        // An unknown method key is rejected by the service against the backend
        // catalog (ADR-077), surfacing as a 422 validation error.
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.selectMethodology(eq(PROJECT_ID), eq(RUN_ID), any()))
                .thenThrow(new DomainValidationException(
                        "Unknown methodology method key: nope",
                        "research_run_methodology_unknown_method",
                        java.util.Map.of("methodKey", "nope")));

        mockMvc.perform(post("/api/v1/research-runs/{id}/methodology/selection", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methodKey\":\"nope\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("research_run_methodology_unknown_method"));
    }

    // -----------------------------------------------------------------------
    // GET /methodology/catalog
    // -----------------------------------------------------------------------

    @Test
    void methodologyCatalog_returnsMethodsWithRequiredSources() throws Exception {
        var profile = new com.keplerops.groundcontrol.domain.research.model.MethodProfile(
                "systematic",
                "Systematic review",
                "1",
                "1",
                List.of(
                        new com.keplerops.groundcontrol.domain.research.model.MethodProfileSource(
                                "FRM9HPNG", "SLR Guidelines"),
                        new com.keplerops.groundcontrol.domain.research.model.MethodProfileSource(
                                "MJX3HCT5", "PRISMA 2020")));
        when(researchRunService.listMethodologyCatalog()).thenReturn(List.of(profile));

        mockMvc.perform(get("/api/v1/research-runs/methodology/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogVersion").value("1"))
                .andExpect(jsonPath("$.methods[0].methodKey").value("systematic"))
                .andExpect(jsonPath("$.methods[0].label").value("Systematic review"))
                .andExpect(jsonPath("$.methods[0].requiredSources[0].ref").value("FRM9HPNG"))
                .andExpect(jsonPath("$.methods[0].requiredSources[1].ref").value("MJX3HCT5"));
    }

    // -----------------------------------------------------------------------
    // GET /{id}/methodology/selection
    // -----------------------------------------------------------------------

    @Test
    void getMethodologySelection_happyPath_returns200() throws Exception {
        var sel = makeSelection();
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.getMethodologySelection(eq(PROJECT_ID), eq(RUN_ID)))
                .thenReturn(sel);

        mockMvc.perform(get("/api/v1/research-runs/{id}/methodology/selection", RUN_ID)
                        .param("project", "research-p"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SELECTION_ID.toString()))
                .andExpect(jsonPath("$.methodKey").value("systematic"));
    }

    // -----------------------------------------------------------------------
    // POST /{id}/methodology/sources
    // -----------------------------------------------------------------------

    @Test
    void recordMethodologySource_happyPath_returns201() throws Exception {
        var sel = makeSelection();
        var src = makeSource(sel);
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.recordMethodologySource(eq(PROJECT_ID), eq(RUN_ID), any()))
                .thenReturn(src);

        mockMvc.perform(post("/api/v1/research-runs/{id}/methodology/sources", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRef\":\"doi:10.1234/example\"," + "\"sourceLabel\":\"Example Paper\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.sourceRef").value("doi:10.1234/example"))
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.state").value("ATTEMPTED"));
    }

    @Test
    void recordMethodologySource_blankSourceRef_returns400() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/research-runs/{id}/methodology/sources", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRef\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // -----------------------------------------------------------------------
    // PATCH /{id}/methodology/sources/{sourceId}
    // -----------------------------------------------------------------------

    @Test
    void updateMethodologySourceState_happyPath_returns200() throws Exception {
        var sel = makeSelection();
        var src = makeSource(sel);
        setField(src, "state", MethodologySourceState.READ);
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.updateMethodologySourceState(eq(PROJECT_ID), eq(RUN_ID), eq(SOURCE_ID), any()))
                .thenReturn(src);

        mockMvc.perform(patch("/api/v1/research-runs/{id}/methodology/sources/{sourceId}", RUN_ID, SOURCE_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"READ\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READ"));
    }

    @Test
    void updateMethodologySourceState_nullState_returns400() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(patch("/api/v1/research-runs/{id}/methodology/sources/{sourceId}", RUN_ID, SOURCE_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":null}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // -----------------------------------------------------------------------
    // GET /{id}/methodology/sources
    // -----------------------------------------------------------------------

    @Test
    void listMethodologySources_happyPath_returns200() throws Exception {
        var sel = makeSelection();
        var src = makeSource(sel);
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.listMethodologySources(eq(PROJECT_ID), eq(RUN_ID)))
                .thenReturn(List.of(src));

        mockMvc.perform(get("/api/v1/research-runs/{id}/methodology/sources", RUN_ID)
                        .param("project", "research-p"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$[0].selectionId").value(SELECTION_ID.toString()));
    }
}
