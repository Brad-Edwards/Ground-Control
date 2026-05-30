package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.compliance.ComplianceDriftController;
import com.keplerops.groundcontrol.domain.compliance.model.ComplianceDriftEvent;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceDriftDetectorService;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceDriftDetectorService.DetectorLiveness;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftCategory;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftSeverity;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(ComplianceDriftController.class)
class ComplianceDriftControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ComplianceDriftDetectorService service;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000999");
    private static final Instant NOW = Instant.parse("2026-05-30T12:00:00Z");

    private ComplianceDriftEvent makeEvent() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var event = new ComplianceDriftEvent(
                project,
                ComplianceDriftCategory.EVIDENCE_EXPIRED,
                ComplianceDriftSeverity.WARN,
                "EVIDENCE_ARTIFACT",
                UUID.randomUUID(),
                "Evidence artifact expired: uid=EVD-0001",
                NOW);
        setField(event, "id", EVENT_ID);
        setField(event, "createdAt", NOW);
        setField(event, "updatedAt", NOW);
        return event;
    }

    @Test
    void listReturnsEventsForProject() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID, null)).thenReturn(List.of(makeEvent()));

        mockMvc.perform(get("/api/v1/compliance-drift-events").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].category", is("EVIDENCE_EXPIRED")))
                .andExpect(jsonPath("$[0].severity", is("WARN")));
    }

    @Test
    void listFiltersByCategory() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.listByProject(PROJECT_ID, ComplianceDriftCategory.EVIDENCE_EXPIRED))
                .thenReturn(List.of(makeEvent()));

        mockMvc.perform(get("/api/v1/compliance-drift-events")
                        .param("project", "ground-control")
                        .param("category", "EVIDENCE_EXPIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void getByIdReturns200() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.getById(PROJECT_ID, EVENT_ID)).thenReturn(makeEvent());

        mockMvc.perform(get("/api/v1/compliance-drift-events/{id}", EVENT_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(EVENT_ID.toString())));
    }

    @Test
    void acknowledgeReturnsUpdatedEvent() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var acked = makeEvent();
        acked.setAcknowledgedAt(NOW);
        when(service.acknowledge(PROJECT_ID, EVENT_ID)).thenReturn(acked);

        mockMvc.perform(post("/api/v1/compliance-drift-events/{id}/acknowledge", EVENT_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt", is(NOW.toString())));
    }

    @Test
    void acknowledgeReturns409WhenAlreadyAcknowledged() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(service.acknowledge(eq(PROJECT_ID), eq(EVENT_ID)))
                .thenThrow(new ConflictException("already acknowledged"));

        mockMvc.perform(post("/api/v1/compliance-drift-events/{id}/acknowledge", EVENT_ID)
                        .param("project", "ground-control"))
                .andExpect(status().isConflict());
    }

    @Test
    void livenessSurfacesDetectorState() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        var lastSweep = NOW.minusSeconds(60);
        var liveness = new DetectorLiveness(NOW, NOW.minusSeconds(120), 3, lastSweep);
        when(service.liveness(PROJECT_ID)).thenReturn(liveness);

        mockMvc.perform(get("/api/v1/compliance-drift-events/liveness").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampledAt", is(NOW.toString())))
                .andExpect(jsonPath("$.lastDetectedAt", is(NOW.minusSeconds(120).toString())))
                .andExpect(jsonPath("$.lastSweepAt", is(lastSweep.toString())))
                .andExpect(jsonPath("$.lagSeconds", is(greaterThanOrEqualTo(120))))
                .andExpect(jsonPath("$.unacknowledgedCount", is(3)));
    }

    @Test
    void livenessOmitsLastSweepAtWhenJobDisabled() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        // Sweep job disabled — supplier returns null, JsonInclude.NON_NULL
        // drops the field from the response.
        var liveness = new DetectorLiveness(NOW, null, 0, null);
        when(service.liveness(PROJECT_ID)).thenReturn(liveness);

        mockMvc.perform(get("/api/v1/compliance-drift-events/liveness").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampledAt", is(NOW.toString())))
                .andExpect(jsonPath("$.lastSweepAt").doesNotExist())
                .andExpect(jsonPath("$.unacknowledgedCount", is(0)));
    }

    @Test
    void appendOnlyContractHasNoPutRoute() throws Exception {
        mockMvc.perform(put("/api/v1/compliance-drift-events/{id}", EVENT_ID).param("project", "ground-control"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void appendOnlyContractHasNoDeleteRoute() throws Exception {
        mockMvc.perform(delete("/api/v1/compliance-drift-events/{id}", EVENT_ID).param("project", "ground-control"))
                .andExpect(status().isMethodNotAllowed());
    }
}
