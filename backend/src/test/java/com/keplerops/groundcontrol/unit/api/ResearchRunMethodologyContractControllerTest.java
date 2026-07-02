package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.research.ResearchRunController;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.ContractEntryKind;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContract;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntry;
import com.keplerops.groundcontrol.domain.research.model.MethodologyRequirementsContractEntrySourceLink;
import com.keplerops.groundcontrol.domain.research.model.MethodologySourceState;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySelection;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunMethodologySource;
import com.keplerops.groundcontrol.domain.research.service.MethodologyRequirementsContractAggregate;
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
 * GC-RSCH-F007 / GC-RSCH-F008 / ADR-080 — controller slice for the methodology
 * requirements contract endpoints on {@link ResearchRunController}.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ResearchRunController.class)
class ResearchRunMethodologyContractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchRunService researchRunService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID SELECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID ARTIFACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID CONTRACT_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");
    private static final UUID ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final Instant NOW = Instant.parse("2026-06-25T00:00:00Z");

    private MethodologyRequirementsContractAggregate aggregate() {
        var project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        setField(project, "id", PROJECT_ID);
        var run = new ResearchRun(project, "RUN-1", AutonomyLevel.COPILOT);
        setField(run, "id", RUN_ID);
        var sel = new ResearchRunMethodologySelection(run, "systematic", "actor");
        setField(sel, "id", SELECTION_ID);
        var contract = new MethodologyRequirementsContract(run, sel, ARTIFACT_ID, 1, "1", "actor");
        setField(contract, "id", CONTRACT_ID);
        setField(contract, "createdAt", NOW);
        var source = new ResearchRunMethodologySource(sel, "doi:read", true, "actor");
        setField(source, "id", SOURCE_ID);
        setField(source, "state", MethodologySourceState.READ);
        var entry = new MethodologyRequirementsContractEntry(
                contract,
                ContractEntryKind.REQUIREMENT,
                "req-1",
                "the protocol must state databases searched",
                null,
                "actor");
        setField(entry, "id", ENTRY_ID);
        var link = new MethodologyRequirementsContractEntrySourceLink(entry, source, "p.1");
        setField(link, "id", UUID.randomUUID());
        return new MethodologyRequirementsContractAggregate(contract, List.of(entry), List.of(link), List.of());
    }

    @Test
    void record_happyPath_returns201() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.recordMethodologyRequirementsContract(eq(PROJECT_ID), eq(RUN_ID), any()))
                .thenReturn(aggregate());

        mockMvc.perform(post("/api/v1/research-runs/{id}/methodology/requirements-contract", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"entries\":[{\"kind\":\"REQUIREMENT\",\"entryKey\":\"req-1\",\"statement\":\"the protocol must state databases searched\",\"sourceLinks\":[{\"sourceId\":\""
                                        + SOURCE_ID + "\",\"locator\":\"p.1\"}]}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONTRACT_ID.toString()))
                .andExpect(jsonPath("$.methodKey").value("systematic"))
                .andExpect(jsonPath("$.entries[0].kind").value("REQUIREMENT"))
                .andExpect(jsonPath("$.entries[0].entryKey").value("req-1"))
                .andExpect(jsonPath("$.entries[0].sourceLinks[0].sourceRef").value("doi:read"));
    }

    @Test
    void record_emptyEntries_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/research-runs/{id}/methodology/requirements-contract", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void record_missingKind_returns422() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(post("/api/v1/research-runs/{id}/methodology/requirements-contract", RUN_ID)
                        .param("project", "research-p")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"entryKey\":\"r\",\"statement\":\"s\"}]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void get_happyPath_returns200() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.getMethodologyRequirementsContract(PROJECT_ID, RUN_ID))
                .thenReturn(aggregate());

        mockMvc.perform(get("/api/v1/research-runs/{id}/methodology/requirements-contract", RUN_ID)
                        .param("project", "research-p"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].entryKey").value("req-1"))
                .andExpect(jsonPath("$.artifactId").value(ARTIFACT_ID.toString()));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        when(projectService.requireProjectId(any())).thenReturn(PROJECT_ID);
        when(researchRunService.getMethodologyRequirementsContract(PROJECT_ID, RUN_ID))
                .thenThrow(new NotFoundException("No methodology requirements contract for run " + RUN_ID));

        mockMvc.perform(get("/api/v1/research-runs/{id}/methodology/requirements-contract", RUN_ID)
                        .param("project", "research-p"))
                .andExpect(status().isNotFound());
    }
}
