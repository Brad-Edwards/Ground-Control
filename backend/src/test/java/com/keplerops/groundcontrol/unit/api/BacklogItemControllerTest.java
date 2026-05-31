package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.backlog.BacklogItemController;
import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import com.keplerops.groundcontrol.domain.backlog.model.WsjfDistribution;
import com.keplerops.groundcontrol.domain.backlog.service.BacklogItemService;
import com.keplerops.groundcontrol.domain.backlog.service.WsjfAnalysisService;
import com.keplerops.groundcontrol.domain.backlog.state.BacklogItemStatus;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
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
@WebMvcTest(BacklogItemController.class)
class BacklogItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BacklogItemService backlogItemService;

    @MockitoBean
    private WsjfAnalysisService wsjfAnalysisService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final Instant NOW = Instant.parse("2026-05-30T12:00:00Z");

    private BacklogItem make() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var item = new BacklogItem(project, "BI-1", "Add feature X");
        item.setUserBusinessValue(CostOfDelayComponent.triangular(2, 5, 8, "alice"));
        item.setTimeCriticality(CostOfDelayComponent.triangular(1, 3, 5, "alice"));
        item.setRiskReductionOpportunityEnablement(CostOfDelayComponent.point(2, "alice"));
        item.setJobDuration(CostOfDelayComponent.triangular(1, 2, 4, "alice"));
        item.setCreatedBy("alice");
        setField(item, "id", ITEM_ID);
        setField(item, "createdAt", NOW);
        setField(item, "updatedAt", NOW);
        return item;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.create(any())).thenReturn(make());

        mockMvc.perform(
                        post("/api/v1/backlog-items")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "BI-1",
                                  "title": "Add feature X",
                                  "userBusinessValue": {"kind":"TRIANGULAR","min":2,"mode":5,"max":8,"attributedTo":"alice"},
                                  "timeCriticality": {"kind":"TRIANGULAR","min":1,"mode":3,"max":5,"attributedTo":"alice"},
                                  "riskReductionOpportunityEnablement": {"kind":"POINT","min":2,"mode":2,"max":2,"attributedTo":"alice"},
                                  "jobDuration": {"kind":"TRIANGULAR","min":1,"mode":2,"max":4,"attributedTo":"alice"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(ITEM_ID.toString())))
                .andExpect(jsonPath("$.uid", is("BI-1")))
                .andExpect(jsonPath("$.status", is("CANDIDATE")))
                .andExpect(jsonPath("$.userBusinessValue.kind", is("TRIANGULAR")))
                .andExpect(jsonPath("$.userBusinessValue.attributedTo", is("alice")));
    }

    @Test
    void wsjfEndpointReturnsDistributionWithAdr035Envelope() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        var ubv = CostOfDelayComponent.point(5, "alice");
        var jd = CostOfDelayComponent.point(1, "alice");
        var dist = WsjfDistribution.compute(ubv, ubv, ubv, jd, 7L, 100);
        when(wsjfAnalysisService.computeForItem(eq(PROJECT_ID), eq(ITEM_ID), anyLong(), anyInt()))
                .thenReturn(dist);

        mockMvc.perform(get("/api/v1/backlog-items/" + ITEM_ID + "/wsjf")
                        .param("project", "ground-control")
                        .param("seed", "7")
                        .param("iterations", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("wsjf")))
                .andExpect(jsonPath("$.scale", is("dimensionless")))
                .andExpect(jsonPath("$.units", is("value-per-week")))
                .andExpect(jsonPath("$.backlogItemId", is(ITEM_ID.toString())))
                .andExpect(jsonPath("$.iterations", is(100)))
                .andExpect(jsonPath("$.samples.length()", is(100)));
    }

    @Test
    void wsjfEndpointRejectsAboveMaxIterations() throws Exception {
        // Caller cannot push past the controller cap; the domain layer rejects
        // <= 0 but only the controller bounds the upper end to keep heap
        // allocation finite. Authenticated DoS guard for the bearer-protected
        // endpoint.
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(get("/api/v1/backlog-items/" + ITEM_ID + "/wsjf")
                        .param("project", "ground-control")
                        .param("iterations", String.valueOf(Integer.MAX_VALUE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")))
                .andExpect(jsonPath("$.error.detail.field", is("iterations")));
    }

    @Test
    void wsjfEndpointRejectsZeroIterations() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(get("/api/v1/backlog-items/" + ITEM_ID + "/wsjf")
                        .param("project", "ground-control")
                        .param("iterations", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void listReturnsAllItemsForProject() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.listByProject(PROJECT_ID)).thenReturn(List.of(make()));

        mockMvc.perform(get("/api/v1/backlog-items").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].uid", is("BI-1")));
    }

    @Test
    void listReturnsEmptyListWhenNoItems() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.listByProject(PROJECT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/backlog-items").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getByIdReturnsItemWhenFound() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.getById(PROJECT_ID, ITEM_ID)).thenReturn(make());

        mockMvc.perform(get("/api/v1/backlog-items/" + ITEM_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(ITEM_ID.toString())))
                .andExpect(jsonPath("$.uid", is("BI-1")));
    }

    @Test
    void getByIdReturns404WhenNotFound() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.getById(PROJECT_ID, ITEM_ID))
                .thenThrow(new NotFoundException("BacklogItem not found: " + ITEM_ID));

        mockMvc.perform(get("/api/v1/backlog-items/" + ITEM_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    void getByUidReturnsItemWhenFound() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.getByUid(PROJECT_ID, "BI-1")).thenReturn(make());

        mockMvc.perform(get("/api/v1/backlog-items/uid/BI-1").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("BI-1")))
                .andExpect(jsonPath("$.status", is("CANDIDATE")));
    }

    @Test
    void getByUidReturns404WhenNotFound() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.getByUid(PROJECT_ID, "MISSING"))
                .thenThrow(new NotFoundException("BacklogItem not found: MISSING"));

        mockMvc.perform(get("/api/v1/backlog-items/uid/MISSING").param("project", "ground-control"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    void updateReturns200WithUpdatedItem() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.update(eq(PROJECT_ID), eq(ITEM_ID), any())).thenReturn(make());

        mockMvc.perform(
                        put("/api/v1/backlog-items/" + ITEM_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"title": "Updated title"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid", is("BI-1")));
    }

    @Test
    void updateReturns404WhenItemNotFound() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.update(eq(PROJECT_ID), eq(ITEM_ID), any()))
                .thenThrow(new NotFoundException("BacklogItem not found: " + ITEM_ID));

        mockMvc.perform(
                        put("/api/v1/backlog-items/" + ITEM_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"title": "Updated title"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void transitionStatusReturns200WithNewStatus() throws Exception {
        var ready = make();
        ready.setUserBusinessValue(CostOfDelayComponent.point(5, "alice"));
        ready.setTimeCriticality(CostOfDelayComponent.point(3, "alice"));
        ready.setRiskReductionOpportunityEnablement(CostOfDelayComponent.point(2, "alice"));
        ready.setJobDuration(CostOfDelayComponent.point(2, "alice"));
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.transitionStatus(PROJECT_ID, ITEM_ID, BacklogItemStatus.ARCHIVED))
                .thenReturn(ready);

        mockMvc.perform(
                        put("/api/v1/backlog-items/" + ITEM_ID + "/status")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status": "ARCHIVED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(ITEM_ID.toString())));
    }

    @Test
    void transitionStatusReturns422ForNullStatus() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        put("/api/v1/backlog-items/" + ITEM_ID + "/status")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status": null}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transitionStatusReturns422ForInvalidEnumValue() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        put("/api/v1/backlog-items/" + ITEM_ID + "/status")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status": "NOT_A_STATUS"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void deleteReturns204WhenItemExists() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        doNothing().when(backlogItemService).delete(PROJECT_ID, ITEM_ID);

        mockMvc.perform(delete("/api/v1/backlog-items/" + ITEM_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenItemNotFound() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        org.mockito.Mockito.doThrow(new NotFoundException("BacklogItem not found: " + ITEM_ID))
                .when(backlogItemService)
                .delete(PROJECT_ID, ITEM_ID);

        mockMvc.perform(delete("/api/v1/backlog-items/" + ITEM_ID).param("project", "ground-control"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createReturns409WhenUidAlreadyExists() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.create(any()))
                .thenThrow(
                        new ConflictException("BacklogItem with UID 'BI-1' already exists in project ground-control"));

        mockMvc.perform(
                        post("/api/v1/backlog-items")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "BI-1",
                                  "title": "Add feature X"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("conflict")));
    }

    @Test
    void createReturns422WhenRequiredFieldsMissing() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(
                        post("/api/v1/backlog-items")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "title": "Add feature X"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void wsjfEndpointRejectsNegativeIterations() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);

        mockMvc.perform(get("/api/v1/backlog-items/" + ITEM_ID + "/wsjf")
                        .param("project", "ground-control")
                        .param("iterations", "-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")))
                .andExpect(jsonPath("$.error.detail.field", is("iterations")));
    }
}
