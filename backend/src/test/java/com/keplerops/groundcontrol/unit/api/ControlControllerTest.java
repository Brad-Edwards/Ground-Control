package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.controls.ControlController;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.service.ControlService;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.controls.state.ControlStatus;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
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
@WebMvcTest(ControlController.class)
class ControlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ControlService controlService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000500");
    private static final Instant NOW = Instant.parse("2026-04-05T12:00:00Z");

    private Control makeControl() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        control.setDescription("Network access control policy");
        control.setOwner("Security Team");
        control.setCategory("Access Control");
        control.setSource("ISO 27001 A.9");
        setField(control, "id", CONTROL_ID);
        setField(control, "createdAt", NOW);
        setField(control, "updatedAt", NOW);
        return control;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(controlService.create(any())).thenReturn(makeControl());

        mockMvc.perform(
                        post("/api/v1/controls")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "CTRL-001",
                                  "title": "Access Control",
                                  "controlFunction": "PREVENTIVE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(CONTROL_ID.toString())))
                .andExpect(jsonPath("$.uid", is("CTRL-001")))
                .andExpect(jsonPath("$.controlFunction", is("PREVENTIVE")))
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    void listReturnsControls() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(controlService.listByProject(PROJECT_ID)).thenReturn(List.of(makeControl()));

        mockMvc.perform(get("/api/v1/controls").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("CTRL-001")));
    }

    @Test
    void getByIdReturnsControl() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(controlService.getById(PROJECT_ID, CONTROL_ID)).thenReturn(makeControl());

        mockMvc.perform(get("/api/v1/controls/{id}", CONTROL_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("CTRL-001")));
    }

    @Test
    void updateReturnsUpdatedControl() throws Exception {
        var control = makeControl();
        control.setTitle("Updated Title");
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(controlService.update(eq(PROJECT_ID), eq(CONTROL_ID), any())).thenReturn(control);

        mockMvc.perform(
                        put("/api/v1/controls/{id}", CONTROL_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"title":"Updated Title"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Updated Title")));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/controls/{id}", CONTROL_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(controlService).delete(PROJECT_ID, CONTROL_ID);
    }

    @Test
    void transitionStatusReturnsControl() throws Exception {
        var control = makeControl();
        control.transitionStatus(ControlStatus.PROPOSED);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(controlService.transitionStatus(PROJECT_ID, CONTROL_ID, ControlStatus.PROPOSED))
                .thenReturn(control);

        mockMvc.perform(
                        put("/api/v1/controls/{id}/status", CONTROL_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status":"PROPOSED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROPOSED")));
    }

    @Test
    void transitionStatusReturns409WhenImplementationEvidenceMissing() throws Exception {
        // GC-GRC-011: the service's evidence gate throws ConflictException; the controller
        // propagates it through GlobalExceptionHandler as a 409 with the stable envelope.
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        java.util.Map<String, java.io.Serializable> detail = new java.util.LinkedHashMap<>();
        detail.put("controlUid", "CTRL-001");
        detail.put("targetStatus", "IMPLEMENTED");
        detail.put("missingCodeLink", true);
        detail.put("missingEfficacyTest", false);
        when(controlService.transitionStatus(PROJECT_ID, CONTROL_ID, ControlStatus.IMPLEMENTED))
                .thenThrow(new com.keplerops.groundcontrol.domain.exception.ConflictException(
                        "Control CTRL-001 cannot transition to IMPLEMENTED without a CODE implementation"
                                + " link and an efficacy test (GC-GRC-011).",
                        "control_missing_implementation_evidence",
                        detail));

        mockMvc.perform(
                        put("/api/v1/controls/{id}/status", CONTROL_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status":"IMPLEMENTED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("control_missing_implementation_evidence")))
                .andExpect(jsonPath("$.error.detail.missingCodeLink", is(true)));
    }
}
