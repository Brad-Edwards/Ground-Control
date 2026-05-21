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

import com.keplerops.groundcontrol.api.riskcontrol.ScopedControlImplementationController;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskcontrol.model.ScopedControlImplementation;
import com.keplerops.groundcontrol.domain.riskcontrol.service.ScopedControlImplementationService;
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
@WebMvcTest(ScopedControlImplementationController.class)
class ScopedControlImplementationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScopedControlImplementationService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONTROL_ID = UUID.fromString("00000000-0000-0000-0000-000000000500");
    private static final UUID SCI_ID = UUID.fromString("00000000-0000-0000-0000-000000000700");
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    private ScopedControlImplementation makeSci() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var control = new Control(project, "CTRL-001", "Access Control", ControlFunction.PREVENTIVE);
        setField(control, "id", CONTROL_ID);
        var sci = new ScopedControlImplementation(project, "SCI-001", control, "Email Gateway Implementation");
        sci.setImplementationScope("Email perimeter only");
        setField(sci, "id", SCI_ID);
        setField(sci, "createdAt", NOW);
        setField(sci, "updatedAt", NOW);
        return sci;
    }

    @Test
    void createReturns201WithSciFields() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.create(any())).thenReturn(makeSci());

        mockMvc.perform(
                        post("/api/v1/scoped-control-implementations")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "SCI-001",
                                  "controlId": "00000000-0000-0000-0000-000000000500",
                                  "name": "Email Gateway Implementation",
                                  "implementationScope": "Email perimeter only"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(SCI_ID.toString())))
                .andExpect(jsonPath("$.uid", is("SCI-001")))
                .andExpect(jsonPath("$.controlId", is(CONTROL_ID.toString())))
                .andExpect(jsonPath("$.name", is("Email Gateway Implementation")))
                .andExpect(jsonPath("$.implementationScope", is("Email perimeter only")));
    }

    @Test
    void listReturns200() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID)).thenReturn(List.of(makeSci()));

        mockMvc.perform(get("/api/v1/scoped-control-implementations").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uid", is("SCI-001")));
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, SCI_ID)).thenReturn(makeSci());

        mockMvc.perform(get("/api/v1/scoped-control-implementations/{id}", SCI_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(SCI_ID.toString())));
    }

    @Test
    void updateReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.update(any())).thenReturn(makeSci());

        mockMvc.perform(
                        put("/api/v1/scoped-control-implementations/{id}", SCI_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "name": "Updated Email Gateway Implementation",
                                  "implementationScope": "Email perimeter only"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(SCI_ID.toString())))
                .andExpect(jsonPath("$.uid", is("SCI-001")));
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/scoped-control-implementations/{id}", SCI_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(service).delete(PROJECT_ID, SCI_ID);
    }
}
